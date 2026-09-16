package com.connectmesh.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.modes.ChaCha20Poly1305

/**
 * Standard Noise XX Key Exchange Engine (Noise_XX_25519_ChaChaPoly_SHA256)
 * Provides mutual authentication, forward secrecy, and session isolation.
 */
class NoiseXXSession(val localStaticKeyPair: KeyManager.KeyPairX25519) {

    enum class Role { INITIATOR, RESPONDER }
    enum class State { UNINITIALIZED, HANDSHAKE_STEP1, HANDSHAKE_STEP2, HANDSHAKE_STEP3, ESTABLISHED, FAILED }

    var state: State = State.UNINITIALIZED
        private set

    var remoteStaticPubKey: ByteArray? = null
        private set

    private var txKey: ByteArray? = null
    private var rxKey: ByteArray? = null
    private var txNonce: Long = 0L
    private var rxNonce: Long = 0L

    private var ephemeralKeyPair: KeyManager.KeyPairX25519? = null
    private var remoteEphemeralPubKey: ByteArray? = null
    private var chainingKey: ByteArray = "Noise_XX_25519_ChaChaPoly_SHA256".toByteArray()
    private var handshakeHash: ByteArray = chainingKey.clone()

    fun initialize(role: Role) {
        ephemeralKeyPair = KeyManager.generateX25519KeyPair()
        state = if (role == Role.INITIATOR) State.HANDSHAKE_STEP1 else State.HANDSHAKE_STEP2
    }

    /**
     * Initiator Msg 1: -> e (Ephemeralkey)
     */
    fun createHandshakeMsg1(): ByteArray {
        val e = ephemeralKeyPair!!.publicKey
        mixHash(e)
        mixKey(e)
        state = State.HANDSHAKE_STEP2
        return e
    }

    /**
     * Responder processes Msg 1 and creates Msg 2: <- e, ee, s, es
     */
    fun processHandshakeMsg1AndCreateMsg2(msg1: ByteArray): ByteArray {
        remoteEphemeralPubKey = msg1
        mixHash(msg1)
        mixKey(msg1)

        val e = ephemeralKeyPair!!.publicKey
        mixHash(e)
        
        // DH(e, re)
        val ee = KeyManager.calculateECDH(ephemeralKeyPair!!.privateKey, remoteEphemeralPubKey!!)
        mixKey(ee)

        // Encrypt s (local static pubkey)
        val encryptedStatic = encryptWithAd(localStaticKeyPair.publicKey)

        // DH(s, re)
        val es = KeyManager.calculateECDH(localStaticKeyPair.privateKey, remoteEphemeralPubKey!!)
        mixKey(es)

        state = State.HANDSHAKE_STEP3
        return e + encryptedStatic
    }

    /**
     * Initiator processes Msg 2 and creates Msg 3: -> s, se
     */
    fun processHandshakeMsg2AndCreateMsg3(msg2: ByteArray): ByteArray {
        val re = msg2.copyOfRange(0, 32)
        remoteEphemeralPubKey = re
        mixHash(re)

        val ee = KeyManager.calculateECDH(ephemeralKeyPair!!.privateKey, remoteEphemeralPubKey!!)
        mixKey(ee)

        val encryptedStatic = msg2.copyOfRange(32, msg2.size)
        remoteStaticPubKey = decryptWithAd(encryptedStatic)

        val es = KeyManager.calculateECDH(ephemeralKeyPair!!.privateKey, remoteStaticPubKey!!)
        mixKey(es)

        // Encrypt local static
        val encryptedLocalStatic = encryptWithAd(localStaticKeyPair.publicKey)

        val se = KeyManager.calculateECDH(localStaticKeyPair.privateKey, remoteEphemeralPubKey!!)
        mixKey(se)

        // Derive final symmetric keys
        splitKeys(initiator = true)
        state = State.ESTABLISHED
        return encryptedLocalStatic
    }

    /**
     * Responder processes Msg 3
     */
    fun processHandshakeMsg3(msg3: ByteArray) {
        remoteStaticPubKey = decryptWithAd(msg3)

        val se = KeyManager.calculateECDH(ephemeralKeyPair!!.privateKey, remoteStaticPubKey!!)
        mixKey(se)

        splitKeys(initiator = false)
        state = State.ESTABLISHED
    }

