package com.connectmesh.mesh

import com.connectmesh.diagnostics.NetworkEventLogger
import com.connectmesh.identity.PeerAuthState
import com.connectmesh.identity.PeerIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class PeerManager {
    private val peers = ConcurrentHashMap<Long, PeerIdentity>()
    private val _peersFlow = MutableStateFlow<List<PeerIdentity>>(emptyList())
    val peersFlow: StateFlow<List<PeerIdentity>> = _peersFlow.asStateFlow()

    fun updatePeer(peer: PeerIdentity) {
        if (peer.peerId == 0L) {
            NetworkEventLogger.log("CONNECT_MESH_PEER: REJECTED_PEER reason=ZERO_ID")
            return
        }
        if (peer.hopCount < 1 || peer.hopCount > RouteTable.MAX_MESH_HOPS) {
            NetworkEventLogger.log("CONNECT_MESH_PEER: REJECTED_PEER reason=INVALID_HOP_COUNT peerId=0x${peer.peerId.toString(16).uppercase()} hops=${peer.hopCount}")
            return
        }

        val existing = peers[peer.peerId]
        if (existing != null) {
            val finalNickname = if (peer.nickname.isNotBlank() && !peer.nickname.startsWith("Peer ...")) {
                peer.nickname
            } else {
                existing.nickname
            }
            peers[peer.peerId] = peer.copy(
                nickname = finalNickname,
                lastSeenMs = System.currentTimeMillis(),
                authState = if (peer.authState != PeerAuthState.UNAUTHENTICATED) peer.authState else existing.authState
            )
        } else {
            peers[peer.peerId] = peer
        }
        _peersFlow.value = peers.values.toList()
    }

    fun updatePeerAuthState(peerId: Long, authState: PeerAuthState) {
        val existing = peers[peerId]
        if (existing != null) {
            peers[peerId] = existing.copy(authState = authState)
            _peersFlow.value = peers.values.toList()
            NetworkEventLogger.log("CONNECT_MESH_AUTH: PEER_AUTH_STATE_UPDATED peerId=0x${peerId.toString(16).uppercase()} state=${authState.name}")
        }
    }

    fun updatePeerNickname(peerId: Long, nickname: String) {
        if (peerId == 0L || nickname.isBlank()) return
        val sanitizedNickname = sanitizeNickname(nickname)
        if (sanitizedNickname.isBlank()) return

        val existing = peers[peerId]
        if (existing != null) {
            if (existing.nickname != sanitizedNickname) {
                peers[peerId] = existing.copy(nickname = sanitizedNickname, lastSeenMs = System.currentTimeMillis())
                _peersFlow.value = peers.values.toList()
            }
        } else {
            val newPeer = PeerIdentity(
                peerId = peerId,
                nickname = sanitizedNickname,
                hopCount = 1,
                nextHopPeerId = peerId
            )
            peers[peerId] = newPeer
            _peersFlow.value = peers.values.toList()
        }
    }

    fun removePeer(peerId: Long) {
        val removed = peers.remove(peerId)
        if (removed != null) {
            _peersFlow.value = peers.values.toList()
        }
    }

    fun getPeer(peerId: Long): PeerIdentity? = peers[peerId]

    fun getAllPeers(): List<PeerIdentity> = peers.values.toList()

    fun cleanExpiredPeers(localDeviceId: Long = 0L, timeoutMs: Long = 45_000L) {
        val now = System.currentTimeMillis()
        val invalidOrExpired = peers.values.filter { peer ->
            (now - peer.lastSeenMs > timeoutMs) ||
            (peer.hopCount < 1 || peer.hopCount > RouteTable.MAX_MESH_HOPS) ||
            (peer.peerId == 0L || (localDeviceId != 0L && peer.peerId == localDeviceId))
        }
        invalidOrExpired.forEach { peers.remove(it.peerId) }
        if (invalidOrExpired.isNotEmpty()) {
            _peersFlow.value = peers.values.toList()
            NetworkEventLogger.log("CONNECT_MESH_PEER: CLEANED_EXPIRED_OR_INVALID_PEERS count=${invalidOrExpired.size}")
        }
    }

    private fun sanitizeNickname(raw: String): String {
        return raw.replace(Regex("[\\p{C}\\uFFFD]"), "").trim().take(32)
    }
}
