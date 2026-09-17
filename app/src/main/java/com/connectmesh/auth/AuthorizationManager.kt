package com.connectmesh.auth

import com.connectmesh.diagnostics.NetworkEventLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Local Authorization Manager.
 * Evaluates role credentials, trusted institutional issuers, and scope permissions.
 */
class AuthorizationManager(
    val revocationManager: RevocationManager = RevocationManager()
) {
    private var localCredential: RoleCredential? = null
    private var localPublicKeyBytes: ByteArray? = null
    private var localDeviceId: Long = 0L

    private val trustedIssuers = ConcurrentHashMap<Long, ByteArray>()

    fun initializeLocalIdentity(deviceId: Long, publicKeyBytes: ByteArray) {
        this.localDeviceId = deviceId
        this.localPublicKeyBytes = publicKeyBytes
    }

    fun registerTrustedIssuer(issuerId: Long, issuerPublicKeyBytes: ByteArray) {
        trustedIssuers[issuerId] = issuerPublicKeyBytes
        NetworkEventLogger.log("CONNECT_MESH_AUTH: TRUSTED_ISSUER_REGISTERED id=0x${issuerId.toString(16).uppercase()}")
    }

    fun getTrustedIssuerKey(issuerId: Long): ByteArray? = trustedIssuers[issuerId]

    fun decodePublicKey(input: String): ByteArray? = Companion.decodePublicKey(input)

    companion object {
        fun decodePublicKey(input: String): ByteArray? {
            val clean = input.trim()
            if (clean.isEmpty()) return null

            // Try Hex decoding first if it matches hex string format
            if (clean.matches(Regex("^[0-9a-fA-F]+$")) && clean.length % 2 == 0) {
                try {
                    val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val keySpec = java.security.spec.X509EncodedKeySpec(bytes)
                    java.security.KeyFactory.getInstance("EC").generatePublic(keySpec)
                    return bytes
                } catch (_: Exception) {}
            }

            // Try standard Base64 decoding
            try {
                val bytes = java.util.Base64.getDecoder().decode(clean)
                val keySpec = java.security.spec.X509EncodedKeySpec(bytes)
                java.security.KeyFactory.getInstance("EC").generatePublic(keySpec)
                return bytes
            } catch (_: Exception) {}

            // Try Base64 MIME decoder (handles line breaks / formatting)
            try {
                val bytes = java.util.Base64.getMimeDecoder().decode(clean)
                val keySpec = java.security.spec.X509EncodedKeySpec(bytes)
                java.security.KeyFactory.getInstance("EC").generatePublic(keySpec)
                return bytes
            } catch (_: Exception) {}

            return null
        }
    }

    fun setLocalCredential(credential: RoleCredential) {
        if (localPublicKeyBytes == null) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: ERROR - local identity not initialized before setting credential")
            return
        }

        val result = RoleCredentialVerifier.verifyCredential(
            credential = credential,
            actualSubjectId = localDeviceId,
            actualSubjectPublicKeyBytes = localPublicKeyBytes!!,
            trustedIssuerPublicKeyBytes = trustedIssuers[credential.issuerId],
            revocationManager = revocationManager
        )

        if (result == RoleCredentialVerifier.VerificationResult.AUTHORIZED) {
            this.localCredential = credential
            NetworkEventLogger.log("CONNECT_MESH_AUTH: LOCAL_CREDENTIAL_ACCEPTED role=${credential.role} scope=${credential.scope}")
        } else {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: LOCAL_CREDENTIAL_REJECTED result=$result")
        }
    }

    fun getLocalCredential(): RoleCredential? = localCredential

    fun getLocalRole(): UserRole {
        val cred = localCredential ?: return UserRole.USER
        val pk = localPublicKeyBytes ?: return UserRole.USER
        val res = RoleCredentialVerifier.verifyCredential(
            credential = cred,
            actualSubjectId = localDeviceId,
            actualSubjectPublicKeyBytes = pk,
            trustedIssuerPublicKeyBytes = trustedIssuers[cred.issuerId],
            revocationManager = revocationManager
        )
        return if (res == RoleCredentialVerifier.VerificationResult.AUTHORIZED) cred.role else UserRole.USER
    }

    fun hasRole(requiredRole: UserRole): Boolean {
        return getLocalRole().hasPrivilegeOf(requiredRole)
    }

    fun hasScope(requiredScope: String): Boolean {
        val cred = localCredential ?: return false
        if (getLocalRole() == UserRole.USER) return false
        return RoleCredentialVerifier.verifyCredential(
            credential = cred,
            actualSubjectId = localDeviceId,
            actualSubjectPublicKeyBytes = localPublicKeyBytes ?: return false,
            trustedIssuerPublicKeyBytes = trustedIssuers[cred.issuerId],
            requiredScope = requiredScope,
            revocationManager = revocationManager
        ) == RoleCredentialVerifier.VerificationResult.AUTHORIZED
    }

    fun canCreateClassroom(scope: String): Boolean {
        return hasRole(UserRole.TEACHER) && hasScope(scope)
    }

    fun canBroadcastCampus(scope: String): Boolean {
        return hasRole(UserRole.ADMIN) && hasScope(scope)
    }

    fun canSendEmergencyBroadcast(): Boolean {
        return hasRole(UserRole.EMERGENCY_AUTHORITY) || hasRole(UserRole.ADMIN)
    }

    fun verifyPeerRole(
        peerId: Long,
        peerPublicKeyBytes: ByteArray,
        credential: RoleCredential,
        requiredScope: String? = null
    ): RoleCredentialVerifier.VerificationResult {
        val trustedKey = trustedIssuers[credential.issuerId]
        return RoleCredentialVerifier.verifyCredential(
            credential = credential,
            actualSubjectId = peerId,
            actualSubjectPublicKeyBytes = peerPublicKeyBytes,
            trustedIssuerPublicKeyBytes = trustedKey,
            requiredScope = requiredScope,
            revocationManager = revocationManager
        )
    }
}