    fun encryptPayload(plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        check(state == State.ESTABLISHED) { "Noise session not established" }
        val cipher = ChaCha20Poly1305()
        val nonceBytes = ByteBuffer8(txNonce++)
        cipher.init(true, ParametersWithIV(KeyParameter(txKey), nonceBytes))
        if (aad != null && aad.isNotEmpty()) {
            cipher.processAADBytes(aad, 0, aad.size)
        }
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, len)
        return output
    }

    fun decryptPayload(ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        check(state == State.ESTABLISHED) { "Noise session not established" }
        val cipher = ChaCha20Poly1305()
        val nonceBytes = ByteBuffer8(rxNonce++)
        cipher.init(false, ParametersWithIV(KeyParameter(rxKey), nonceBytes))
        if (aad != null && aad.isNotEmpty()) {
            cipher.processAADBytes(aad, 0, aad.size)
        }
        val output = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
        cipher.doFinal(output, len)
        return output
    }

    fun encryptPayloadWithMacAndAad(plaintext: ByteArray, aad: ByteArray? = null): Pair<ByteArray, ByteArray> {
        val fullCiphertext = encryptPayload(plaintext, aad)
        val cipherOnly = fullCiphertext.copyOfRange(0, fullCiphertext.size - 16)
        val macTag = fullCiphertext.copyOfRange(fullCiphertext.size - 16, fullCiphertext.size)
        return Pair(cipherOnly, macTag)
    }

    fun decryptPayloadWithMacAndAad(ciphertext: ByteArray, macTag: ByteArray, aad: ByteArray? = null): ByteArray? {
        return try {
            val fullCiphertext = ciphertext + macTag
            decryptPayload(fullCiphertext, aad)
        } catch (e: Exception) {
            null
        }
    }

    private fun mixHash(data: ByteArray) {
        val digest = SHA256Digest()
        digest.update(handshakeHash, 0, handshakeHash.size)
        digest.update(data, 0, data.size)
        digest.doFinal(handshakeHash, 0)
    }

    private fun mixKey(inputKeyMaterial: ByteArray) {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(inputKeyMaterial, chainingKey, null))
        val output = ByteArray(64)
        hkdf.generateBytes(output, 0, 64)
        chainingKey = output.copyOfRange(0, 32)
        mixHash(output.copyOfRange(32, 64))
    }

    private fun encryptWithAd(plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        val nonceBytes = ByteBuffer8(0L)
        cipher.init(true, ParametersWithIV(KeyParameter(chainingKey), nonceBytes))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, len)
        mixHash(output)
        return output
    }

    private fun decryptWithAd(ciphertext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        val nonceBytes = ByteBuffer8(0L)
        cipher.init(false, ParametersWithIV(KeyParameter(chainingKey), nonceBytes))
        val output = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
        cipher.doFinal(output, len)
        mixHash(ciphertext)
        return output
    }

    private fun splitKeys(initiator: Boolean) {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(chainingKey, null, null))
        val output = ByteArray(64)
        hkdf.generateBytes(output, 0, 64)
        if (initiator) {
            txKey = output.copyOfRange(0, 32)
            rxKey = output.copyOfRange(32, 64)
        } else {
            rxKey = output.copyOfRange(0, 32)
            txKey = output.copyOfRange(32, 64)
        }
    }

    private fun ByteBuffer8(value: Long): ByteArray {
        val buf = ByteArray(12)
        buf[4] = (value and 0xFF).toByte()
        buf[5] = ((value shr 8) and 0xFF).toByte()
        buf[6] = ((value shr 16) and 0xFF).toByte()
        buf[7] = ((value shr 24) and 0xFF).toByte()
        buf[8] = ((value shr 32) and 0xFF).toByte()
        buf[9] = ((value shr 40) and 0xFF).toByte()
        buf[10] = ((value shr 48) and 0xFF).toByte()
        buf[11] = ((value shr 56) and 0xFF).toByte()
        return buf
    }
}
