package com.connectmesh.identity

enum class PeerAuthState {
    UNAUTHENTICATED,
    AUTHENTICATING,
    AUTHENTICATED,
    AUTHENTICATION_FAILED
}

data class PeerIdentity(
    val peerId: Long,
    val nickname: String,
    val bleAddress: String = "",
    val hopCount: Int = 1,
    val nextHopPeerId: Long = peerId,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val rssi: Int = 0,
    val authState: PeerAuthState = PeerAuthState.UNAUTHENTICATED
)
