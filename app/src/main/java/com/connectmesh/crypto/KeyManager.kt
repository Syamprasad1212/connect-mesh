package com.connectmesh.crypto

import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import java.security.SecureRandom

object KeyManager {

    data class KeyPairX25519(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as KeyPairX25519
            return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int {
            return 31 * privateKey.contentHashCode() + publicKey.contentHashCode()
        }
    }

    fun generateX25519KeyPair(): KeyPairX25519 {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()

        val priv = (pair.private as X25519PrivateKeyParameters).encoded
        val pub = (pair.public as X25519PublicKeyParameters).encoded

        return KeyPairX25519(priv, pub)
    }

    fun calculateECDH(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val pubParams = X25519PublicKeyParameters(remotePublicKey, 0)
        agreement.init(privParams)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)
        return sharedSecret
    }
}
