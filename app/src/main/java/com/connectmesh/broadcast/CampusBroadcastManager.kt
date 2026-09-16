package com.connectmesh.broadcast

import com.connectmesh.auth.AuthorizationManager
import com.connectmesh.auth.UserRole
import com.connectmesh.diagnostics.NetworkEventLogger
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Campus-Wide Broadcast Manager.
 * Manages creation, ECDSA signing, ChaCha20-Poly1305 encryption, anti-replay deduplication, and broadcast state.
 */
class CampusBroadcastManager(
    val authorizationManager: AuthorizationManager
) {
    private val broadcasts = ConcurrentHashMap<String, CollegeBroadcast>()
    private val campusKeys = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>() // scope -> (version -> 32B key)
    private val processedBroadcastIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Initializes or registers a campus symmetric broadcast key for an institutional scope.
     */
    fun registerCampusKey(institutionScope: String, keyVersion: Int, keyBytes: ByteArray) {
        val keysMap = campusKeys.computeIfAbsent(institutionScope) { ConcurrentHashMap() }
        keysMap[keyVersion] = keyBytes
    }

    /**
     * Rotates campus key for an institutional scope (e.g., when a user is revoked).
     */
    fun rotateCampusKey(institutionScope: String, currentVersion: Int): Pair<Int, ByteArray> {
        val newVersion = currentVersion + 1
        val newKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        registerCampusKey(institutionScope, newVersion, newKey)
        NetworkEventLogger.log("CONNECT_MESH_BROADCAST: CAMPUS_KEY_ROTATED scope=$institutionScope newVersion=$newVersion")
        return Pair(newVersion, newKey)
    }

    /**
     * Creates and ECDSA-signs a new CollegeBroadcast. Requires ADMIN role authorization for institutionScope.
     */
    fun createAndSignBroadcast(
        adminConnectMeshId: Long,
        adminCredentialId: String,
        adminPrivateKey: PrivateKey,
        institutionScope: String,
        title: String,
        message: String,
        broadcastType: BroadcastType = BroadcastType.ANNOUNCEMENT,
        priority: BroadcastPriority = BroadcastPriority.NORMAL,
        validityDurationMs: Long = 7 * 24 * 60 * 60 * 1000L, // 7 days default
        keyVersion: Int = 1,
        currentTime: Long = System.currentTimeMillis()
    ): CollegeBroadcast? {

        if (!authorizationManager.canBroadcastCampus(institutionScope)) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: REJECTED_UNAUTHORIZED_ADMIN_BROADCAST scope=$institutionScope admin=0x${adminConnectMeshId.toString(16).uppercase()}")
            return null
        }

        val broadcastId = "BC-" + UUID.randomUUID().toString().take(12).uppercase()

        val dummyBroadcast = CollegeBroadcast(
            broadcastId = broadcastId,
            institutionScope = institutionScope,
            senderConnectMeshId = adminConnectMeshId,
            senderCredentialId = adminCredentialId,
            createdAt = currentTime,
            expiresAt = currentTime + validityDurationMs,
            priority = priority,
            broadcastType = broadcastType,
            title = title,
            message = message,
            keyVersion = keyVersion,
            signatureHex = ""
        )

        val signableBytes = dummyBroadcast.constructSignableBytes()

        val signatureHex = try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(adminPrivateKey)
            signature.update(signableBytes)
            val sigBytes = signature.sign()
            sigBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: ERROR_SIGNING_BROADCAST: ${e.message}")
            return null
        }

        val signedBroadcast = dummyBroadcast.copy(signatureHex = signatureHex)
        broadcasts[broadcastId] = signedBroadcast
        processedBroadcastIds.add(broadcastId)

        NetworkEventLogger.log("CONNECT_MESH_BROADCAST: BROADCAST_CREATED id=$broadcastId title='$title' scope=$institutionScope priority=$priority")
        return signedBroadcast
    }

    /**
     * Encrypts a broadcast payload using the campus versioned symmetric key and sequence counter.
     */
    fun encryptBroadcastPayload(
        institutionScope: String,
        keyVersion: Int,
        sequence: Long,
        plainTextBytes: ByteArray,
        headerAad: ByteArray
    ): Pair<ByteArray, ByteArray>? { // Returns Pair(ciphertext, macTag)
        val keysMap = campusKeys[institutionScope] ?: return null
        val key = keysMap[keyVersion] ?: return null

        val cipher = ChaCha20Poly1305()
        val nonceBytes = ByteArray(12).apply {
            val buf = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
            buf.putLong(sequence)
            buf.putInt(keyVersion)
        }

        cipher.init(true, ParametersWithIV(KeyParameter(key), nonceBytes))
        if (headerAad.isNotEmpty()) {
            cipher.processAADBytes(headerAad, 0, headerAad.size)
        }

        val fullCiphertext = ByteArray(cipher.getOutputSize(plainTextBytes.size))
        val len = cipher.processBytes(plainTextBytes, 0, plainTextBytes.size, fullCiphertext, 0)
        cipher.doFinal(fullCiphertext, len)

        val cipherOnly = fullCiphertext.copyOfRange(0, fullCiphertext.size - 16)
        val macTag = fullCiphertext.copyOfRange(fullCiphertext.size - 16, fullCiphertext.size)

        return Pair(cipherOnly, macTag)
    }

    /**
     * Decrypts a broadcast payload using the campus versioned symmetric key and sequence counter.
     */
    fun decryptBroadcastPayload(
        institutionScope: String,
        keyVersion: Int,
        sequence: Long,
        ciphertext: ByteArray,
        macTag: ByteArray,
        headerAad: ByteArray
    ): ByteArray? {
        val keysMap = campusKeys[institutionScope] ?: return null
        val key = keysMap[keyVersion] ?: return null

        return try {
            val cipher = ChaCha20Poly1305()
            val nonceBytes = ByteArray(12).apply {
                val buf = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
                buf.putLong(sequence)
                buf.putInt(keyVersion)
            }

            cipher.init(false, ParametersWithIV(KeyParameter(key), nonceBytes))
            if (headerAad.isNotEmpty()) {
                cipher.processAADBytes(headerAad, 0, headerAad.size)
            }

            val fullCiphertext = ciphertext + macTag
            val output = ByteArray(cipher.getOutputSize(fullCiphertext.size))
            val len = cipher.processBytes(fullCiphertext, 0, fullCiphertext.size, output, 0)
            cipher.doFinal(output, len)
            output
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: DECRYPT_FAILED: ${e.message}")
            null
        }
    }

    fun isDuplicateOrAdd(broadcastId: String): Boolean {
        return !processedBroadcastIds.add(broadcastId)
    }

    fun addVerifiedBroadcast(broadcast: CollegeBroadcast) {
        broadcasts[broadcast.broadcastId] = broadcast
        processedBroadcastIds.add(broadcast.broadcastId)
    }

    fun getBroadcast(broadcastId: String): CollegeBroadcast? = broadcasts[broadcastId]

    fun getAllVerifiedBroadcasts(): List<CollegeBroadcast> {
        val now = System.currentTimeMillis()
        return broadcasts.values.filter { it.expiresAt > now }.sortedByDescending { it.createdAt }
    }
}
