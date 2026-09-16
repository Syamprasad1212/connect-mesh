package com.connectmesh.classroom

import com.connectmesh.auth.*
import com.connectmesh.mesh.DeduplicationManager
import com.connectmesh.protocol.Packet
import com.connectmesh.protocol.PacketHeader
import com.connectmesh.protocol.PacketType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class ClassroomChatTest {

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

    private val sequence: Long = 2002L

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

        authorizationManager.registerTrustedIssuer(issuerId, issuerKeyPair.public.encoded)

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
    fun test1_CreateAndJoinClassroomFlow() {
        // 1. Create classroom
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)
        val code = group!!.groupId

        // 2. Join classroom
        val joinedGroup = classroomManager.joinClassroomByCode(code, studentId, studentKeyPair.public.encoded)
        assertNotNull(joinedGroup)
        assertEquals(code, joinedGroup!!.groupId)

        // Verify membership
        assertTrue(classroomManager.isMember(code, teacherId))
        assertTrue(classroomManager.isMember(code, studentId))
        assertFalse(classroomManager.isMember(code, nonMemberId))
    }

    @Test
    fun test2_SendClassroomMessageTargetedToGroup() {
        val group = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", teacherId, teacherKeyPair.public.encoded)
        assertNotNull(group)

        val text = "Tomorrow lab starts at 10 AM."
        val plainBytes = text.toByteArray(Charsets.UTF_8)
        val headerAad = "AAD_CLASSROOM".toByteArray(Charsets.UTF_8)

        val encResult = classroomManager.encryptGroupPayload(group!!.groupId, sequence, plainBytes, headerAad)
        assertNotNull(encResult)

        val (version, cipherPair) = encResult!!
        val (cipherText, macTag) = cipherPair

        // Message contains correct classroom ID
        val msg = ClassroomMessage(
            id = sequence,
            groupId = group.groupId,
            senderId = teacherId,
            text = text,
            timestamp = System.currentTimeMillis(),
            groupKeyVersion = version,
            isSelf = true
        )
        classroomManager.addMessage(msg)

        val messages = classroomManager.getMessages(group.groupId)
        assertEquals(1, messages.size)
        assertEquals(group.groupId, messages[0].groupId)
        assertEquals("Tomorrow lab starts at 10 AM.", messages[0].text)

        // Non-member cannot decrypt
        val nonMemberDecrypt = classroomManager.decryptGroupPayload(group.groupId, version, sequence, nonMemberId, cipherText, macTag, headerAad)
        assertNull(nonMemberDecrypt)

        // Member can decrypt
        classroomManager.joinClassroomByCode(group.groupId, studentId, studentKeyPair.public.encoded)
        val studentDecrypt = classroomManager.decryptGroupPayload(group.groupId, version, sequence, studentId, cipherText, macTag, headerAad)
        assertNotNull(studentDecrypt)
        assertEquals(text, String(studentDecrypt!!, Charsets.UTF_8))
    }

    @Test
    fun test3_DeduplicationOfClassroomMessage() {
        val dedupManager = DeduplicationManager()
        val packetId = 998877L

        val header = PacketHeader(
            packetType = PacketType.CLASSROOM_MSG,
            packetId = packetId,
            sourceId = teacherId,
            destinationId = 0L,
            payloadLength = 40,
            ttl = 7
        )
        // First arrival -> not duplicate (adds to cache)
        assertFalse(dedupManager.isDuplicateOrAdd(packetId))

        // Second arrival -> duplicate
        assertTrue(dedupManager.isDuplicateOrAdd(packetId))
    }

    @Test
    fun test4_ClassroomMessageIsNotCampusBroadcast() {
        // Verify PacketType distinction
        assertNotEquals(PacketType.CLASSROOM_MSG, PacketType.COLLEGE_BROADCAST)
        assertEquals(0x13.toByte(), PacketType.CLASSROOM_MSG.code)
        assertEquals(0x15.toByte(), PacketType.COLLEGE_BROADCAST.code)
    }

    @Test
    fun test5_DirectChatTypeUnchanged() {
        // Direct messages use PacketType.MESSAGE (0x02)
        assertEquals(0x02.toByte(), PacketType.MESSAGE.code)
    }
}
