package com.connectmesh

import com.connectmesh.mesh.BleConstants
import com.connectmesh.protocol.*
import org.junit.Assert.*
import org.junit.Test

class PacketEncoderDecoderTest {

    @Test
    fun testBasePacketEncodeDecodeRoundtrip() {
        val payload = "Hello Connect-Mesh".toByteArray(Charsets.UTF_8)
        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = 123456789L,
            sourceId = 0x31B2C3D4E5F60708L,
            destinationId = 0x1122334455667788L,
            ttl = 7,
            payloadLength = payload.size.toShort()
        )
        val macTag = ByteArray(16) { (it + 1).toByte() }
        val packet = Packet(header = header, payload = payload, macTag = macTag)

        val encoded = PacketEncoder.encode(packet)
        assertEquals(PacketHeader.HEADER_SIZE + payload.size + BleConstants.MAC_TAG_SIZE, encoded.size)

        val decoded = PacketDecoder.decode(encoded)
        assertNotNull(decoded)
        assertEquals(PacketType.MESSAGE, decoded!!.header.packetType)
        assertEquals(123456789L, decoded.header.packetId)
        assertEquals(0x31B2C3D4E5F60708L, decoded.header.sourceId)
        assertEquals(0x1122334455667788L, decoded.header.destinationId)
        assertEquals(7.toByte(), decoded.header.ttl)
        assertArrayEquals(payload, decoded.payload)
        assertArrayEquals(macTag, decoded.macTag)
    }

    @Test
    fun testFragmentPacketEncodeDecodeRoundtrip() {
        val fragPayload = "Fragmented Payload Data".toByteArray(Charsets.UTF_8)
        val header = PacketHeader(
            packetType = PacketType.FRAGMENT,
            packetId = 987654321L,
            sourceId = 100L,
            destinationId = 200L,
            payloadLength = fragPayload.size.toShort()
        )
        val fragHeader = FragmentHeader(
            fragmentId = 555L,
            fragmentIndex = 2,
            totalFragments = 5,
            crc32 = 12345
        )
        val packet = Packet(header = header, fragmentHeader = fragHeader, payload = fragPayload)

        val encoded = PacketEncoder.encode(packet)
        val decoded = PacketDecoder.decode(encoded)

        assertNotNull(decoded)
        assertEquals(PacketType.FRAGMENT, decoded!!.header.packetType)
        assertNotNull(decoded.fragmentHeader)
        assertEquals(555L, decoded.fragmentHeader!!.fragmentId)
        assertEquals(2.toShort(), decoded.fragmentHeader!!.fragmentIndex)
        assertEquals(5.toShort(), decoded.fragmentHeader!!.totalFragments)
        assertEquals(12345, decoded.fragmentHeader!!.crc32)
        assertArrayEquals(fragPayload, decoded.payload)
    }

    @Test
    fun testInvalidMagicRejection() {
        val payload = "Bad Magic".toByteArray()
        val header = PacketHeader(
            magic = 0x1234.toShort(),
            packetType = PacketType.MESSAGE,
            packetId = 1L,
            sourceId = 2L,
            destinationId = 3L,
            payloadLength = payload.size.toShort()
        )
        val packet = Packet(header = header, payload = payload)
        val encoded = PacketEncoder.encode(packet)

        val decoded = PacketDecoder.decode(encoded)
        assertNull(decoded)
    }
}
