package com.connectmesh.classroom

import com.connectmesh.auth.UserRole
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cryptographically signed membership credential for a Classroom Group.
 * Issued by an authorized Teacher/Admin to a student or co-teacher.
 */
data class ClassroomMembershipCredential(
    val membershipId: String,
    val groupId: String,
    val memberConnectMeshId: Long,
    val memberPublicKeyFingerprint: String,
    val memberRole: UserRole,
    val issuedByConnectMeshId: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val signatureHex: String
) {
    /**
     * Constructs deterministic binary representation for ECDSA signing and verification.
     */
    fun constructSignableBytes(): ByteArray {
        val memBytes = membershipId.toByteArray(Charsets.UTF_8)
        val grpBytes = groupId.toByteArray(Charsets.UTF_8)
        val fpBytes = memberPublicKeyFingerprint.toByteArray(Charsets.UTF_8)

        val totalSize = 2 + memBytes.size + 2 + grpBytes.size + 8 + 2 + fpBytes.size + 1 + 8 + 8 + 8

        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(memBytes.size.toShort())
            put(memBytes)
            putShort(grpBytes.size.toShort())
            put(grpBytes)
            putLong(memberConnectMeshId)
            putShort(fpBytes.size.toShort())
            put(fpBytes)
            put(memberRole.ordinal.toByte())
            putLong(issuedByConnectMeshId)
            putLong(issuedAt)
            putLong(expiresAt)
        }.array()
    }
}
