package com.connectmesh.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Revocation registry tracking revoked role credentials.
 * Provides the revocation foundation for institutional identity management.
 */
class RevocationManager {

    data class RevocationEntry(
        val credentialId: String,
        val revokedAt: Long,
        val reason: String
    )

    private val revokedCredentials = ConcurrentHashMap<String, RevocationEntry>()

    fun revokeCredential(credentialId: String, reason: String = "REVOKED_BY_ISSUER", revokedAt: Long = System.currentTimeMillis()) {
        revokedCredentials[credentialId] = RevocationEntry(credentialId, revokedAt, reason)
    }

    fun isRevoked(credentialId: String): Boolean {
        return revokedCredentials.containsKey(credentialId)
    }

    fun getRevocationEntry(credentialId: String): RevocationEntry? {
        return revokedCredentials[credentialId]
    }

    fun clear() {
        revokedCredentials.clear()
    }
}
