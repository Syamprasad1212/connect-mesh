package com.connectmesh.auth

import com.connectmesh.diagnostics.NetworkEventLogger
import java.security.PrivateKey
import java.security.Signature
import java.util.UUID

object RoleCredentialIssuer {

    /**
     * Issues a cryptographically signed RoleCredential using an EC P-256 Private Key.
     * Operates without embedding master keys inside the application code.
     */
    fun issueCredential(
        issuerId: Long,
        issuerPrivateKey: PrivateKey,
        subjectConnectMeshId: Long,
        subjectPublicKeyBytes: ByteArray,
        role: UserRole,
        scope: String,
        validityDurationMs: Long = 365 * 24 * 60 * 60 * 1000L, // Default 1 year validity
        currentTime: Long = System.currentTimeMillis()
    ): RoleCredential? {
        val credentialId = "CRED-" + UUID.randomUUID().toString().take(18).uppercase()
        val subjectFingerprint = RoleCredentialVerifier.calculatePublicKeyFingerprint(subjectPublicKeyBytes)

        val dummyCredential = RoleCredential(
            credentialId = credentialId,
            subjectConnectMeshId = subjectConnectMeshId,
            subjectPublicKeyFingerprint = subjectFingerprint,
            role = role,
            issuerId = issuerId,
            issuedAt = currentTime,
            expiresAt = currentTime + validityDurationMs,
            scope = scope,
            signatureHex = ""
        )

        val signableBytes = dummyCredential.constructSignableBytes()

        val signatureHex = try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(issuerPrivateKey)
            signature.update(signableBytes)
            val sigBytes = signature.sign()
            sigBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: ISSUE_CREDENTIAL_ERROR: ${e.message}")
            return null
        }

        NetworkEventLogger.log("CONNECT_MESH_AUTH: CREDENTIAL_ISSUED id=$credentialId role=$role subject=0x${subjectConnectMeshId.toString(16).uppercase()} scope=$scope")
        return dummyCredential.copy(signatureHex = signatureHex)
    }
}
