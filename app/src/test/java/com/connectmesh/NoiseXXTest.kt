package com.connectmesh

import com.connectmesh.crypto.KeyManager
import com.connectmesh.crypto.NoiseXXSession
import org.junit.Assert.*
import org.junit.Test

class NoiseXXTest {

    @Test
    fun testNoiseXXHandshakeAndEncryptedTransport() {
        val staticA = KeyManager.generateX25519KeyPair()
        val staticB = KeyManager.generateX25519KeyPair()

        val sessionA = NoiseXXSession(staticA)
        val sessionB = NoiseXXSession(staticB)

        sessionA.initialize(NoiseXXSession.Role.INITIATOR)
        sessionB.initialize(NoiseXXSession.Role.RESPONDER)

        // Step 1: Initiator -> Responder (msg1)
        val msg1 = sessionA.createHandshakeMsg1()
        assertTrue(msg1.isNotEmpty())

        // Step 2: Responder processes msg1 and creates msg2
        val msg2 = sessionB.processHandshakeMsg1AndCreateMsg2(msg1)
        assertTrue(msg2.isNotEmpty())

        // Step 3: Initiator processes msg2 and creates msg3
        val msg3 = sessionA.processHandshakeMsg2AndCreateMsg3(msg2)
        assertTrue(msg3.isNotEmpty())
        assertEquals(NoiseXXSession.State.ESTABLISHED, sessionA.state)

        // Responder processes msg3
        sessionB.processHandshakeMsg3(msg3)
        assertEquals(NoiseXXSession.State.ESTABLISHED, sessionB.state)

        // Verify mutual identity authentication
        assertArrayEquals(staticB.publicKey, sessionA.remoteStaticPubKey)
        assertArrayEquals(staticA.publicKey, sessionB.remoteStaticPubKey)

        // Encrypted transport test: A -> B
        val plaintextA = "Secret message from A to B".toByteArray(Charsets.UTF_8)
        val ciphertextA = sessionA.encryptPayload(plaintextA)
        assertFalse(plaintextA.contentEquals(ciphertextA))

        val decryptedB = sessionB.decryptPayload(ciphertextA)
        assertArrayEquals(plaintextA, decryptedB)

        // Encrypted transport test: B -> A
        val plaintextB = "Reply secret from B to A".toByteArray(Charsets.UTF_8)
        val ciphertextB = sessionB.encryptPayload(plaintextB)
        val decryptedA = sessionA.decryptPayload(ciphertextB)
        assertArrayEquals(plaintextB, decryptedA)
    }
}
