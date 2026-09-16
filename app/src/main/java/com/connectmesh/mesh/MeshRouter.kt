package com.connectmesh.mesh

import com.connectmesh.diagnostics.NetworkEventLogger
import com.connectmesh.protocol.Packet
import com.connectmesh.protocol.PacketEncoder
import com.connectmesh.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Random

class MeshRouter(
    private val localPeerId: Long,
    private val deduplicationManager: DeduplicationManager,
    private val routeTable: RouteTable,
    private val relayManager: RelayManager,
    private val scope: CoroutineScope,
    private val sendPacketToPeer: (nextHop: Long, packetBytes: ByteArray) -> Unit,
    private val broadcastPacket: (packetBytes: ByteArray) -> Unit,
    private val onLocalMessageReceived: (packet: Packet) -> Unit,
    private val onAckReceived: (originalPacketId: Long) -> Unit,
    private val onSosAckReceived: (originalPacketId: Long, ackSenderId: Long) -> Unit = { _, _ -> }
) {
    private val random = Random()

    fun handleIncomingPacket(packet: Packet) {
        // 1. Deduplication Filter
        if (deduplicationManager.isDuplicateOrAdd(packet.header.packetId)) {
            relayManager.duplicatesSuppressed.incrementAndGet()
            NetworkEventLogger.log("CONNECT_MESH_BLE: DUPLICATE_PACKET: Suppressed packet ${packet.header.packetId}")
            return
        }

        // 2. Destination Matching
        val isForSelf = (packet.header.destinationId == localPeerId)
        val isBroadcast = (packet.header.destinationId == 0L)

        if (isForSelf) {
            processLocalPacket(packet)
        } else if (isBroadcast) {
            processLocalPacket(packet)
            relayBroadcastPacket(packet)
        } else {
            relayUnicastPacket(packet)
        }
    }

    private fun relayBroadcastPacket(packet: Packet) {
        if (packet.header.ttl <= 1) {
            relayManager.packetsDropped.incrementAndGet()
            return
        }

        val updatedTtl = (packet.header.ttl - 1).toByte()
        val updatedHeader = packet.header.copy(ttl = updatedTtl)
        val updatedPacket = packet.copy(header = updatedHeader)
        val encodedBytes = PacketEncoder.encode(updatedPacket)

        val jitterMs = 10L + random.nextInt(210) // 10ms to 220ms jitter
        scope.launch {
            kotlinx.coroutines.delay(jitterMs)
            broadcastPacket(encodedBytes)
            relayManager.packetsRelayed.incrementAndGet()
            NetworkEventLogger.log("CONNECT_MESH_ROUTE: RELAY_BROADCAST: PacketID=${packet.header.packetId}, NewTTL=$updatedTtl")
        }
    }

    private fun relayUnicastPacket(packet: Packet) {
        if (packet.header.ttl > 1) {
            val updatedTtl = (packet.header.ttl - 1).toByte()
            val nextHop = routeTable.getNextHop(packet.header.destinationId)

            if (nextHop != null) {
                val updatedHeader = packet.header.copy(ttl = updatedTtl)
                val updatedPacket = packet.copy(header = updatedHeader)
                val encodedBytes = PacketEncoder.encode(updatedPacket)

                sendPacketToPeer(nextHop, encodedBytes)
                relayManager.packetsRelayed.incrementAndGet()

                when (packet.header.packetType) {
                    PacketType.MESSAGE -> relayManager.textPacketsRelayed.incrementAndGet()
                    PacketType.ACK -> relayManager.ackPacketsRelayed.incrementAndGet()
                    PacketType.VOICE_FRAGMENT -> relayManager.voiceFragmentsRelayed.incrementAndGet()
                    PacketType.FILE_CHUNK -> relayManager.fileChunksRelayed.incrementAndGet()
                    PacketType.ANNOUNCE, PacketType.PING, PacketType.PONG, PacketType.AUTH_REQUEST, PacketType.AUTH_RESPONSE, PacketType.SESSION_INIT, PacketType.SESSION_FINISH, PacketType.CLASSROOM_MSG, PacketType.CLASSROOM_KEY_SYNC, PacketType.COLLEGE_BROADCAST -> relayManager.controlPacketsRelayed.incrementAndGet()
                    else -> {}
                }
                NetworkEventLogger.log("CONNECT_MESH_ROUTE: UNICAST_RELAY PacketID=${packet.header.packetId} Target=0x${packet.header.destinationId.toString(16).uppercase()} via NextHop=0x${nextHop.toString(16).uppercase()} TTL=$updatedTtl")
            } else {
                NetworkEventLogger.log("CONNECT_MESH_ROUTE: NO_ROUTE_NO_FLOOD Dropped packet ${packet.header.packetId} for 0x${packet.header.destinationId.toString(16).uppercase()}")
            }
        } else {
            relayManager.packetsDropped.incrementAndGet()
            NetworkEventLogger.log("CONNECT_MESH_BLE: TTL_EXPIRED: Dropped packet ${packet.header.packetId}")
        }
    }

    private fun processLocalPacket(packet: Packet) {
        when (packet.header.packetType) {
            PacketType.ACK -> {
                relayManager.acksReceived.incrementAndGet()
                val ackedPacketId = AckManager.parseAckOriginalPacketId(packet)
                if (ackedPacketId != null) {
                    onAckReceived(ackedPacketId)
                }
            }
            PacketType.SOS_ACK -> {
                relayManager.sosAcksReceived.incrementAndGet()
                val ackedPacketId = AckManager.parseAckOriginalPacketId(packet)
                if (ackedPacketId != null) {
                    onSosAckReceived(ackedPacketId, packet.header.sourceId)
                }
            }
            PacketType.MESSAGE, PacketType.FRAGMENT, PacketType.VOICE_FRAGMENT, PacketType.SOS,
            PacketType.FILE_START, PacketType.FILE_CHUNK, PacketType.FILE_END, PacketType.FILE_ACK, PacketType.FILE_CANCEL,
            PacketType.AUTH_REQUEST, PacketType.AUTH_RESPONSE, PacketType.SESSION_INIT, PacketType.SESSION_FINISH,
            PacketType.CLASSROOM_MSG, PacketType.CLASSROOM_KEY_SYNC, PacketType.COLLEGE_BROADCAST -> {
                if (packet.header.packetType == PacketType.SOS) {
                    relayManager.sosPacketsReceived.incrementAndGet()
                }
                onLocalMessageReceived(packet)
            }
            PacketType.PING -> {
                val pongHeader = com.connectmesh.protocol.PacketHeader(
                    packetType = PacketType.PONG,
                    packetId = System.nanoTime(),
                    sourceId = localPeerId,
                    destinationId = packet.header.sourceId,
                    payloadLength = 4
                )
                val pongPacket = Packet(header = pongHeader, payload = "PONG".toByteArray())
                val encoded = PacketEncoder.encode(pongPacket)
                val nextHop = routeTable.getNextHop(packet.header.sourceId) ?: packet.header.sourceId
                sendPacketToPeer(nextHop, encoded)
            }
            PacketType.PONG -> {
                NetworkEventLogger.log("CONNECT_MESH_BLE: PONG_RECEIVED from 0x${packet.header.sourceId.toString(16).uppercase()}")
            }
            PacketType.ANNOUNCE -> {
                onLocalMessageReceived(packet)
            }
        }
    }
}
