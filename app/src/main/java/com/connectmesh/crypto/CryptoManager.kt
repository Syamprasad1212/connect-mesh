package com.connectmesh.crypto

import android.content.Context

class CryptoManager(context: Context) {
    val staticKeyPair: KeyManager.KeyPairX25519 = KeyManager.generateX25519KeyPair()
    private val sessions = mutableMapOf<Long, NoiseXXSession>()

    fun getOrCreateSession(peerId: Long, role: NoiseXXSession.Role): NoiseXXSession {
        return sessions.getOrPut(peerId) {
            NoiseXXSession(staticKeyPair).apply {
                initialize(role)
            }
        }
    }

    fun removeSession(peerId: Long) {
        sessions.remove(peerId)
    }
}
