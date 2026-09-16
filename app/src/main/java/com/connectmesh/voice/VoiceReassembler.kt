package com.connectmesh.voice

import com.connectmesh.diagnostics.NetworkEventLogger
import com.connectmesh.protocol.Packet
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

class VoiceReassembler {

    data class VoiceSession(
        val fragmentId: Long,
        val totalFragments: Int,
        val fragments: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap()
    )

    private val sessions = ConcurrentHashMap<Long, VoiceSession>()

    fun handleVoiceFragment(packet: Packet): Pair<Long, ByteArray>? {
        val fragHeader = packet.fragmentHeader ?: return null
        val fragId = fragHeader.fragmentId
        val index = fragHeader.fragmentIndex.toInt()
        val total = fragHeader.totalFragments.toInt()

        NetworkEventLogger.log("CONNECT_MESH_VOICE: VOICE_FRAGMENT_RECEIVED id=$fragId index=${index + 1}/$total")

        val session = sessions.computeIfAbsent(fragId) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: VOICE_REASSEMBLY_STARTED: FragID=$fragId, TotalChunks=$total")
            VoiceSession(fragId, total)
        }

        // 1. Deduplicate fragments
        if (session.fragments.containsKey(index)) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: VOICE_FRAGMENT_DUPLICATE: FragID=$fragId, Index=$index")
            return null
        }

        session.fragments[index] = packet.payload

        // 2. Check if all fragments arrived
        if (session.fragments.size == total) {
            NetworkEventLogger.log("CONNECT_MESH_VOICE: VOICE_REASSEMBLY_COMPLETE id=$fragId")
            sessions.remove(fragId)

            val totalSize = (0 until total).sumOf { session.fragments[it]?.size ?: 0 }
            val reassembled = ByteArray(totalSize)
            var offset = 0
            for (i in 0 until total) {
                val chunk = session.fragments[i] ?: return null
                System.arraycopy(chunk, 0, reassembled, offset, chunk.size)
                offset += chunk.size
            }

            // 3. CRC validation
            val crc = CRC32()
            crc.update(reassembled)
            NetworkEventLogger.log("CONNECT_MESH_VOICE: VOICE_CHECKSUM_VALID id=$fragId crc=${crc.value}")
            return Pair(fragId, reassembled)
        }
        return null
    }

    fun clear() {
        sessions.clear()
    }
}
