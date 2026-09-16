package com.connectmesh.relay

enum class MeshNodeRole {
    USER,
    STATIC_RELAY
}

data class StaticRelayConfig(
    val relayId: Long,
    val locationLabel: String,
    val isRelayEnabled: Boolean = true,
    val maxConcurrentConnections: Int = 16,
    val maxForwardQueueSize: Int = 200,
    val maxPacketRatePerSec: Int = 50,
    val createdAt: Long = System.currentTimeMillis()
)
