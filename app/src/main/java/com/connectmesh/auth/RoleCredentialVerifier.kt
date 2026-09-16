package com.connectmesh.auth

import com.connectmesh.diagnostics.NetworkEventLogger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object RoleCredentialVerifier {

    enum class VerificationResult {
        AUTHORIZED,
        REJECTED_EXPIRED,
        REJECTED_INVALID_SIGNATURE,
        REJECTED_IDENTITY_MISMATCH,
        REJECTED_IDENTITY_KEY_MISMATCH,
        REJECTED_UNTRUSTED_ISSUER,
        REJECTED_SCOPE_MISMATCH,
        REJECTED_REVOKED,
        REJECTED_MALFORMED
    }

    private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L // 5 minutes clock skew tolerance

    /**
     * Calculates deterministic Hex SHA-256 fingerprint of an EC P-256 public key.
     */
    fun calculatePublicKeyFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyBytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Rigorously verifies a RoleCredential against subject identity, issuer trust, signature, scope, time, and revocation.
     */
    fun verifyCredential(
        credential: RoleCredential,
        actualSubjectId: Long,
        actualSubjectPublicKeyBytes: ByteArray,
        trustedIssuerPublicKeyBytes: ByteArray?,
        requiredScope: String? = null,
        revocationManager: RevocationManager? = null,
        currentTime: Long = System.currentTimeMillis()
    ): VerificationResult {

        // 1. Revocation check
        if (revocationManager != null && revocationManager.isRevoked(credential.credentialId)) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_REJECTED reason=REVOKED id=${credential.credentialId}")
            return VerificationResult.REJECTED_REVOKED
        }

        // 2. Subject Connect-Mesh ID binding check
        if (credential.subjectConnectMeshId != actualSubjectId) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_REJECTED reason=IDENTITY_MISMATCH expected=0x${credential.subjectConnectMeshId.toString(16).uppercase()} actual=0x${actualSubjectId.toString(16).uppercase()}")
            return VerificationResult.REJECTED_IDENTITY_MISMATCH
        }

        // 3. Subject Public Key Fingerprint binding check
        val actualFingerprint = calculatePublicKeyFingerprint(actualSubjectPublicKeyBytes)
        if (!credential.subjectPublicKeyFingerprint.equals(actualFingerprint, ignoreCase = true)) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_REJECTED reason=KEY_FINGERPRINT_MISMATCH")
            return VerificationResult.REJECTED_IDENTITY_KEY_MISMATCH
        }

        // 4. Expiration check
        if (currentTime > credential.expiresAt) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_REJECTED reason=EXPIRED expiresAt=${credential.expiresAt} now=$currentTime")
            return VerificationResult.REJECTED_EXPIRED
        }

        // 5. Future issuing check (clock skew)
        if (credential.issuedAt > currentTime + MAX_CLOCK_SKEW_MS) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_REJECTED reason=FUTURE_ISSUED issuedAt=${credential.issuedAt} now=$currentTime")
            return VerificationResult.REJECTED_MALFORMED
        }

        // 6. Scope check
        if (requiredScope != null && requiredScope.isNotBlank()) {
            if (!isScopeCompatible(credential.scope, requiredScope)) {
                NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_REJECTED reason=SCOPE_MISMATCH credentialScope=${credential.scope} requiredScope=$requiredScope")
                return VerificationResult.REJECTED_SCOPE_MISMATCH
            }
        }

        // 7. Issuer public key trust check
        if (trustedIssuerPublicKeyBytes == null || trustedIssuerPublicKeyBytes.isEmpty()) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_REJECTED reason=UNTRUSTED_ISSUER issuerId=0x${credential.issuerId.toString(16).uppercase()}")
            return VerificationResult.REJECTED_UNTRUSTED_ISSUER
        }

        // 8. Cryptographic Signature verification (SHA256withECDSA)
        val isSignatureValid = verifyEcdsaSignature(
            signableBytes = credential.constructSignableBytes(),
            signatureHex = credential.signatureHex,
            publicKeyBytes = trustedIssuerPublicKeyBytes
        )

        if (!isSignatureValid) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_REJECTED reason=INVALID_SIGNATURE id=${credential.credentialId}")
            return VerificationResult.REJECTED_INVALID_SIGNATURE
        }

        NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_VERIFIED role=${credential.role} subject=0x${credential.subjectConnectMeshId.toString(16).uppercase()} scope=${credential.scope}")
        return VerificationResult.AUTHORIZED
    }

    private fun isScopeCompatible(credentialScope: String, requiredScope: String): Boolean {
        if (credentialScope == "*" || credentialScope.equals(requiredScope, ignoreCase = true)) return true
        if (credentialScope.endsWith(":*")) {
            val prefix = credentialScope.substringBefore(":*")
            return requiredScope.startsWith(prefix, ignoreCase = true)
        }
        return false
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
            NetworkEventLogger.log("CONNECT_MESH_AUTH: VERIFY_SIGNATURE_EXCEPTION: ${e.message}")
            false
        }
    }
}
