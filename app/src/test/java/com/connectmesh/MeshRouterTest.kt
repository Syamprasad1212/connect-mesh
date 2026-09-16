package com.connectmesh

import com.connectmesh.mesh.DeduplicationManager
import com.connectmesh.mesh.MeshRouter
import com.connectmesh.mesh.RelayManager
import com.connectmesh.mesh.RouteTable
import com.connectmesh.protocol.Packet
import com.connectmesh.protocol.PacketHeader
import com.connectmesh.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class MeshRouterTest {

    @Test
    fun testLocalMessageConsumption() {
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val localPeerId = 100L
        val deduplicationManager = DeduplicationManager()
        val routeTable = RouteTable()
        val relayManager = RelayManager()

        var localReceived: Packet? = null

        val router = MeshRouter(
            localPeerId = localPeerId,
            deduplicationManager = deduplicationManager,
            routeTable = routeTable,
            relayManager = relayManager,
            scope = testScope,
            sendPacketToPeer = { _, _ -> },
            broadcastPacket = { _ -> },
            onLocalMessageReceived = { packet -> localReceived = packet },
            onAckReceived = { }
        )

        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = 5555L,
            sourceId = 200L,
            destinationId = localPeerId,
            ttl = 7,
            payloadLength = 7
        )
        val packet = Packet(header = header, payload = "Hello C".toByteArray())

        router.handleIncomingPacket(packet)

        assertNotNull(localReceived)
        assertEquals(5555L, localReceived!!.header.packetId)
    }

    @Test
    fun testTtlZeroDrop() {
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val localPeerId = 100L
        val deduplicationManager = DeduplicationManager()
        val routeTable = RouteTable()
        val relayManager = RelayManager()

        val router = MeshRouter(
            localPeerId = localPeerId,
            deduplicationManager = deduplicationManager,
            routeTable = routeTable,
            relayManager = relayManager,
            scope = testScope,
            sendPacketToPeer = { _, _ -> },
            broadcastPacket = { _ -> },
            onLocalMessageReceived = { },
            onAckReceived = { }
        )

        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = 9999L,
            sourceId = 200L,
            destinationId = 300L,
            ttl = 1, // Expired TTL for forwarding
            payloadLength = 4
        )
        val packet = Packet(header = header, payload = "Test".toByteArray())

        router.handleIncomingPacket(packet)

        assertEquals(1L, relayManager.packetsDropped.get())
    }
}
