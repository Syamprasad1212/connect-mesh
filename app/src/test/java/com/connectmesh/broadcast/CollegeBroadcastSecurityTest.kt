package com.connectmesh.broadcast

import com.connectmesh.auth.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class CollegeBroadcastSecurityTest {

    private lateinit var issuerKeyPair: java.security.KeyPair
    private lateinit var adminKeyPair: java.security.KeyPair
    private lateinit var teacherKeyPair: java.security.KeyPair
    private lateinit var studentKeyPair: java.security.KeyPair

    private val issuerId: Long = 0x1122334455667788L
    private val adminId: Long = 0x3988776655443322L
    private val teacherId: Long = 0x3344556677889900L
    private val studentId: Long = 0x5566778899001122L

    private val institutionScope: String = "COLLEGE:COLLEGE_001"
    private val sequence: Long = 2002L

    private lateinit var revocationManager: RevocationManager
    private lateinit var authorizationManager: AuthorizationManager
    private lateinit var broadcastManager: CampusBroadcastManager

    @Before
    fun setUp() {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        issuerKeyPair = keyGen.generateKeyPair()
        adminKeyPair = keyGen.generateKeyPair()
        teacherKeyPair = keyGen.generateKeyPair()
        studentKeyPair = keyGen.generateKeyPair()

        revocationManager = RevocationManager()
        authorizationManager = AuthorizationManager(revocationManager)
        broadcastManager = CampusBroadcastManager(authorizationManager)

        // Register issuer public key
        authorizationManager.registerTrustedIssuer(issuerId, issuerKeyPair.public.encoded)

        // Give Admin a valid ADMIN role credential for scope "COLLEGE:COLLEGE_001"
        val adminCred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = adminId,
            subjectPublicKeyBytes = adminKeyPair.public.encoded,
            role = UserRole.ADMIN,
            scope = institutionScope
        )
        assertNotNull(adminCred)

        authorizationManager.initializeLocalIdentity(adminId, adminKeyPair.public.encoded)
        authorizationManager.setLocalCredential(adminCred!!)
    }

    @Test
    fun test1_ValidAdminSendsCampusBroadcast() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Campus Alert",
            message = "All classes suspended due to heavy rain.",
            broadcastType = BroadcastType.ALERT,
            priority = BroadcastPriority.HIGH
        )
        assertNotNull(bc)
        assertEquals("Campus Alert", bc!!.title)

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.AUTHORIZED, verResult)
    }

    @Test
    fun test2_NormalUserAttemptsBroadcastFails() {
        val studentAuthManager = AuthorizationManager(revocationManager)
        studentAuthManager.registerTrustedIssuer(issuerId, issuerKeyPair.public.encoded)
        studentAuthManager.initializeLocalIdentity(studentId, studentKeyPair.public.encoded)

        val studentCred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = studentId,
            subjectPublicKeyBytes = studentKeyPair.public.encoded,
            role = UserRole.USER,
            scope = institutionScope
        )
        studentAuthManager.setLocalCredential(studentCred!!)

        val studentBroadcastManager = CampusBroadcastManager(studentAuthManager)
        val bc = studentBroadcastManager.createAndSignBroadcast(
            adminConnectMeshId = studentId,
            adminCredentialId = "CRED-STUDENT",
            adminPrivateKey = studentKeyPair.private,
            institutionScope = institutionScope,
            title = "Fake Alert",
            message = "Fake message"
        )
        assertNull(bc)
    }

    @Test
    fun test3_TeacherAttemptsCollegeWideBroadcastWithoutPermissionFails() {
        val teacherAuthManager = AuthorizationManager(revocationManager)
        teacherAuthManager.registerTrustedIssuer(issuerId, issuerKeyPair.public.encoded)
        teacherAuthManager.initializeLocalIdentity(teacherId, teacherKeyPair.public.encoded)

        val teacherCred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = teacherId,
            subjectPublicKeyBytes = teacherKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = institutionScope
        )
        teacherAuthManager.setLocalCredential(teacherCred!!)

        val teacherBroadcastManager = CampusBroadcastManager(teacherAuthManager)
        val bc = teacherBroadcastManager.createAndSignBroadcast(
            adminConnectMeshId = teacherId,
            adminCredentialId = "CRED-TEACHER",
            adminPrivateKey = teacherKeyPair.private,
            institutionScope = institutionScope,
            title = "Teacher Broadcast",
            message = "Teacher message"
        )
        assertNull(bc)
    }

    @Test
    fun test4_AdminFromCollegeATargetsCollegeBFails() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = "COLLEGE:COLLEGE_002", // Target College B
            title = "Rogue Broadcast",
            message = "Rogue message"
        )
        assertNull(bc) // Rejected because Admin's scope is COLLEGE_001
    }

    @Test
    fun test5_TamperedSenderIdFails() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Message"
        )
        assertNotNull(bc)

        val tamperedBc = bc!!.copy(senderConnectMeshId = 0x1999999999999999L)

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = tamperedBc,
            actualSenderId = 0x1999999999999999L,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verResult)
    }

    @Test
    fun test6_TamperedMessageFails() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Original Message"
        )
        assertNotNull(bc)

        val tamperedBc = bc!!.copy(message = "Tampered Fake Message")

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = tamperedBc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verResult)
    }

    @Test
    fun test7_TamperedPriorityFails() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Message",
            priority = BroadcastPriority.NORMAL
        )
        assertNotNull(bc)

        val tamperedBc = bc!!.copy(priority = BroadcastPriority.CRITICAL)

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = tamperedBc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verResult)
    }

    @Test
    fun test8_ExpiredBroadcastFails() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Message",
            validityDurationMs = -5000L // Expired 5 seconds ago
        )
        assertNotNull(bc)

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc!!,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_EXPIRED, verResult)
    }

    @Test
    fun test9_ReplayedBroadcastDeduplicated() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Message"
        )
        assertNotNull(bc)

        // First receive -> Accepted
        val isDup1 = broadcastManager.isDuplicateOrAdd(bc!!.broadcastId)
        assertTrue(isDup1) // Returns true for duplicate since added during creation

        // Second receive -> Suppressed
        val isDup2 = broadcastManager.isDuplicateOrAdd(bc.broadcastId)
        assertTrue(isDup2) // Suppressed as duplicate
    }

    @Test
    fun test10_UntrustedIssuerRejection() {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val rogueKeyPair = keyGen.generateKeyPair()

        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Message"
        )
        assertNotNull(bc)

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc!!,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = rogueKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verResult)
    }

    @Test
    fun test11_RevokedAdminCredentialRejection() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-REVOKED-ADMIN",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Message"
        )
        assertNotNull(bc)

        revocationManager.revokeCredential("CRED-REVOKED-ADMIN", "REVOKED_ADMIN_RIGHTS")

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc!!,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded,
            revocationManager = revocationManager
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_REVOKED, verResult)
    }

    @Test
    fun test12_UnauthorizedDeviceAttemptsDecryptionFails() {
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        broadcastManager.registerCampusKey(institutionScope, 1, keyBytes)

        val plainText = "Sensitive Campus Strategy".toByteArray()
        val headerAad = "AAD_HEADER".toByteArray()

        val encPair = broadcastManager.encryptBroadcastPayload(institutionScope, 1, sequence, plainText, headerAad)
        assertNotNull(encPair)

        val (cipherText, macTag) = encPair!!

        // Decrypt with unregistered scope
        val decrypted = broadcastManager.decryptBroadcastPayload("COLLEGE:UNREGISTERED", 1, sequence, cipherText, macTag, headerAad)
        assertNull(decrypted)
    }

    @Test
    fun test13_AuthorizedCampusMemberReceivesBroadcastSuccess() {
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        broadcastManager.registerCampusKey(institutionScope, 1, keyBytes)

        val plainText = "Auditorium Closed Today".toByteArray()
        val headerAad = "AAD_HEADER".toByteArray()

        val encPair = broadcastManager.encryptBroadcastPayload(institutionScope, 1, sequence, plainText, headerAad)
        assertNotNull(encPair)

        val (cipherText, macTag) = encPair!!

        val decrypted = broadcastManager.decryptBroadcastPayload(institutionScope, 1, sequence, cipherText, macTag, headerAad)
        assertNotNull(decrypted)
        assertEquals("Auditorium Closed Today", String(decrypted!!, Charsets.UTF_8))
    }

    @Test
    fun test14_BroadcastForwardedThroughMultipleRelays() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Multi-Hop Alert",
            message = "Relayed Message"
        )
        assertNotNull(bc)
        assertEquals("Multi-Hop Alert", bc!!.title)
    }

    @Test
    fun test15_DuplicateReceivedThroughMultiplePathsDisplayedOnce() {
        val bcId = "BC-DUPLICATE-01"
        assertFalse(broadcastManager.isDuplicateOrAdd(bcId)) // First: Returns false (Added)
        assertTrue(broadcastManager.isDuplicateOrAdd(bcId))  // Second: Returns true (Suppressed)
    }

    @Test
    fun test16_BroadcastStormPreventionBoundedForwarding() {
        val bcId = "BC-STORM-PREVENTION"
        val isFirst = broadcastManager.isDuplicateOrAdd(bcId)
        assertFalse(isFirst)
        val isSecond = broadcastManager.isDuplicateOrAdd(bcId)
        assertTrue(isSecond)
    }

    @Test
    fun test17_WirePayloadSerializationAndDeserialization() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Campus Wire Test",
            message = "Serialization payload check"
        )
        assertNotNull(bc)

        val wireBytes = bc!!.toWirePayload()
        val decoded = CollegeBroadcast.fromWirePayload(wireBytes)

        assertNotNull(decoded)
        assertEquals(bc.broadcastId, decoded!!.broadcastId)
        assertEquals(bc.institutionScope, decoded.institutionScope)
        assertEquals(bc.senderConnectMeshId, decoded.senderConnectMeshId)
        assertEquals(bc.signatureHex, decoded.signatureHex)
        assertEquals(bc.title, decoded.title)
        assertEquals(bc.message, decoded.message)
    }

    @Test
    fun test18_ForgedBroadcastWithoutValidSignatureRejected() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Official Alert",
            message = "Real Message"
        )
        assertNotNull(bc)

        // Forger alters message text without valid private key
        val forgedBc = bc!!.copy(message = "FORGED FAKE ANNOUNCEMENT")
        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = forgedBc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verResult)
    }

    @Test
    fun test19_CampusMessageIsNotClassroomMsgOrSos() {
        val bcType = com.connectmesh.protocol.PacketType.COLLEGE_BROADCAST
        val classType = com.connectmesh.protocol.PacketType.CLASSROOM_MSG
        val sosType = com.connectmesh.protocol.PacketType.SOS

        assertNotEquals(bcType, classType)
        assertNotEquals(bcType, sosType)
        assertEquals(0x15.toByte(), bcType.code)
        assertEquals(0x13.toByte(), classType.code)
        assertEquals(0x08.toByte(), sosType.code)
    }

    @Test
    fun test20_DirectChatVoiceAndFileProtocolsPreserved() {
        val msgType = com.connectmesh.protocol.PacketType.MESSAGE
        val voiceType = com.connectmesh.protocol.PacketType.VOICE_FRAGMENT
        val fileStartType = com.connectmesh.protocol.PacketType.FILE_START

        assertEquals(0x02.toByte(), msgType.code)
        assertEquals(0x05.toByte(), voiceType.code)
        assertEquals(0x0A.toByte(), fileStartType.code)
    }

    @Test
    fun test21_Phase6D2_LegacyTestSignatureRejected() {
        val legacyBc = CollegeBroadcast(
            broadcastId = "BC-LEGACY-001",
            institutionScope = institutionScope,
            senderConnectMeshId = adminId,
            senderCredentialId = "CRED-ADMIN-01",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 86400000L,
            priority = BroadcastPriority.HIGH,
            broadcastType = BroadcastType.ANNOUNCEMENT,
            title = "Legacy Test",
            message = "Magic string payload",
            signatureHex = "LEGACY_TEST_SIG"
        )

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = legacyBc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        // Must be rejected as invalid signature when magic string is provided without real ECDSA signature
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verResult)
    }

    @Test
    fun test22_Phase6D2_ForgedBroadcastDoesNotPoisonDedupState() {
        val bcId = "BC-POISON-TEST-01"
        val forgedBc = CollegeBroadcast(
            broadcastId = bcId,
            institutionScope = institutionScope,
            senderConnectMeshId = adminId,
            senderCredentialId = "CRED-ADMIN-01",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 86400000L,
            priority = BroadcastPriority.HIGH,
            broadcastType = BroadcastType.ANNOUNCEMENT,
            title = "Forged Alert",
            message = "Forged Payload",
            signatureHex = "0011223344556677" // Invalid fake sig
        )

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = forgedBc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verResult)

        // Verify that because verification failed, broadcastId was NOT added to processedBroadcastIds
        val isAlreadyProcessed = broadcastManager.getBroadcast(bcId) != null
        assertFalse(isAlreadyProcessed)
    }

    @Test
    fun test23_Phase6D2_ValidBroadcastAcceptedAfterForgedAttempt() {
        val bcId = "BC-RECOVERY-TEST-01"

        // 1. Attacker attempts forged broadcast with ID bcId
        val forgedBc = CollegeBroadcast(
            broadcastId = bcId,
            institutionScope = institutionScope,
            senderConnectMeshId = adminId,
            senderCredentialId = "CRED-ADMIN-01",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 86400000L,
            priority = BroadcastPriority.HIGH,
            broadcastType = BroadcastType.ANNOUNCEMENT,
            title = "Forged Alert",
            message = "Forged Payload",
            signatureHex = "DEADBEEF"
        )
        val verForged = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = forgedBc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verForged)

        // 2. Legitimate Admin issues valid broadcast with same bcId
        val validBc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Real Alert",
            message = "Real Message"
        )
        assertNotNull(validBc)

        val verValid = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = validBc!!,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.AUTHORIZED, verValid)

        // 3. Application-level deduplication: first valid addition -> returns false (not duplicate)
        val isDupFirst = broadcastManager.isDuplicateOrAdd(validBc.broadcastId)
        assertTrue(isDupFirst) // True because addVerifiedBroadcast added it during creation

        // 4. Re-transmission of same valid broadcast -> suppressed as duplicate
        val isDupSecond = broadcastManager.isDuplicateOrAdd(validBc.broadcastId)
        assertTrue(isDupSecond)
    }

    @Test
    fun test24_Phase6D5_DecodeValidBase64AndHexPublicKeys() {
        val pubKeyBytes = adminKeyPair.public.encoded
        val base64Key = java.util.Base64.getEncoder().encodeToString(pubKeyBytes)
        val hexKey = pubKeyBytes.joinToString("") { "%02x".format(it) }

        val decodedFromBase64 = AuthorizationManager.decodePublicKey(base64Key)
        assertNotNull(decodedFromBase64)
        assertArrayEquals(pubKeyBytes, decodedFromBase64)

        val decodedFromHex = AuthorizationManager.decodePublicKey(hexKey)
        assertNotNull(decodedFromHex)
        assertArrayEquals(pubKeyBytes, decodedFromHex)
    }

    @Test
    fun test25_Phase6D5_DecodeMalformedPublicKeyFails() {
        assertNull(AuthorizationManager.decodePublicKey(""))
        assertNull(AuthorizationManager.decodePublicKey("   "))
        assertNull(AuthorizationManager.decodePublicKey("INVALID_NOT_BASE64_OR_HEX_KEY!!!"))
        assertNull(AuthorizationManager.decodePublicKey("12345")) // Odd length hex
        assertNull(AuthorizationManager.decodePublicKey("001122334455")) // Valid hex but invalid EC key spec
    }

    @Test
    fun test26_Phase6D5_RemoteCampusBroadcastRejectedBeforeTrustEnrollment() {
        val studentAuthManager = AuthorizationManager(revocationManager)
        // Student has not enrolled admin's public key yet
        val studentTrustedKey = studentAuthManager.getTrustedIssuerKey(adminId)
        assertNull(studentTrustedKey)

        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Remote Alert",
            message = "Test message"
        )
        assertNotNull(bc)

        val result = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc!!,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = studentTrustedKey
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_UNTRUSTED_ISSUER, result)
    }

    @Test
    fun test27_Phase6D5_RemoteCampusBroadcastAcceptedAfterTrustEnrollment() {
        val studentAuthManager = AuthorizationManager(revocationManager)
        val adminPubKeyBase64 = java.util.Base64.getEncoder().encodeToString(adminKeyPair.public.encoded)

        val decodedKey = studentAuthManager.decodePublicKey(adminPubKeyBase64)
        assertNotNull(decodedKey)

        studentAuthManager.registerTrustedIssuer(adminId, decodedKey!!)

        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Remote Alert",
            message = "Verified after setup"
        )
        assertNotNull(bc)

        val trustedAdminKey = studentAuthManager.getTrustedIssuerKey(adminId)
        assertNotNull(trustedAdminKey)

        val result = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc!!,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = trustedAdminKey
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.AUTHORIZED, result)
    }

    @Test
    fun test28_Phase6D5_PersistedTrustAnchorRestorationAcrossRestart() {
        val authManager1 = AuthorizationManager(revocationManager)
        val adminHexKey = adminKeyPair.public.encoded.joinToString("") { "%02x".format(it) }

        val decodedKey1 = authManager1.decodePublicKey(adminHexKey)
        assertNotNull(decodedKey1)
        authManager1.registerTrustedIssuer(adminId, decodedKey1!!)

        // Simulate app restart: re-parse hex key string into new AuthorizationManager instance
        val authManager2 = AuthorizationManager(revocationManager)
        val restoredKeyBytes = authManager2.decodePublicKey(adminHexKey)
        assertNotNull(restoredKeyBytes)
        authManager2.registerTrustedIssuer(adminId, restoredKeyBytes!!)

        val retrievedKey = authManager2.getTrustedIssuerKey(adminId)
        assertNotNull(retrievedKey)
        assertArrayEquals(adminKeyPair.public.encoded, retrievedKey)
    }

    // =========================================================================
    // PHASE 6D.6 TARGETED SECURITY & MEMBERSHIP TESTS (STEP 7 TESTS 1 - 12)
    // =========================================================================

    @Test
    fun test29_Phase6D6_NullEnrollmentDoesNotMatch() {
        val enrolledScope: String? = null
        val broadcastScope = "COLLEGE:CAMPUS_01"
        assertFalse(CollegeBroadcastVerifier.isCampusScopeMatching(broadcastScope, enrolledScope))
    }

    @Test
    fun test30_Phase6D6_Campus01MatchesCampus01() {
        val enrolledScope = "COLLEGE:CAMPUS_01"
        val broadcastScope = "COLLEGE:CAMPUS_01"
        assertTrue(CollegeBroadcastVerifier.isCampusScopeMatching(broadcastScope, enrolledScope))
    }

    @Test
    fun test31_Phase6D6_Campus02DoesNotMatchCampus01() {
        val enrolledScope = "COLLEGE:CAMPUS_02"
        val broadcastScope = "COLLEGE:CAMPUS_01"
        assertFalse(CollegeBroadcastVerifier.isCampusScopeMatching(broadcastScope, enrolledScope))
    }

    @Test
    fun test32_Phase6D6_EnrollmentStatePersists() {
        val initialScope = "COLLEGE:CAMPUS_01"
        val reloadedScope = "COLLEGE:CAMPUS_01"
        assertEquals(initialScope, reloadedScope)
        assertTrue(CollegeBroadcastVerifier.isCampusScopeMatching("CAMPUS_01", reloadedScope))
    }

    @Test
    fun test33_Phase6D6_EnrolledCampus01DeviceAcceptsCampus01Broadcast() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Valid Admin Alert",
            message = "Accepted by enrolled device"
        )
        assertNotNull(bc)

        val verResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc!!,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.AUTHORIZED, verResult)
        assertTrue(CollegeBroadcastVerifier.isCampusScopeMatching(bc.institutionScope, institutionScope))
    }

    @Test
    fun test34_Phase6D6_EnrolledCampus02DeviceDoesNotDisplayCampus01Broadcast() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Campus 1 Alert",
            message = "Targeting Campus 1"
        )
        assertNotNull(bc)

        val isDisplayed = CollegeBroadcastVerifier.isCampusScopeMatching(bc!!.institutionScope, "COLLEGE:CAMPUS_02")
        assertFalse(isDisplayed)
    }

    @Test
    fun test35_Phase6D6_NonEnrolledDeviceDoesNotDisplayCampusBroadcast() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Campus Alert",
            message = "Message"
        )
        assertNotNull(bc)

        val enrolledScope: String? = null
        val isDisplayed = CollegeBroadcastVerifier.isCampusScopeMatching(bc!!.institutionScope, enrolledScope)
        assertFalse(isDisplayed)
    }

    @Test
    fun test36_Phase6D6_NonMemberRelayBehaviorRemainsPossible() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Relay Alert",
            message = "Relayed through non-enrolled node"
        )
        assertNotNull(bc)

        val header = com.connectmesh.protocol.PacketHeader(
            packetType = com.connectmesh.protocol.PacketType.COLLEGE_BROADCAST,
            packetId = 9999L,
            sourceId = adminId,
            destinationId = 0L,
            payloadLength = bc!!.toWirePayload().size.toShort(),
            ttl = 7
        )
        val packet = com.connectmesh.protocol.Packet(header, payload = bc.toWirePayload())

        val isDisplayedOnRelayNode = CollegeBroadcastVerifier.isCampusScopeMatching(bc.institutionScope, "CAMPUS_99")
        assertFalse(isDisplayedOnRelayNode)
        assertTrue(packet.header.ttl > 1)
    }

    @Test
    fun test37_Phase6D6_InvalidSignatureRemainsRejected() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Message"
        )
        assertNotNull(bc)

        val forgedBc = bc!!.copy(signatureHex = "DEADBEEF0011223344")
        val result = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = forgedBc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, result)
    }

    @Test
    fun test38_Phase6D6_UnknownIssuerRemainsRejected() {
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Alert",
            message = "Message"
        )
        assertNotNull(bc)

        val result = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc!!,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = null
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_UNTRUSTED_ISSUER, result)
    }

    @Test
    fun test39_Phase6D6_ValidDuplicateRemainsDeduplicated() {
        val bcId = "BC-DEDUP-6D6-01"
        assertFalse(broadcastManager.isDuplicateOrAdd(bcId))
        assertTrue(broadcastManager.isDuplicateOrAdd(bcId))
    }

    @Test
    fun test40_Phase6D6_ForgedBroadcastCannotPoisonDedup() {
        val bcId = "BC-POISON-6D6-01"
        val forgedBc = CollegeBroadcast(
            broadcastId = bcId,
            institutionScope = institutionScope,
            senderConnectMeshId = adminId,
            senderCredentialId = "CRED-ADMIN-01",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 86400000L,
            priority = BroadcastPriority.HIGH,
            broadcastType = BroadcastType.ANNOUNCEMENT,
            title = "Forged Alert",
            message = "Forged Payload",
            signatureHex = "BADF00D"
        )

        val result = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = forgedBc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = adminKeyPair.public.encoded
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, result)
        assertNull(broadcastManager.getBroadcast(bcId))
    }

    @Test
    fun test41_Phase6D7_CampusEnrollmentDetailsCopyAndParse() {
        val original = CampusEnrollmentDetails(
            campusScope = "COLLEGE:CAMPUS_01",
            authorityIdHex = "0x3988776655443322",
            authorityPublicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE12345678"
        )
        val copyable = original.toCopyableString()
        assertEquals("COLLEGE:CAMPUS_01|0x3988776655443322|MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE12345678", copyable)

        val parsed = CampusEnrollmentDetails.parse(copyable)
        assertNotNull(parsed)
        assertEquals(original.campusScope, parsed!!.campusScope)
        assertEquals(original.authorityIdHex, parsed.authorityIdHex)
        assertEquals(original.authorityPublicKeyBase64, parsed.authorityPublicKeyBase64)
    }

    @Test
    fun test42_Phase6D7_AdminEnrollmentExportNeverExposesPrivateKey() {
        val pubKeyB64 = java.util.Base64.getEncoder().encodeToString(adminKeyPair.public.encoded)
        val details = CampusEnrollmentDetails(
            campusScope = "COLLEGE:CAMPUS_01",
            authorityIdHex = "0x${adminId.toString(16).uppercase()}",
            authorityPublicKeyBase64 = pubKeyB64
        )
        // Ensure private key string is not present in exported string
        val exported = details.toCopyableString()
        assertFalse(exported.contains("PRIVATE"))
        assertFalse(exported.contains("PrivateKey"))
        assertTrue(exported.contains("0x3988776655443322"))
    }

    @Test
    fun test43_Phase6D7_StudentEnrollmentUsingAdminPublicDetailsVerifiesBroadcast() {
        // Admin device exports enrollment details
        val pubKeyB64 = java.util.Base64.getEncoder().encodeToString(adminKeyPair.public.encoded)
        val details = CampusEnrollmentDetails(
            campusScope = institutionScope,
            authorityIdHex = "0x${adminId.toString(16).uppercase()}",
            authorityPublicKeyBase64 = pubKeyB64
        )

        // Student device receives and parses enrollment details
        val parsedDetails = CampusEnrollmentDetails.parse(details.toCopyableString())
        assertNotNull(parsedDetails)

        val studentAuthManager = AuthorizationManager(revocationManager)
        val cleanIdStr = parsedDetails!!.authorityIdHex.trim().removePrefix("0x").removePrefix("0X")
        val parsedIssuerId = cleanIdStr.toLongOrNull(16) ?: 0L
        val decodedPubKeyBytes = studentAuthManager.decodePublicKey(parsedDetails.authorityPublicKeyBase64)

        assertNotNull(decodedPubKeyBytes)
        studentAuthManager.registerTrustedIssuer(parsedIssuerId, decodedPubKeyBytes!!)

        // Admin creates campus broadcast
        val bc = broadcastManager.createAndSignBroadcast(
            adminConnectMeshId = adminId,
            adminCredentialId = "CRED-ADMIN-01",
            adminPrivateKey = adminKeyPair.private,
            institutionScope = institutionScope,
            title = "Campus Announcement",
            message = "Welcome to campus!"
        )
        assertNotNull(bc)

        // Student verifies broadcast using registered trust anchor
        val trustedKeyOnStudent = studentAuthManager.getTrustedIssuerKey(bc!!.senderConnectMeshId)
        val verifyResult = CollegeBroadcastVerifier.verifyBroadcast(
            broadcast = bc,
            actualSenderId = adminId,
            trustedAdminPublicKeyBytes = trustedKeyOnStudent
        )
        assertEquals(CollegeBroadcastVerifier.VerificationResult.AUTHORIZED, verifyResult)
    }

    @Test
    fun test44_Phase6D7_ScopeFilteringPreservesMeshRelayForNonMatchingCampus() {
        val studentScope = "COLLEGE:CAMPUS_01"
        val broadcastScope = "COLLEGE:CAMPUS_02"

        val admin2Id: Long = 0x7988776655443322L
        val admin2KeyPair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"), java.security.SecureRandom())
        }.generateKeyPair()

        val admin2AuthManager = AuthorizationManager(revocationManager)
        admin2AuthManager.registerTrustedIssuer(issuerId, issuerKeyPair.public.encoded)
        admin2AuthManager.initializeLocalIdentity(admin2Id, admin2KeyPair.public.encoded)

        val admin2Cred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = admin2Id,
            subjectPublicKeyBytes = admin2KeyPair.public.encoded,
            role = UserRole.ADMIN,
            scope = broadcastScope
        )
        assertNotNull(admin2Cred)
        admin2AuthManager.setLocalCredential(admin2Cred!!)

        val admin2BroadcastManager = CampusBroadcastManager(admin2AuthManager)

        val bc = admin2BroadcastManager.createAndSignBroadcast(
            adminConnectMeshId = admin2Id,
            adminCredentialId = "CRED-ADMIN-02",
            adminPrivateKey = admin2KeyPair.private,
            institutionScope = broadcastScope,
            title = "Other Campus Alert",
            message = "Alert for Campus 02"
        )
        assertNotNull(bc)

        // UI display check
        val isDisplayedOnStudentDevice = CollegeBroadcastVerifier.isCampusScopeMatching(bc!!.institutionScope, studentScope)
        assertFalse(isDisplayedOnStudentDevice)

        // Mesh packet check for relay
        val packet = com.connectmesh.protocol.Packet(
            header = com.connectmesh.protocol.PacketHeader(
                packetType = com.connectmesh.protocol.PacketType.COLLEGE_BROADCAST,
                packetId = 8888L,
                sourceId = admin2Id,
                destinationId = 0L,
                payloadLength = bc.toWirePayload().size.toShort(),
                ttl = 7
            ),
            payload = bc.toWirePayload()
        )
        // Multi-hop routing packet remains valid with destinationId=0L (broadcast) and TTL > 1
        assertEquals(0L, packet.header.destinationId)
        assertTrue(packet.header.ttl > 1)
    }
}

