package com.connectmesh.crypto

import com.connectmesh.diagnostics.NetworkEventLogger
import java.util.concurrent.ConcurrentHashMap

object SessionManager {

    private val activeSessions = ConcurrentHashMap<Long, NoiseXXSession>()

    fun getSession(peerId: Long): NoiseXXSession? = activeSessions[peerId]

    fun getEstablishedSession(peerId: Long): NoiseXXSession? {
        val s = activeSessions[peerId]
        return if (s != null && s.state == NoiseXXSession.State.ESTABLISHED) s else null
    }

    fun hasEstablishedSession(peerId: Long): Boolean {
        return activeSessions[peerId]?.state == NoiseXXSession.State.ESTABLISHED
    }

    fun initiateSession(peerId: Long, localStaticKeyPair: KeyManager.KeyPairX25519): ByteArray {
        val session = NoiseXXSession(localStaticKeyPair).apply {
            initialize(NoiseXXSession.Role.INITIATOR)
        }
        activeSessions[peerId] = session
        val msg1 = session.createHandshakeMsg1()
        NetworkEventLogger.log("CONNECT_MESH_SESSION: HANDSHAKE_INITIATED peerId=0x${peerId.toString(16).uppercase()}")
        return msg1
    }

    fun handleSessionInit(peerId: Long, localStaticKeyPair: KeyManager.KeyPairX25519, msg1: ByteArray): ByteArray {
        val session = NoiseXXSession(localStaticKeyPair).apply {
            initialize(NoiseXXSession.Role.RESPONDER)
        }
        activeSessions[peerId] = session
        val msg2 = session.processHandshakeMsg1AndCreateMsg2(msg1)
        NetworkEventLogger.log("CONNECT_MESH_SESSION: HANDSHAKE_RESP_MSG2_CREATED peerId=0x${peerId.toString(16).uppercase()}")
        return msg2
    }

    fun handleSessionFinish(peerId: Long, msgPayload: ByteArray): ByteArray? {
        val session = activeSessions[peerId] ?: return null
        return when (session.state) {
            NoiseXXSession.State.HANDSHAKE_STEP2 -> {
                val msg3 = session.processHandshakeMsg2AndCreateMsg3(msgPayload)
                NetworkEventLogger.log("CONNECT_MESH_SESSION: SESSION_ESTABLISHED_INITIATOR peerId=0x${peerId.toString(16).uppercase()}")
                msg3
            }
            NoiseXXSession.State.HANDSHAKE_STEP3 -> {
                session.processHandshakeMsg3(msgPayload)
                NetworkEventLogger.log("CONNECT_MESH_SESSION: SESSION_ESTABLISHED_RESPONDER peerId=0x${peerId.toString(16).uppercase()}")
                null
            }
            else -> null
        }
    }

    fun invalidateSession(peerId: Long) {
        val removed = activeSessions.remove(peerId)
        if (removed != null) {
            NetworkEventLogger.log("CONNECT_MESH_SESSION: SESSION_INVALIDATED peerId=0x${peerId.toString(16).uppercase()}")
        }
    }

    fun clearAllSessions() {
        activeSessions.clear()
        NetworkEventLogger.log("CONNECT_MESH_SESSION: ALL_SESSIONS_CLEARED")
    }
}
