package com.connectmesh.mesh

import com.connectmesh.protocol.Packet
import com.connectmesh.protocol.PacketHeader
import com.connectmesh.protocol.PacketType
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AckManager {

    fun createAckPacket(
        originalPacketId: Long,
        localPeerId: Long,
        targetPeerId: Long,
        ttl: Byte = BleConstants.DEFAULT_TTL
    ): Packet {
        val ackPayload = ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(originalPacketId)
        }.array()

        val header = PacketHeader(
            packetType = PacketType.ACK,
            packetId = System.nanoTime(),
            sourceId = localPeerId,
            destinationId = targetPeerId,
            ttl = ttl,
            payloadLength = ackPayload.size.toShort()
        )

        return Packet(
            header = header,
            payload = ackPayload
        )
    }

    fun parseAckOriginalPacketId(packet: Packet): Long? {
        if ((packet.header.packetType != PacketType.ACK && packet.header.packetType != PacketType.SOS_ACK) || packet.payload.size < 8) return null
        return ByteBuffer.wrap(packet.payload).apply {
            order(ByteOrder.BIG_ENDIAN)
        }.long
    }
}
