package com.connectmesh.classroom

import com.connectmesh.auth.AuthorizationManager
import com.connectmesh.auth.RoleCredentialVerifier
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
 * Core Classroom & Institutional Group Manager.
 * Handles group creation, cryptographic membership authorization, key rotation, and ChaCha20-Poly1305 group encryption.
 */
class ClassroomManager(
    val authorizationManager: AuthorizationManager
) {
    private val classrooms = ConcurrentHashMap<String, ClassroomGroup>()
    private val memberships = ConcurrentHashMap<String, ConcurrentHashMap<Long, ClassroomMembershipCredential>>()
    private val groupKeys = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>() // groupId -> (version -> 32B key)
    private val classroomMessages = ConcurrentHashMap<String, MutableList<ClassroomMessage>>()

    /**
     * Creates a new Classroom Group. Requires TEACHER or ADMIN authorization for institutionScope.
     */
    fun createClassroom(
        name: String,
        institutionScope: String,
        teacherConnectMeshId: Long,
        teacherPublicKeyBytes: ByteArray
    ): ClassroomGroup? {
        if (!authorizationManager.canCreateClassroom(institutionScope)) {
            NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: REJECTED_UNAUTHORIZED_CREATION scope=$institutionScope creator=0x${teacherConnectMeshId.toString(16).uppercase()}")
            return null
        }

        val groupId = "GRP-" + name.uppercase().replace(" ", "_") + "-" + UUID.randomUUID().toString().take(6).uppercase()
        val initialKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val keyHex = initialKey.joinToString("") { "%02x".format(it) }

        val group = ClassroomGroup(
            groupId = groupId,
            groupName = name,
            institutionScope = institutionScope,
            createdByConnectMeshId = teacherConnectMeshId,
            createdAt = System.currentTimeMillis(),
            groupKeyVersion = 1,
            activeGroupKeyHex = keyHex
        )

        classrooms[groupId] = group

        val keysMap = ConcurrentHashMap<Int, ByteArray>()
        keysMap[1] = initialKey
        groupKeys[groupId] = keysMap

        // Self-add creator as Teacher member
        val teacherFingerprint = RoleCredentialVerifier.calculatePublicKeyFingerprint(teacherPublicKeyBytes)
        val selfCred = ClassroomMembershipCredential(
            membershipId = "MEM-OWNER-" + UUID.randomUUID().toString().take(8).uppercase(),
            groupId = groupId,
            memberConnectMeshId = teacherConnectMeshId,
            memberPublicKeyFingerprint = teacherFingerprint,
            memberRole = UserRole.TEACHER,
            issuedByConnectMeshId = teacherConnectMeshId,
            issuedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L,
            signatureHex = "SELF_SIGNED_OWNER"
        )

        val groupMembers = ConcurrentHashMap<Long, ClassroomMembershipCredential>()
        groupMembers[teacherConnectMeshId] = selfCred
        memberships[groupId] = groupMembers

        NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: CLASSROOM_CREATED id=$groupId name=$name scope=$institutionScope owner=0x${teacherConnectMeshId.toString(16).uppercase()}")
        return group
    }

    /**
     * Authorizes and adds a new member to a Classroom Group.
     */
    fun addMember(
        groupId: String,
        memberConnectMeshId: Long,
        memberPublicKeyBytes: ByteArray,
        memberRole: UserRole,
        issuerPrivateKey: PrivateKey,
        issuerConnectMeshId: Long
    ): ClassroomMembershipCredential? {
        val group = classrooms[groupId] ?: return null

        if (!authorizationManager.hasRole(UserRole.TEACHER) || !authorizationManager.hasScope(group.institutionScope)) {
            NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: REJECTED_ADD_MEMBER_UNAUTHORIZED issuer=0x${issuerConnectMeshId.toString(16).uppercase()}")
            return null
        }

        val membershipId = "MEM-" + UUID.randomUUID().toString().take(12).uppercase()
        val fingerprint = RoleCredentialVerifier.calculatePublicKeyFingerprint(memberPublicKeyBytes)

        val dummyCred = ClassroomMembershipCredential(
            membershipId = membershipId,
            groupId = groupId,
            memberConnectMeshId = memberConnectMeshId,
            memberPublicKeyFingerprint = fingerprint,
            memberRole = memberRole,
            issuedByConnectMeshId = issuerConnectMeshId,
            issuedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L,
            signatureHex = ""
        )

        val signableBytes = dummyCred.constructSignableBytes()
        val signatureHex = try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(issuerPrivateKey)
            signature.update(signableBytes)
            val sigBytes = signature.sign()
            sigBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return null
        }

        val signedCred = dummyCred.copy(signatureHex = signatureHex)
        val groupMembers = memberships.computeIfAbsent(groupId) { ConcurrentHashMap() }
        groupMembers[memberConnectMeshId] = signedCred

        NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: MEMBER_ADDED groupId=$groupId member=0x${memberConnectMeshId.toString(16).uppercase()} role=$memberRole")
        return signedCred
    }

    /**
     * Removes a member and triggers Group Key Rotation to version N+1.
     */
    fun removeMember(groupId: String, memberConnectMeshId: Long): Boolean {
        val group = classrooms[groupId] ?: return false
        val groupMembers = memberships[groupId] ?: return false

        if (!groupMembers.containsKey(memberConnectMeshId)) return false
        groupMembers.remove(memberConnectMeshId)

        // Rotate Group Key to version N+1
        val newVersion = group.groupKeyVersion + 1
        val newKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val newKeyHex = newKey.joinToString("") { "%02x".format(it) }

        group.groupKeyVersion = newVersion
        group.activeGroupKeyHex = newKeyHex

        val keysMap = groupKeys.computeIfAbsent(groupId) { ConcurrentHashMap() }
        keysMap[newVersion] = newKey

        NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: MEMBER_REMOVED_AND_KEY_ROTATED groupId=$groupId member=0x${memberConnectMeshId.toString(16).uppercase()} newVersion=$newVersion")
        return true
    }

    fun isMember(groupId: String, memberConnectMeshId: Long): Boolean {
        val groupMembers = memberships[groupId] ?: return false
        val cred = groupMembers[memberConnectMeshId] ?: return false
        return System.currentTimeMillis() <= cred.expiresAt
    }

    /**
     * Encrypts a classroom text payload using the group's active versioned symmetric key and sequence counter.
     */
    fun encryptGroupPayload(
        groupId: String,
        sequence: Long,
        plainTextBytes: ByteArray,
        headerAad: ByteArray
    ): Pair<Int, Pair<ByteArray, ByteArray>>? { // Returns Pair(keyVersion, Pair(ciphertext, macTag))
        val group = classrooms[groupId] ?: return null
        val version = group.groupKeyVersion
        val keysMap = groupKeys[groupId] ?: return null
        val key = keysMap[version] ?: return null

        val cipher = ChaCha20Poly1305()
        val nonceBytes = ByteArray(12).apply {
            val buf = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
            buf.putLong(sequence)
            buf.putInt(version)
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

        return Pair(version, Pair(cipherOnly, macTag))
    }

    /**
     * Decrypts a classroom group payload using the designated groupKeyVersion symmetric key and sequence counter.
     */
    fun decryptGroupPayload(
        groupId: String,
        keyVersion: Int,
        sequence: Long,
        memberConnectMeshId: Long,
        ciphertext: ByteArray,
        macTag: ByteArray,
        headerAad: ByteArray
    ): ByteArray? {
        if (!isMember(groupId, memberConnectMeshId)) {
            NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: DECRYPT_REJECTED reason=NON_MEMBER member=0x${memberConnectMeshId.toString(16).uppercase()}")
            return null
        }

        val keysMap = groupKeys[groupId] ?: return null
        val key = keysMap[keyVersion]
        if (key == null) {
            NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: DECRYPT_REJECTED reason=MISSING_KEY_VERSION version=$keyVersion")
            return null
        }

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
            NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: DECRYPT_FAILED: ${e.message}")
            null
        }
    }

    fun getClassroom(groupId: String): ClassroomGroup? = classrooms[groupId]

    fun getAllClassrooms(): List<ClassroomGroup> = classrooms.values.toList()

    fun registerClassroom(group: ClassroomGroup) {
        classrooms[group.groupId] = group
        val keyBytes = try {
            group.activeGroupKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            ByteArray(32)
        }
        val keysMap = groupKeys.computeIfAbsent(group.groupId) { ConcurrentHashMap() }
        keysMap[group.groupKeyVersion] = keyBytes
    }

    fun findClassroomByCode(code: String): ClassroomGroup? {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.isBlank()) return null
        return classrooms.values.find { group ->
            group.groupId.uppercase() == cleanCode ||
            group.groupId.uppercase() == "GRP-$cleanCode" ||
            group.groupId.uppercase().removePrefix("GRP-") == cleanCode
        }
    }

    fun joinClassroomByCode(
        code: String,
        studentConnectMeshId: Long,
        studentPublicKeyBytes: ByteArray = ByteArray(32)
    ): ClassroomGroup? {
        val group = findClassroomByCode(code) ?: return null

        val fingerprint = RoleCredentialVerifier.calculatePublicKeyFingerprint(studentPublicKeyBytes)
        val studentCred = ClassroomMembershipCredential(
            membershipId = "MEM-STUDENT-" + UUID.randomUUID().toString().take(8).uppercase(),
            groupId = group.groupId,
            memberConnectMeshId = studentConnectMeshId,
            memberPublicKeyFingerprint = fingerprint,
            memberRole = UserRole.USER,
            issuedByConnectMeshId = group.createdByConnectMeshId,
            issuedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L,
            signatureHex = "JOIN_CODE_ENROLLED"
        )
        val groupMembers = memberships.computeIfAbsent(group.groupId) { ConcurrentHashMap() }
        groupMembers[studentConnectMeshId] = studentCred
        NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: STUDENT_JOINED groupId=${group.groupId} student=0x${studentConnectMeshId.toString(16).uppercase()}")
        return group
    }

    fun addMessage(msg: ClassroomMessage) {
        val list = classroomMessages.computeIfAbsent(msg.groupId) { mutableListOf() }
        list.add(msg)
    }

    fun getMessages(groupId: String): List<ClassroomMessage> {
        return classroomMessages[groupId] ?: emptyList()
    }
}
