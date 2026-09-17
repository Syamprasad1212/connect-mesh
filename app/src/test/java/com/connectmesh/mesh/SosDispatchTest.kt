package com.connectmesh.mesh

import com.connectmesh.protocol.Packet
import com.connectmesh.protocol.PacketDecoder
import com.connectmesh.protocol.PacketHeader
import com.connectmesh.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SosDispatchTest {

    data class PendingOp(
        val targetPeerId: Long,
        val packetData: ByteArray,
        val priority: BleOperationQueue.Priority = BleOperationQueue.Priority.HIGH
    )

    enum class PeerConnectionState {
        CONNECTING,
        DISCOVERING_SERVICES,
        READY,
        DISCONNECTED
    }

    class MockSosDispatcher {
        val peerStates = ConcurrentHashMap<Long, PeerConnectionState>()
        val immediateSentPackets = ConcurrentHashMap<Long, MutableList<ByteArray>>()
        val pendingPacketsMap = ConcurrentHashMap<Long, MutableList<PendingOp>>()

        fun sendPacketToPeer(peerId: Long, packetData: ByteArray): Boolean {
            val state = peerStates[peerId] ?: PeerConnectionState.DISCONNECTED
            if (state == PeerConnectionState.READY) {
                immediateSentPackets.computeIfAbsent(peerId) { mutableListOf() }.add(packetData)
                return true
            }
            return false
        }

        fun dispatchOrQueuePacket(peerId: Long, packetData: ByteArray) {
            val sent = sendPacketToPeer(peerId, packetData)
            if (!sent) {
                pendingPacketsMap.computeIfAbsent(peerId) { mutableListOf() }
                    .add(PendingOp(peerId, packetData))
            }
        }

        fun broadcastSosPacket(packetData: ByteArray, targetPeers: List<Long>) {
            targetPeers.forEach { peerId ->
                val state = peerStates[peerId] ?: PeerConnectionState.DISCONNECTED
                if (state == PeerConnectionState.READY) {
                    sendPacketToPeer(peerId, packetData)
                } else {
                    dispatchOrQueuePacket(peerId, packetData)
                }
            }
        }

        fun onPeerStateChangedToReady(peerId: Long) {
            peerStates[peerId] = PeerConnectionState.READY
            val pendingList = pendingPacketsMap.remove(peerId)
            if (!pendingList.isNullOrEmpty()) {
                pendingList.forEach { op ->
                    sendPacketToPeer(peerId, op.packetData)
                }
            }
        }
    }

    @Test
    fun test1_ReadyPeerReceivesSosImmediately() {
        val dispatcher = MockSosDispatcher()
        val peerB = 200L
        dispatcher.peerStates[peerB] = PeerConnectionState.READY

        val sosBytes = "SOS_PAYLOAD_TEST_1".toByteArray()
        dispatcher.broadcastSosPacket(sosBytes, listOf(peerB))

        // Verified immediate transmission
        val sentToB = dispatcher.immediateSentPackets[peerB]
        assertNotNull(sentToB)
        assertEquals(1, sentToB!!.size)
        assertArrayEquals(sosBytes, sentToB[0])

        // Verified no pending queue entry for READY peer
        assertNull(dispatcher.pendingPacketsMap[peerB])
    }

    @Test
    fun test2_PeerNotReadyPreservesSosInPendingQueue() {
        val dispatcher = MockSosDispatcher()
        val peerC = 300L
        dispatcher.peerStates[peerC] = PeerConnectionState.CONNECTING

        val sosBytes = "SOS_PAYLOAD_TEST_2".toByteArray()
        dispatcher.broadcastSosPacket(sosBytes, listOf(peerC))

        // Verified not sent immediately while CONNECTING
        val sentToC = dispatcher.immediateSentPackets[peerC]
        assertNull(sentToC)

        // Verified preserved in pending list
        val pendingC = dispatcher.pendingPacketsMap[peerC]
        assertNotNull(pendingC)
        assertEquals(1, pendingC!!.size)
        assertArrayEquals(sosBytes, pendingC[0].packetData)
    }

    @Test
    fun test3_PeerBecomesReadyFlushesPendingSos() {
        val dispatcher = MockSosDispatcher()
        val peerC = 300L
        dispatcher.peerStates[peerC] = PeerConnectionState.CONNECTING

        val sosBytes = "SOS_PAYLOAD_TEST_3".toByteArray()
        dispatcher.broadcastSosPacket(sosBytes, listOf(peerC))

        // Verify pending before transition
        assertEquals(1, dispatcher.pendingPacketsMap[peerC]?.size ?: 0)

        // Transition peer to READY
        dispatcher.onPeerStateChangedToReady(peerC)

        // Verified flushed and sent
        val sentToC = dispatcher.immediateSentPackets[peerC]
        assertNotNull(sentToC)
        assertEquals(1, sentToC!!.size)
        assertArrayEquals(sosBytes, sentToC[0])

        // Verified pending queue cleared
        assertNull(dispatcher.pendingPacketsMap[peerC])
    }

    @Test
    fun test4_MixedPeersReadyAndNotReadyHandledWithoutBlocking() {
        val dispatcher = MockSosDispatcher()
        val peerB = 200L // READY
        val peerC = 300L // CONNECTING

        dispatcher.peerStates[peerB] = PeerConnectionState.READY
        dispatcher.peerStates[peerC] = PeerConnectionState.CONNECTING

        val sosBytes = "SOS_PAYLOAD_TEST_4".toByteArray()
        dispatcher.broadcastSosPacket(sosBytes, listOf(peerB, peerC))

        // Peer B receives immediately
        val sentToB = dispatcher.immediateSentPackets[peerB]
        assertNotNull(sentToB)
        assertEquals(1, sentToB!!.size)

        // Peer C queued in pending
        val pendingC = dispatcher.pendingPacketsMap[peerC]
        assertNotNull(pendingC)
        assertEquals(1, pendingC!!.size)

        // Peer C becomes ready later
        dispatcher.onPeerStateChangedToReady(peerC)

        // Peer C receives after state transition
        val sentToC = dispatcher.immediateSentPackets[peerC]
        assertNotNull(sentToC)
        assertEquals(1, sentToC!!.size)
    }

    @Test
    fun test5_NoDuplicateDeliveryToReadyPeer() {
        val dispatcher = MockSosDispatcher()
        val peerB = 200L
        dispatcher.peerStates[peerB] = PeerConnectionState.READY

        val sosBytes = "SOS_PAYLOAD_TEST_5".toByteArray()
        dispatcher.broadcastSosPacket(sosBytes, listOf(peerB))

        // Peer B receives once immediately
        assertEquals(1, dispatcher.immediateSentPackets[peerB]?.size ?: 0)

        // Peer B state refresh -> does not trigger duplicate sending because B was not in pending map
        dispatcher.onPeerStateChangedToReady(peerB)
        assertEquals(1, dispatcher.immediateSentPackets[peerB]?.size ?: 0)
    }

    @Test
    fun test6_MultiHopSosRelayPreserved() {
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val localPeerId = 200L // Relay Node B
        val deduplicationManager = DeduplicationManager()
        val routeTable = RouteTable()
        val relayManager = RelayManager()

        var localReceived: Packet? = null
        var broadcastedPayload: Packet? = null

        val router = MeshRouter(
            localPeerId = localPeerId,
            deduplicationManager = deduplicationManager,
            routeTable = routeTable,
            relayManager = relayManager,
            scope = testScope,
            sendPacketToPeer = { _, _ -> },
            broadcastPacket = { bytes ->
                broadcastedPayload = PacketDecoder.decode(bytes)
            },
            onLocalMessageReceived = { packet -> localReceived = packet },
            onAckReceived = { }
        )

        val header = PacketHeader(
            packetType = PacketType.SOS,
            packetId = 8888L,
            sourceId = 100L, // Node A
            destinationId = 0L, // Broadcast
            ttl = 6,
            payloadLength = 12
        )
        val packet = Packet(header = header, payload = "SOS_MULTI_HOP".toByteArray())

        router.handleIncomingPacket(packet)

        // Allow random 10-220ms jitter coroutine to complete
        Thread.sleep(300)

        // Verified processed locally at relay B
        assertNotNull(localReceived)
        assertEquals(PacketType.SOS, localReceived!!.header.packetType)

        // Verified relayed to neighbors with TTL decremented (6 -> 5)
        assertNotNull(broadcastedPayload)
        assertEquals(5, broadcastedPayload!!.header.ttl.toInt())
        assertEquals(0L, broadcastedPayload!!.header.destinationId)
    }
}
