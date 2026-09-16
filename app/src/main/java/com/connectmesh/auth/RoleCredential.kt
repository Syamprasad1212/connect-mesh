package com.connectmesh.auth

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cryptographically signed role authorization credential.
 * Cryptographically binds a subject's persistent 64-bit Connect-Mesh ID and EC P-256 public key fingerprint
 * to an institutional role (USER, TEACHER, ADMIN, EMERGENCY_AUTHORITY) and authorization scope.
 */
data class RoleCredential(
    val credentialId: String,
    val subjectConnectMeshId: Long,
    val subjectPublicKeyFingerprint: String,
    val role: UserRole,
    val issuerId: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val scope: String,
    val signatureHex: String
) {
    /**
     * Constructs a canonical, deterministic binary representation of the credential fields for ECDSA signing and verification.
     * Prevents ambiguity, platform-specific serialization differences, and role/expiration tampering.
     */
    fun constructSignableBytes(): ByteArray {
        val cidBytes = credentialId.toByteArray(Charsets.UTF_8)
        val fpBytes = subjectPublicKeyFingerprint.toByteArray(Charsets.UTF_8)
        val scopeBytes = scope.toByteArray(Charsets.UTF_8)

        val totalSize = 2 + cidBytes.size + 8 + 2 + fpBytes.size + 1 + 8 + 8 + 8 + 2 + scopeBytes.size

        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(cidBytes.size.toShort())
            put(cidBytes)
            putLong(subjectConnectMeshId)
            putShort(fpBytes.size.toShort())
            put(fpBytes)
            put(role.ordinal.toByte())
            putLong(issuerId)
            putLong(issuedAt)
            putLong(expiresAt)
            putShort(scopeBytes.size.toShort())
            put(scopeBytes)
        }.array()
    }
}
