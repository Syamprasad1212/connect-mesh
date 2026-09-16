package com.connectmesh.crypto

import com.connectmesh.diagnostics.NetworkEventLogger
import com.connectmesh.identity.CryptoIdentityManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object PeerAuthenticator {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "connect_mesh_identity_key"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    fun generateChallenge(): ByteArray {
        val challenge = ByteArray(32)
        SecureRandom().nextBytes(challenge)
        return challenge
    }

    fun signChallenge(challenge: ByteArray, localId: Long, remoteId: Long): ByteArray? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
            val privateKey = entry.privateKey

            val dataToSign = ByteBuffer.allocate(32 + 8 + 8).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(challenge)
                putLong(localId)
                putLong(remoteId)
            }.array()

            val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
            signer.initSign(privateKey)
            signer.update(dataToSign)
            signer.sign()
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: ERROR signing challenge: ${e.message}")
            null
        }
    }

    fun verifyPeerAuthentication(
        claimedPeerId: Long,
        remotePublicKeyBytes: ByteArray,
        challenge: ByteArray,
        localId: Long,
        signatureBytes: ByteArray
    ): Boolean {
        try {
            // Step 1: Verify derived ID match
            val derivedId = CryptoIdentityManager.getInstance().deriveConnectMeshId(remotePublicKeyBytes)
            if (derivedId != claimedPeerId) {
                NetworkEventLogger.log("CONNECT_MESH_AUTH: REJECTED reason=DERIVED_ID_MISMATCH claimed=0x${claimedPeerId.toString(16).uppercase()} derived=0x${derivedId.toString(16).uppercase()}")
                return false
            }

            // Step 2: Reconstruct EC Public Key
            val keyFactory = KeyFactory.getInstance("EC")
            val pubKeySpec = X509EncodedKeySpec(remotePublicKeyBytes)
            val remotePubKey = keyFactory.generatePublic(pubKeySpec)

            // Step 3: Verify SHA256withECDSA Signature
            val dataToSign = ByteBuffer.allocate(32 + 8 + 8).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(challenge)
                putLong(claimedPeerId)
                putLong(localId)
            }.array()

            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(remotePubKey)
            verifier.update(dataToSign)
            val isValid = verifier.verify(signatureBytes)

            if (isValid) {
                NetworkEventLogger.log("CONNECT_MESH_AUTH: VERIFICATION_SUCCESS peerId=0x${claimedPeerId.toString(16).uppercase()}")
            } else {
                NetworkEventLogger.log("CONNECT_MESH_AUTH: REJECTED reason=INVALID_SIGNATURE peerId=0x${claimedPeerId.toString(16).uppercase()}")
            }
            return isValid
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: REJECTED reason=CRYPTO_EXCEPTION peerId=0x${claimedPeerId.toString(16).uppercase()} err=${e.message}")
            return false
        }
    }

    fun encodeAuthResponsePayload(publicKeyBytes: ByteArray, signatureBytes: ByteArray): ByteArray {
        val totalSize = 2 + publicKeyBytes.size + 2 + signatureBytes.size
        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(publicKeyBytes.size.toShort())
            put(publicKeyBytes)
            putShort(signatureBytes.size.toShort())
            put(signatureBytes)
        }.array()
    }

    data class DecodedAuthResponse(
        val publicKeyBytes: ByteArray,
        val signatureBytes: ByteArray
    )

    fun decodeAuthResponsePayload(payload: ByteArray): DecodedAuthResponse? {
        return try {
            val buf = ByteBuffer.wrap(payload).apply { order(ByteOrder.BIG_ENDIAN) }
            val pubLen = buf.short.toInt() and 0xFFFF
            val pubBytes = ByteArray(pubLen)
            buf.get(pubBytes)

            val sigLen = buf.short.toInt() and 0xFFFF
            val sigBytes = ByteArray(sigLen)
            buf.get(sigBytes)

            DecodedAuthResponse(pubBytes, sigBytes)
        } catch (e: Exception) {
            null
        }
    }
}
