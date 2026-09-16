package com.connectmesh.protocol

import com.connectmesh.mesh.BleConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketEncoder {

    fun encode(packet: Packet): ByteArray {
        val hasFragment = packet.fragmentHeader != null
        val fragHeaderSize = if (hasFragment) FragmentHeader.FRAGMENT_HEADER_SIZE else 0
        val totalSize = PacketHeader.HEADER_SIZE + fragHeaderSize + packet.payload.size + BleConstants.MAC_TAG_SIZE

        val buffer = ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(packet.header.magic)
            put(packet.header.version)
            put(packet.header.packetType.code)
            putLong(packet.header.packetId)
            putLong(packet.header.sourceId)
            putLong(packet.header.destinationId)
            put(packet.header.ttl)
            putLong(packet.header.timestamp)
            put(packet.header.flags)
            putShort((packet.payload.size + fragHeaderSize).toShort())

            packet.fragmentHeader?.let { frag ->
                putLong(frag.fragmentId)
                putShort(frag.fragmentIndex)
                putShort(frag.totalFragments)
                putInt(frag.crc32)
            }

            put(packet.payload)
            if (packet.macTag.size == BleConstants.MAC_TAG_SIZE) {
                put(packet.macTag)
            } else {
                put(ByteArray(BleConstants.MAC_TAG_SIZE))
            }
        }

        return buffer.array()
    }
}
