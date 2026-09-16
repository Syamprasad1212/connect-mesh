package com.connectmesh.mesh

enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    MTU_NEGOTIATING,
    READY,
    DISCONNECTING,
    ERROR
}
