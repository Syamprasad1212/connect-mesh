package com.connectmesh.protocol

data class Packet(
    val header: PacketHeader,
    val fragmentHeader: FragmentHeader? = null,
    val payload: ByteArray,
    val macTag: ByteArray = ByteArray(16) // Poly1305 MAC tag
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet

        if (header != other.header) return false
        if (fragmentHeader != other.fragmentHeader) return false
        if (!payload.contentEquals(other.payload)) return false
        if (!macTag.contentEquals(other.macTag)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + (fragmentHeader?.hashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + macTag.contentHashCode()
        return result
    }
}
