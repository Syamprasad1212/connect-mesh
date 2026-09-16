package com.connectmesh.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class RoleAuthorizationTest {

    private lateinit var issuerKeyPair: java.security.KeyPair
    private lateinit var subjectKeyPair: java.security.KeyPair
    private lateinit var revocationManager: RevocationManager

    private val issuerId: Long = 0x1122334455667788L
    private val subjectId: Long = 0x3A88664422001133L
    private val scope: String = "CAMPUS:MAIN_HALL"

    @Before
    fun setUp() {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        issuerKeyPair = keyGen.generateKeyPair()
        subjectKeyPair = keyGen.generateKeyPair()

        revocationManager = RevocationManager()
    }

    @Test
    fun test1_ValidUserCredential() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.USER,
            scope = scope
        )
        assertNotNull(cred)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred!!,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.AUTHORIZED, result)
    }

    @Test
    fun test2_ValidTeacherCredential() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope
        )
        assertNotNull(cred)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred!!,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.AUTHORIZED, result)
    }

    @Test
    fun test3_ValidAdminCredential() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.ADMIN,
            scope = scope
        )
        assertNotNull(cred)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred!!,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.AUTHORIZED, result)
    }

    @Test
    fun test4_ExpiredCredential() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope,
            validityDurationMs = -10000L // Already expired 10s ago
        )
        assertNotNull(cred)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred!!,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_EXPIRED, result)
    }

    @Test
    fun test5_InvalidSignature() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope
        )
        assertNotNull(cred)

        val corruptedSig = cred!!.signatureHex.dropLast(4) + "FFFF"
        val tamperedCred = cred.copy(signatureHex = corruptedSig)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = tamperedCred,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, result)
    }

    @Test
    fun test6_WrongSubjectId() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope
        )
        assertNotNull(cred)

        val differentSubjectId: Long = 0x1999999999999999L

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred!!,
            actualSubjectId = differentSubjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_IDENTITY_MISMATCH, result)
    }

    @Test
    fun test7_WrongPublicKeyFingerprint() {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val differentKeyPair = keyGen.generateKeyPair()

        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope
        )
        assertNotNull(cred)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred!!,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = differentKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_IDENTITY_KEY_MISMATCH, result)
    }

    @Test
    fun test8_WrongIssuer() {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val rogueIssuerKeyPair = keyGen.generateKeyPair()

        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope
        )
        assertNotNull(cred)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred!!,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = rogueIssuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, result)
    }

    @Test
    fun test9_WrongScope() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = "CLASSROOM:CSE-A"
        )
        assertNotNull(cred)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred!!,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded,
            requiredScope = "CLASSROOM:CSE-B"
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_SCOPE_MISMATCH, result)
    }

    @Test
    fun test10_RevokedCredential() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope
        )
        assertNotNull(cred)

        revocationManager.revokeCredential(cred!!.credentialId, "REVOKED_FOR_TESTING")

        val result = RoleCredentialVerifier.verifyCredential(
            credential = cred,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded,
            revocationManager = revocationManager
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_REVOKED, result)
    }

    @Test
    fun test11_TamperedRole() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope
        )
        assertNotNull(cred)

        val tamperedCred = cred!!.copy(role = UserRole.ADMIN)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = tamperedCred,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, result)
    }

    @Test
    fun test12_TamperedExpiration() {
        val cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = subjectId,
            subjectPublicKeyBytes = subjectKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = scope
        )
        assertNotNull(cred)

        val tamperedCred = cred!!.copy(expiresAt = cred.expiresAt + 1000000L)

        val result = RoleCredentialVerifier.verifyCredential(
            credential = tamperedCred,
            actualSubjectId = subjectId,
            actualSubjectPublicKeyBytes = subjectKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = issuerKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, result)
    }
}
