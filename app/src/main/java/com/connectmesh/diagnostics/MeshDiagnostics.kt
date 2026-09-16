package com.connectmesh.diagnostics

import com.connectmesh.mesh.RelayManager

data class MeshDiagnostics(
    val isBluetoothEnabled: Boolean = true,
    val isAdvertising: Boolean = true,
    val isScanning: Boolean = true,
    val connectedPeersCount: Int = 0,
    val knownPeersCount: Int = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val packetsRelayed: Long = 0,
    val packetsDropped: Long = 0,
    val duplicatesSuppressed: Long = 0
)
