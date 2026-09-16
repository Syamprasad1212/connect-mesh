package com.connectmesh.relay

import com.connectmesh.diagnostics.NetworkEventLogger
import com.connectmesh.mesh.DeduplicationManager
import com.connectmesh.mesh.RelayManager
import com.connectmesh.protocol.Packet
import com.connectmesh.protocol.PacketHeader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Controller for Static Relay Node operation.
 * Manages relay mode, capacity queues, bounded forwarding, and privacy-isolated packet routing.
 * Relays forward opaque payload bytes WITHOUT decrypting text, voice, file, classroom, or broadcast content.
 */
class StaticRelayController(
    val deduplicationManager: DeduplicationManager = DeduplicationManager(),
    val relayManager: RelayManager = RelayManager()
) {
    var nodeRole: MeshNodeRole = MeshNodeRole.USER
        private set

    var relayConfig: StaticRelayConfig? = null
        private set

    private val forwardQueue = ConcurrentLinkedQueue<Packet>()
    val packetsForwarded = AtomicLong(0)
    val startTime = System.currentTimeMillis()

    fun enableStaticRelayMode(config: StaticRelayConfig) {
        this.relayConfig = config
        this.nodeRole = MeshNodeRole.STATIC_RELAY
        NetworkEventLogger.log("CONNECT_MESH_RELAY: MODE_ENABLED id=0x${config.relayId.toString(16).uppercase()} label=${config.locationLabel}")
    }

    fun disableStaticRelayMode() {
        this.nodeRole = MeshNodeRole.USER
        this.relayConfig = null
        forwardQueue.clear()
        NetworkEventLogger.log("CONNECT_MESH_RELAY: MODE_DISABLED")
    }

    fun isStaticRelay(): Boolean = (nodeRole == MeshNodeRole.STATIC_RELAY && (relayConfig?.isRelayEnabled == true))

    enum class ForwardDecision {
        FORWARD,
        SUPPRESSED_DUPLICATE,
        EXPIRED_TTL,
        DROPPED_CAPACITY_FULL,
        REJECTED_INVALID_HEADER
    }

    /**
     * Inspects routing metadata and determines whether to forward the packet across the mesh.
     * IMPERATIVE: Payload bytes are NEVER decrypted or inspected for message text, voice audio, or file data.
     */
    fun inspectAndForwardPacket(packet: Packet): ForwardDecision {
        val config = relayConfig
        if (!isStaticRelay() || config == null) {
            return ForwardDecision.FORWARD // Handled by normal mesh node
        }

        // 1. Basic Packet Header Validation
        if (packet.header.magic != PacketHeader.MAGIC_VALUE || packet.header.version != 0x01.toByte()) {
            relayManager.packetsDropped.incrementAndGet()
            NetworkEventLogger.log("CONNECT_MESH_RELAY: DROPPED reason=INVALID_HEADER packetId=${packet.header.packetId}")
            return ForwardDecision.REJECTED_INVALID_HEADER
        }

        // 2. Anti-Replay & Loop Suppression
        if (deduplicationManager.isDuplicateOrAdd(packet.header.packetId)) {
            relayManager.duplicatesSuppressed.incrementAndGet()
            NetworkEventLogger.log("CONNECT_MESH_RELAY: DUP_SUPPRESSED packetId=${packet.header.packetId} type=${packet.header.packetType}")
            return ForwardDecision.SUPPRESSED_DUPLICATE
        }

        // 3. TTL Expiration Check
        if (packet.header.ttl <= 1) {
            relayManager.packetsDropped.incrementAndGet()
            NetworkEventLogger.log("CONNECT_MESH_RELAY: TTL_EXPIRED packetId=${packet.header.packetId}")
            return ForwardDecision.EXPIRED_TTL
        }

        // 4. Capacity Queue Bounded Check
        if (forwardQueue.size >= config.maxForwardQueueSize) {
            relayManager.packetsDropped.incrementAndGet()
            NetworkEventLogger.log("CONNECT_MESH_RELAY: QUEUE_FULL packetId=${packet.header.packetId} queueSize=${forwardQueue.size}")
            return ForwardDecision.DROPPED_CAPACITY_FULL
        }

        // 5. Enqueue for Forwarding (Opaque Bytes)
        forwardQueue.add(packet)
        packetsForwarded.incrementAndGet()
        relayManager.packetsRelayed.incrementAndGet()

        // Privacy-Safe Telemetry Logging (NO PLAINTEXT, NO AUDIO, NO FILE DATA, NO KEYS)
        NetworkEventLogger.log("CONNECT_MESH_RELAY: PACKET_FORWARDED packetId=${packet.header.packetId} type=${packet.header.packetType} ttl=${packet.header.ttl} payloadLen=${packet.payload.size}")

        return ForwardDecision.FORWARD
    }

    fun pollNextForwardPacket(): Packet? {
        return forwardQueue.poll()
    }

    fun getUptimeMs(): Long {
        return System.currentTimeMillis() - startTime
    }
}
