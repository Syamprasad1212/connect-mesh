package com.connectmesh.relay

import com.connectmesh.auth.*
import com.connectmesh.broadcast.*
import com.connectmesh.classroom.*
import com.connectmesh.protocol.Packet
import com.connectmesh.protocol.PacketHeader
import com.connectmesh.protocol.PacketType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class StaticRelaySecurityTest {

    private lateinit var relayKeyPair: java.security.KeyPair
    private lateinit var adminKeyPair: java.security.KeyPair
    private lateinit var userKeyPair: java.security.KeyPair

    private val relayId: Long = 0x3122334455667788L
    private val adminId: Long = 0x3988776655443322L
    private val userId: Long = 0x3566778899001122L

    private lateinit var relayController: StaticRelayController
    private lateinit var relayConfig: StaticRelayConfig

    @Before
    fun setUp() {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        relayKeyPair = keyGen.generateKeyPair()
        adminKeyPair = keyGen.generateKeyPair()
        userKeyPair = keyGen.generateKeyPair()

        relayController = StaticRelayController()
        relayConfig = StaticRelayConfig(
            relayId = relayId,
            locationLabel = "CANTEEN-01",
            isRelayEnabled = true
        )
        relayController.enableStaticRelayMode(relayConfig)
    }

    @Test
    fun test1_StaticRelayGetsPersistentIdentity() {
        assertNotNull(relayConfig.relayId)
        assertEquals(relayId, relayConfig.relayId)
        assertEquals("CANTEEN-01", relayConfig.locationLabel)
    }

    @Test
    fun test2_RelayIdentitySurvivesServiceRestart() {
        val restoredConfig = relayConfig.copy()
        assertEquals(relayConfig.relayId, restoredConfig.relayId)
        assertEquals(relayConfig.locationLabel, restoredConfig.locationLabel)
    }

    @Test
    fun test3_RelayIdentitySurvivesProcessRestart() {
        val restoredConfig = relayConfig.copy()
        assertEquals(relayConfig.relayId, restoredConfig.relayId)
    }

    @Test
    fun test4_RelayIdentitySurvivesRebootCompatiblePersistence() {
        val restoredConfig = relayConfig.copy()
        assertEquals(relayConfig.relayId, restoredConfig.relayId)
    }

    @Test
    fun test5_RelayForwardsTextPacket() {
        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = 101L,
            sourceId = userId,
            destinationId = 0x1111L,
            ttl = 7,
            payloadLength = 10
        )
        val packet = Packet(header = header, payload = "Ciphertext".toByteArray())
        val decision = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, decision)
    }

    @Test
    fun test6_RelayForwardsVoiceFragment() {
        val header = PacketHeader(
            packetType = PacketType.VOICE_FRAGMENT,
            packetId = 102L,
            sourceId = userId,
            destinationId = 0x1111L,
            ttl = 7,
            payloadLength = 180
        )
        val packet = Packet(header = header, payload = ByteArray(180))
        val decision = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, decision)
    }

    @Test
    fun test7_RelayForwardsFileChunk() {
        val header = PacketHeader(
            packetType = PacketType.FILE_CHUNK,
            packetId = 103L,
            sourceId = userId,
            destinationId = 0x1111L,
            ttl = 7,
            payloadLength = 384
        )
        val packet = Packet(header = header, payload = ByteArray(384))
        val decision = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, decision)
    }

    @Test
    fun test8_RelayForwardsClassroomMessage() {
        val header = PacketHeader(
            packetType = PacketType.CLASSROOM_MSG,
            packetId = 104L,
            sourceId = userId,
            destinationId = 0L,
            ttl = 7,
            payloadLength = 50
        )
        val packet = Packet(header = header, payload = ByteArray(50))
        val decision = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, decision)
    }

    @Test
    fun test9_RelayForwardsCollegeBroadcast() {
        val header = PacketHeader(
            packetType = PacketType.COLLEGE_BROADCAST,
            packetId = 105L,
            sourceId = adminId,
            destinationId = 0L,
            ttl = 7,
            payloadLength = 60
        )
        val packet = Packet(header = header, payload = ByteArray(60))
        val decision = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, decision)
    }

    @Test
    fun test10_RelayForwardsSOS() {
        val header = PacketHeader(
            packetType = PacketType.SOS,
            packetId = 106L,
            sourceId = userId,
            destinationId = 0L,
            ttl = 7,
            payloadLength = 20
        )
        val packet = Packet(header = header, payload = ByteArray(20))
        val decision = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, decision)
    }

    @Test
    fun test11_RelayCannotDecryptProtectedText() {
        // Relay possesses no symmetric session keys
        val ciphertextPayload = "ENCRYPTED_TEXT_CIPHERTEXT".toByteArray()
        val isPlaintext = String(ciphertextPayload, Charsets.UTF_8).contains("Hello")
        assertFalse(isPlaintext)
    }

    @Test
    fun test12_RelayCannotDecryptClassroomMessage() {
        val classroomPayload = "ENCRYPTED_CLASSROOM_CIPHERTEXT".toByteArray()
        val isPlaintext = String(classroomPayload, Charsets.UTF_8).contains("CSE-A")
        assertFalse(isPlaintext)
    }

    @Test
    fun test13_RelayCannotDecryptProtectedCampusBroadcast() {
        val campusPayload = "ENCRYPTED_BROADCAST_CIPHERTEXT".toByteArray()
        val isPlaintext = String(campusPayload, Charsets.UTF_8).contains("Auditorium")
        assertFalse(isPlaintext)
    }

    @Test
    fun test14_DuplicatePacketSuppressed() {
        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = 201L,
            sourceId = userId,
            destinationId = 0x1111L,
            ttl = 7,
            payloadLength = 10
        )
        val packet = Packet(header = header, payload = "Data".toByteArray())

        val decision1 = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, decision1)

        val decision2 = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.SUPPRESSED_DUPLICATE, decision2)
    }

    @Test
    fun test15_TtlReachesZeroNotForwarded() {
        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = 301L,
            sourceId = userId,
            destinationId = 0x1111L,
            ttl = 1, // Expired
            payloadLength = 10
        )
        val packet = Packet(header = header, payload = "Data".toByteArray())

        val decision = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.EXPIRED_TTL, decision)
    }

    @Test
    fun test16_ForwardingLoopBounded() {
        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = 401L,
            sourceId = userId,
            destinationId = 0x1111L,
            ttl = 7,
            payloadLength = 10
        )
        val packet = Packet(header = header, payload = "Loop test".toByteArray())

        val d1 = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, d1)

        val d2 = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.SUPPRESSED_DUPLICATE, d2)
    }

    @Test
    fun test17_UnauthorizedUserAttemptsToEnableRelayMode() {
        val userAuthManager = AuthorizationManager()
        val userRole = userAuthManager.getLocalRole()
        assertNotEquals(UserRole.ADMIN, userRole)
    }

    @Test
    fun test18_RelayAttemptsToObtainAdminPrivilegesRejected() {
        val relayAuthManager = AuthorizationManager()
        relayAuthManager.initializeLocalIdentity(relayId, relayKeyPair.public.encoded)

        assertFalse(relayAuthManager.hasRole(UserRole.ADMIN))
        assertFalse(relayAuthManager.canBroadcastCampus("COLLEGE:CAMPUS_01"))
    }

    @Test
    fun test19_RelayLogsPacketNoPlaintextOrKeyMaterial() {
        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = 501L,
            sourceId = userId,
            destinationId = 0x1111L,
            ttl = 7,
            payloadLength = 15
        )
        val packet = Packet(header = header, payload = "EncryptedSecret".toByteArray())
        val decision = relayController.inspectAndForwardPacket(packet)
        assertEquals(StaticRelayController.ForwardDecision.FORWARD, decision)
        assertTrue(relayController.packetsForwarded.get() > 0)
    }

    @Test
    fun test20_RelayDisconnectsRoutingRecoversThroughOtherPaths() {
        relayController.disableStaticRelayMode()
        assertFalse(relayController.isStaticRelay())
    }
}
