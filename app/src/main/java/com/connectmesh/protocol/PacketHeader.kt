package com.connectmesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PacketHeader(
    val magic: Short = 0x434D.toShort(), // "CM"
    val version: Byte = 0x01.toByte(),
    val packetType: PacketType,
    val packetId: Long,
    val sourceId: Long,
    val destinationId: Long, // 0L for broadcast
    val ttl: Byte = 7.toByte(),
    val timestamp: Long = System.currentTimeMillis(),
    val flags: Byte = 0x00.toByte(),
    val payloadLength: Short
) {
    companion object {
        const val MAGIC_VALUE: Short = 0x434D.toShort()
        const val HEADER_SIZE: Int = 40
    }

    /**
     * Constructs canonical 37-byte Additional Authenticated Data (AAD) for AEAD encryption.
     * Authenticates all immutable header fields (magic, version, packetType, packetId, sourceId, destinationId, flags, timestamp).
     * Excludes mutable ttl (decremented per hop) so multi-hop routing remains 100% operational.
     */
    fun constructAad(): ByteArray {
        return ByteBuffer.allocate(37).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(magic)
            put(version)
            put(packetType.code)
            putLong(packetId)
            putLong(sourceId)
            putLong(destinationId)
            put(flags)
            putLong(timestamp)
        }.array()
    }
}
