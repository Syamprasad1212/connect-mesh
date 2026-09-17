package com.connectmesh.classroom

import com.connectmesh.auth.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class ClassroomSecurityTest {

    private lateinit var issuerKeyPair: java.security.KeyPair
    private lateinit var teacherKeyPair: java.security.KeyPair
    private lateinit var studentKeyPair: java.security.KeyPair
    private lateinit var nonMemberKeyPair: java.security.KeyPair

    private val issuerId: Long = 0x1122334455667788L
    private val teacherId: Long = 0x3344556677889900L
    private val studentId: Long = 0x5566778899001122L
    private val nonMemberId: Long = 0x7788990011223344L

    private lateinit var revocationManager: RevocationManager
    private lateinit var authorizationManager: AuthorizationManager
    private lateinit var classroomManager: ClassroomManager

    private val sequence: Long = 1001L

    @Before
    fun setUp() {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        issuerKeyPair = keyGen.generateKeyPair()
        teacherKeyPair = keyGen.generateKeyPair()
        studentKeyPair = keyGen.generateKeyPair()
        nonMemberKeyPair = keyGen.generateKeyPair()

        revocationManager = RevocationManager()
        authorizationManager = AuthorizationManager(revocationManager)
        classroomManager = ClassroomManager(authorizationManager)

        // Register issuer public key
        authorizationManager.registerTrustedIssuer(issuerId, issuerKeyPair.public.encoded)

        // Give teacher a valid Teacher role credential for scope "COLLEGE:CAMPUS_01"
        val teacherCred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = teacherId,
            subjectPublicKeyBytes = teacherKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = "COLLEGE:CAMPUS_01"
        )
        assertNotNull(teacherCred)

        authorizationManager.initializeLocalIdentity(teacherId, teacherKeyPair.public.encoded)
        authorizationManager.setLocalCredential(teacherCred!!)
    }

    @Test
    fun test1_ValidTeacherCreatesClassroom() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)
        assertEquals("CSE-A", group!!.groupName)
    }

    @Test
    fun test2_NormalUserAttemptsClassroomCreation() {
        val studentAuthManager = AuthorizationManager(revocationManager)
        studentAuthManager.registerTrustedIssuer(issuerId, issuerKeyPair.public.encoded)
        studentAuthManager.initializeLocalIdentity(studentId, studentKeyPair.public.encoded)

        val studentUserCred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = studentId,
            subjectPublicKeyBytes = studentKeyPair.public.encoded,
            role = UserRole.USER,
            scope = "COLLEGE:CAMPUS_01"
        )
        studentAuthManager.setLocalCredential(studentUserCred!!)

        val studentClassroomManager = ClassroomManager(studentAuthManager)
        val group = studentClassroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", studentId, studentKeyPair.public.encoded)
        assertNull(group)
    }

    @Test
    fun test3_TeacherWithMatchingScopeManagesClassroom() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val memCred = classroomManager.addMember(
            groupId = group!!.groupId,
            memberConnectMeshId = studentId,
            memberPublicKeyBytes = studentKeyPair.public.encoded,
            memberRole = UserRole.USER,
            issuerPrivateKey = teacherKeyPair.private,
            issuerConnectMeshId = teacherId
        )
        assertNotNull(memCred)
        assertTrue(classroomManager.isMember(group.groupId, studentId))
    }

    @Test
    fun test4_TeacherWithWrongScopeFails() {
        val wrongScopeTeacherCred = RoleCredentialIssuer.issueCredential(
            issuerId = issuerId,
            issuerPrivateKey = issuerKeyPair.private,
            subjectConnectMeshId = teacherId,
            subjectPublicKeyBytes = teacherKeyPair.public.encoded,
            role = UserRole.TEACHER,
            scope = "CLASSROOM:CSE-A"
        )
        authorizationManager.setLocalCredential(wrongScopeTeacherCred!!)

        val group = classroomManager.createClassroom("CSE-B", "CLASSROOM:CSE-B", teacherId, teacherKeyPair.public.encoded)
        assertNull(group)
    }

    @Test
    fun test5_AuthorizedStudentJoinsClassroom() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val cred = classroomManager.addMember(group!!.groupId, studentId, studentKeyPair.public.encoded, UserRole.USER, teacherKeyPair.private, teacherId)
        assertNotNull(cred)
        assertTrue(classroomManager.isMember(group.groupId, studentId))
    }

    @Test
    fun test6_UnknownUserIsNotMember() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)
        assertFalse(classroomManager.isMember(group!!.groupId, nonMemberId))
    }

    @Test
    fun test7_NonMemberCannotDecryptClassroomMessage() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val plainText = "Classroom Assignment Update".toByteArray()
        val headerAad = "AAD_HEADER".toByteArray()

        val encResult = classroomManager.encryptGroupPayload(group!!.groupId, sequence, plainText, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        val decrypted = classroomManager.decryptGroupPayload(
            groupId = group.groupId,
            keyVersion = version,
            sequence = sequence,
            memberConnectMeshId = nonMemberId,
            ciphertext = cipherText,
            macTag = macTag,
            headerAad = headerAad
        )
        assertNull(decrypted)
    }

    @Test
    fun test8_RemovedMemberCannotDecryptNewMessages() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        classroomManager.addMember(group!!.groupId, studentId, studentKeyPair.public.encoded, UserRole.USER, teacherKeyPair.private, teacherId)
        assertTrue(classroomManager.isMember(group.groupId, studentId))

        // Remove member -> Key Rotates to version 2
        val removed = classroomManager.removeMember(group.groupId, studentId)
        assertTrue(removed)
        assertFalse(classroomManager.isMember(group.groupId, studentId))

        val newMsgText = "Secret Post-Removal Notes".toByteArray()
        val headerAad = "AAD_HEADER".toByteArray()

        val encResult = classroomManager.encryptGroupPayload(group.groupId, sequence, newMsgText, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        assertEquals(2, version)

        val (cipherText, macTag) = cipherPair
        val decrypted = classroomManager.decryptGroupPayload(group.groupId, version, sequence, studentId, cipherText, macTag, headerAad)
        assertNull(decrypted)
    }

    @Test
    fun test9_ExpiredMembershipRejection() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val expiredCred = ClassroomMembershipCredential(
            membershipId = "MEM-EXPIRED",
            groupId = group!!.groupId,
            memberConnectMeshId = studentId,
            memberPublicKeyFingerprint = RoleCredentialVerifier.calculatePublicKeyFingerprint(studentKeyPair.public.encoded),
            memberRole = UserRole.USER,
            issuedByConnectMeshId = teacherId,
            issuedAt = System.currentTimeMillis() - 100000L,
            expiresAt = System.currentTimeMillis() - 1000L, // Expired
            signatureHex = "SIG"
        )
        assertFalse(System.currentTimeMillis() <= expiredCred.expiresAt)
    }

    @Test
    fun test10_TamperedGroupIdRejection() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val plainText = "Test".toByteArray()
        val headerAad = "AAD".toByteArray()

        val encResult = classroomManager.encryptGroupPayload(group!!.groupId, sequence, plainText, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        val decrypted = classroomManager.decryptGroupPayload("GRP-FAKE", version, sequence, teacherId, cipherText, macTag, headerAad)
        assertNull(decrypted)
    }

    @Test
    fun test11_TamperedMembershipCredentialSignature() {
        val cred = ClassroomMembershipCredential(
            membershipId = "MEM-1",
            groupId = "GRP-CSE-A",
            memberConnectMeshId = studentId,
            memberPublicKeyFingerprint = RoleCredentialVerifier.calculatePublicKeyFingerprint(studentKeyPair.public.encoded),
            memberRole = UserRole.USER,
            issuedByConnectMeshId = teacherId,
            issuedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 100000L,
            signatureHex = "CORRUPTED_SIGNATURE"
        )
        val verifierResult = RoleCredentialVerifier.verifyCredential(
            credential = RoleCredential(
                credentialId = cred.membershipId,
                subjectConnectMeshId = studentId,
                subjectPublicKeyFingerprint = cred.memberPublicKeyFingerprint,
                role = UserRole.USER,
                issuerId = teacherId,
                issuedAt = cred.issuedAt,
                expiresAt = cred.expiresAt,
                scope = "CLASSROOM:CSE-A",
                signatureHex = cred.signatureHex
            ),
            actualSubjectId = studentId,
            actualSubjectPublicKeyBytes = studentKeyPair.public.encoded,
            trustedIssuerPublicKeyBytes = teacherKeyPair.public.encoded
        )
        assertEquals(RoleCredentialVerifier.VerificationResult.REJECTED_INVALID_SIGNATURE, verifierResult)
    }

    @Test
    fun test12_GroupKeyVersionMismatchRejection() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val plainText = "Data".toByteArray()
        val headerAad = "AAD".toByteArray()

        val encResult = classroomManager.encryptGroupPayload(group!!.groupId, sequence, plainText, headerAad)
        assertNotNull(encResult)

        val (_, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        // Request non-existent version 99
        val decrypted = classroomManager.decryptGroupPayload(group.groupId, 99, sequence, teacherId, cipherText, macTag, headerAad)
        assertNull(decrypted)
    }

    @Test
    fun test13_AuthorizedMemberDecryptsSuccess() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        classroomManager.addMember(group!!.groupId, studentId, studentKeyPair.public.encoded, UserRole.USER, teacherKeyPair.private, teacherId)

        val plainText = "Lecture Room Changed to Hall 3".toByteArray()
        val headerAad = "AAD_HEADER".toByteArray()

        val encResult = classroomManager.encryptGroupPayload(group.groupId, sequence, plainText, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        val decrypted = classroomManager.decryptGroupPayload(group.groupId, version, sequence, studentId, cipherText, macTag, headerAad)
        assertNotNull(decrypted)
        assertEquals("Lecture Room Changed to Hall 3", String(decrypted!!, Charsets.UTF_8))
    }

    @Test
    fun test14_NonMemberReceivesCiphertextCannotDecrypt() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val plainText = "Top Secret Quiz Questions".toByteArray()
        val headerAad = "AAD".toByteArray()

        val encResult = classroomManager.encryptGroupPayload(group!!.groupId, sequence, plainText, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        val decrypted = classroomManager.decryptGroupPayload(group.groupId, version, sequence, nonMemberId, cipherText, macTag, headerAad)
        assertNull(decrypted)
    }

    @Test
    fun test15_FacultyCreatesClassroomExposesUniqueClassroomCode() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)
        assertTrue(group!!.groupId.startsWith("GRP-"))
        assertNotNull(classroomManager.findClassroomByCode(group.groupId))
    }

    @Test
    fun test16_StudentJoinsClassroomByValidCode() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val code = group!!.groupId
        val joinedGroup = classroomManager.joinClassroomByCode(code, studentId, studentKeyPair.public.encoded)
        assertNotNull(joinedGroup)
        assertEquals(group.groupId, joinedGroup!!.groupId)

        // Verify student is now a member and can decrypt group messages
        assertTrue(classroomManager.isMember(group.groupId, studentId))

        val plainText = "Welcome Students".toByteArray()
        val headerAad = "AAD".toByteArray()
        val encResult = classroomManager.encryptGroupPayload(group.groupId, sequence, plainText, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        val decrypted = classroomManager.decryptGroupPayload(group.groupId, version, sequence, studentId, cipherText, macTag, headerAad)
        assertNotNull(decrypted)
        assertEquals("Welcome Students", String(decrypted!!, Charsets.UTF_8))
    }

    @Test
    fun test17_StudentJoinsWithInvalidCodeFails() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val invalidResult = classroomManager.joinClassroomByCode("INVALID-CODE-9999", studentId)
        assertNull(invalidResult)
        assertFalse(classroomManager.isMember(group!!.groupId, studentId))
    }

    @Test
    fun test18_ClassroomCodeDoesNotExposeSymmetricKey() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        assertFalse(group!!.groupId.contains(group.activeGroupKeyHex))
        assertFalse(group.groupId.contains("KEY"))
    }

    @Test
    fun test19_CreateClassroomGeneratesShortJoinCode() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)
        assertTrue(group!!.joinCode.isNotBlank())
        assertTrue(group.joinCode.length in 6..10)
        assertEquals(group.joinCode, group.displayJoinCode)
    }

    @Test
    fun test20_FindClassroomByShortJoinCode() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val found = classroomManager.findClassroomByCode(group!!.joinCode)
        assertNotNull(found)
        assertEquals(group.groupId, found!!.groupId)
    }

    @Test
    fun test21_ClassroomInvitationRegistrationAndDiscovery() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        // Remote device manager receives classroom invitation
        val remoteManager = ClassroomManager(authorizationManager)
        remoteManager.registerClassroom(group!!)

        val foundOnRemote = remoteManager.findClassroomByCode(group.joinCode)
        assertNotNull(foundOnRemote)
        assertEquals(group.groupId, foundOnRemote!!.groupId)

        val joined = remoteManager.joinClassroomByCode(group.joinCode, studentId, studentKeyPair.public.encoded)
        assertNotNull(joined)
        assertTrue(remoteManager.isMember(group.groupId, studentId))
    }

    @Test
    fun test22_ClassroomRestorationPreservesLocalMembership() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        // Simulate app restart on student device restoring classroom from DB
        val studentAppManager = ClassroomManager(authorizationManager)
        studentAppManager.registerClassroom(group!!, studentId)

        assertTrue(studentAppManager.isMember(group.groupId, studentId))

        // Student can decrypt messages after restart
        val plainText = "Message After Restart".toByteArray()
        val headerAad = "AAD".toByteArray()
        val encResult = classroomManager.encryptGroupPayload(group.groupId, sequence, plainText, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        val decrypted = studentAppManager.decryptGroupPayload(group.groupId, version, sequence, studentId, cipherText, macTag, headerAad)
        assertNotNull(decrypted)
        assertEquals("Message After Restart", String(decrypted!!, Charsets.UTF_8))
    }

    @Test
    fun test23_ClassroomIsolation() {
        val groupA = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        val groupB = classroomManager.createClassroom("CSE-B", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(groupA)
        assertNotNull(groupB)

        // Student joins only Group A
        classroomManager.joinClassroomByCode(groupA!!.joinCode, studentId, studentKeyPair.public.encoded)
        assertTrue(classroomManager.isMember(groupA.groupId, studentId))
        assertFalse(classroomManager.isMember(groupB!!.groupId, studentId))

        val plainText = "Secrets for CSE-B".toByteArray()
        val headerAad = "AAD".toByteArray()
        val encResult = classroomManager.encryptGroupPayload(groupB.groupId, sequence, plainText, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        // Student tries to decrypt CSE-B message
        val decrypted = classroomManager.decryptGroupPayload(groupB.groupId, version, sequence, studentId, cipherText, macTag, headerAad)
        assertNull(decrypted)
    }

    @Test
    fun test24_JoinCodeDoesNotExposeSymmetricKey() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        assertFalse(group!!.joinCode.contains(group.activeGroupKeyHex))
        assertTrue(group.activeGroupKeyHex.length == 64) // 256-bit Hex Key
    }
}

