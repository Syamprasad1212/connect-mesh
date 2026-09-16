package com.connectmesh.protocol

import com.connectmesh.mesh.BleConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketDecoder {

    fun decode(bytes: ByteArray): Packet? {
        if (bytes.size < PacketHeader.HEADER_SIZE + BleConstants.MAC_TAG_SIZE) {
            return null // Buffer too small for header + MAC tag
        }

        try {
            val buffer = ByteBuffer.wrap(bytes).apply {
                order(ByteOrder.BIG_ENDIAN)
            }

            val magic = buffer.short
            if (magic != PacketHeader.MAGIC_VALUE) {
                return null // Bad magic identifier
            }

            val version = buffer.get()
            val typeCode = buffer.get()
            val packetType = PacketType.fromCode(typeCode) ?: return null

            val packetId = buffer.long
            val sourceId = buffer.long
            val destinationId = buffer.long
            val ttl = buffer.get()
            val timestamp = buffer.long
            val flags = buffer.get()
            val rawPayloadLen = buffer.short.toInt() and 0xFFFF

            val expectedRemaining = rawPayloadLen + BleConstants.MAC_TAG_SIZE
            if (buffer.remaining() < expectedRemaining) {
                return null // Incomplete packet data
            }

            val isFragmentType = (packetType == PacketType.FRAGMENT || packetType == PacketType.VOICE_FRAGMENT)
            var fragmentHeader: FragmentHeader? = null
            var actualPayloadSize = rawPayloadLen

            if (isFragmentType && rawPayloadLen >= FragmentHeader.FRAGMENT_HEADER_SIZE) {
                val fragId = buffer.long
                val fragIndex = buffer.short
                val totalFrags = buffer.short
                val crc32 = buffer.int
                fragmentHeader = FragmentHeader(fragId, fragIndex, totalFrags, crc32)
                actualPayloadSize -= FragmentHeader.FRAGMENT_HEADER_SIZE
            }

            val payload = ByteArray(actualPayloadSize)
            buffer.get(payload)

            val macTag = ByteArray(BleConstants.MAC_TAG_SIZE)
            buffer.get(macTag)

            val header = PacketHeader(
                magic = magic,
                version = version,
                packetType = packetType,
                packetId = packetId,
                sourceId = sourceId,
                destinationId = destinationId,
                ttl = ttl,
                timestamp = timestamp,
                flags = flags,
                payloadLength = actualPayloadSize.toShort()
            )

            return Packet(
                header = header,
                fragmentHeader = fragmentHeader,
                payload = payload,
                macTag = macTag
            )
        } catch (e: Exception) {
            return null
        }
    }
}
