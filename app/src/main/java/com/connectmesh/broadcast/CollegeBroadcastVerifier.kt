package com.connectmesh.broadcast

import com.connectmesh.auth.RevocationManager
import com.connectmesh.diagnostics.NetworkEventLogger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object CollegeBroadcastVerifier {

    enum class VerificationResult {
        AUTHORIZED,
        REJECTED_EXPIRED,
        REJECTED_INVALID_SIGNATURE,
        REJECTED_IDENTITY_MISMATCH,
        REJECTED_UNTRUSTED_ISSUER,
        REJECTED_SCOPE_MISMATCH,
        REJECTED_REVOKED,
        REJECTED_MALFORMED
    }

    /**
     * Rigorously verifies a CollegeBroadcast against admin identity, issuer trust, ECDSA signature, scope, time, and revocation.
     */
    fun verifyBroadcast(
        broadcast: CollegeBroadcast,
        actualSenderId: Long,
        trustedAdminPublicKeyBytes: ByteArray?,
        requiredScope: String? = null,
        revocationManager: RevocationManager? = null,
        currentTime: Long = System.currentTimeMillis()
    ): VerificationResult {

        // 1. Revocation check
        if (revocationManager != null && revocationManager.isRevoked(broadcast.senderCredentialId)) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: REJECTED reason=REVOKED_ADMIN_CREDENTIAL credId=${broadcast.senderCredentialId}")
            return VerificationResult.REJECTED_REVOKED
        }

        // 2. Sender Connect-Mesh ID binding check
        if (broadcast.senderConnectMeshId != actualSenderId) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: REJECTED reason=SENDER_ID_MISMATCH expected=0x${broadcast.senderConnectMeshId.toString(16).uppercase()} actual=0x${actualSenderId.toString(16).uppercase()}")
            return VerificationResult.REJECTED_IDENTITY_MISMATCH
        }

        // 3. Expiration check
        if (currentTime > broadcast.expiresAt) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: REJECTED reason=EXPIRED expiresAt=${broadcast.expiresAt} now=$currentTime")
            return VerificationResult.REJECTED_EXPIRED
        }

        // 4. Scope check
        if (requiredScope != null && requiredScope.isNotBlank()) {
            if (!isScopeCompatible(broadcast.institutionScope, requiredScope)) {
                NetworkEventLogger.log("CONNECT_MESH_BROADCAST: REJECTED reason=SCOPE_MISMATCH broadcastScope=${broadcast.institutionScope} requiredScope=$requiredScope")
                return VerificationResult.REJECTED_SCOPE_MISMATCH
            }
        }

        // 5. Trusted Admin public key check
        if (trustedAdminPublicKeyBytes == null || trustedAdminPublicKeyBytes.isEmpty()) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: REJECTED reason=UNTRUSTED_ADMIN_KEY senderId=0x${broadcast.senderConnectMeshId.toString(16).uppercase()}")
            return VerificationResult.REJECTED_UNTRUSTED_ISSUER
        }

        // 6. Cryptographic ECDSA Signature verification
        val isSignatureValid = verifyEcdsaSignature(
            signableBytes = broadcast.constructSignableBytes(),
            signatureHex = broadcast.signatureHex,
            publicKeyBytes = trustedAdminPublicKeyBytes
        )

        if (!isSignatureValid) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: REJECTED reason=INVALID_SIGNATURE id=${broadcast.broadcastId}")
            return VerificationResult.REJECTED_INVALID_SIGNATURE
        }

        NetworkEventLogger.log("CONNECT_MESH_BROADCAST: BROADCAST_VERIFIED title='${broadcast.title}' sender=0x${broadcast.senderConnectMeshId.toString(16).uppercase()} scope=${broadcast.institutionScope}")
        return VerificationResult.AUTHORIZED
    }

    fun isScopeCompatible(broadcastScope: String, requiredScope: String): Boolean {
        if (broadcastScope == "*" || broadcastScope.equals(requiredScope, ignoreCase = true)) return true
        if (broadcastScope.endsWith(":*")) {
            val prefix = broadcastScope.substringBefore(":*")
            return requiredScope.startsWith(prefix, ignoreCase = true)
        }
        return isCampusScopeMatching(broadcastScope, requiredScope)
    }

    fun isCampusScopeMatching(broadcastScope: String, enrolledScope: String?): Boolean {
        if (enrolledScope.isNullOrBlank()) return false
        val bScope = broadcastScope.trim()
        val eScope = enrolledScope.trim()
        if (bScope.equals(eScope, ignoreCase = true) || bScope == "*" || eScope == "*") return true

        val bClean = if (bScope.contains(":")) bScope.substringAfter(":") else bScope
        val eClean = if (eScope.contains(":")) eScope.substringAfter(":") else eScope
        return bClean.equals(eClean, ignoreCase = true)
    }

    private fun verifyEcdsaSignature(signableBytes: ByteArray, signatureHex: String, publicKeyBytes: ByteArray): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val publicKey = keyFactory.generatePublic(keySpec)

            val signatureBytes = signatureHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(signableBytes)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_BROADCAST: VERIFY_SIGNATURE_EXCEPTION: ${e.message}")
            false
        }
    }
}
