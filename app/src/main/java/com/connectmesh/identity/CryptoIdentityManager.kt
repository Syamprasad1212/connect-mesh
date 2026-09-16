package com.connectmesh.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.connectmesh.diagnostics.NetworkEventLogger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest

class CryptoIdentityManager private constructor() {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "connect_mesh_identity_key"

        @Volatile
        private var instance: CryptoIdentityManager? = null

        fun getInstance(): CryptoIdentityManager {
            return instance ?: synchronized(this) {
                instance ?: CryptoIdentityManager().also { instance = it }
            }
        }
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    val publicKey: ByteArray
    val connectMeshId: Long

    init {
        ensureKeyExists()
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        publicKey = entry.certificate.publicKey.encoded
        connectMeshId = deriveConnectMeshId(publicKey)
        NetworkEventLogger.log("CONNECT_MESH_IDENTITY: Cryptographic identity initialized (ID=0x${connectMeshId.toString(16).uppercase()}, PubKeyBytes=${publicKey.size})")
    }

    @Synchronized
    private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            try {
                val keyPairGenerator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    KEYSTORE_PROVIDER
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .build()

                keyPairGenerator.initialize(spec)
                keyPairGenerator.generateKeyPair()
                NetworkEventLogger.log("CONNECT_MESH_IDENTITY: Hardware-backed EC keypair generated in Android Keystore")
            } catch (e: Exception) {
                NetworkEventLogger.log("CONNECT_MESH_IDENTITY: ERROR generating Keystore keypair: ${e.message}")
            }
        }
    }

    fun deriveConnectMeshId(pubKey: ByteArray): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pubKey)
        var id = ByteBuffer.wrap(hash).long
        if (id == 0L) id = 1L
        return id
    }

    fun getPublicIdentity(): ByteArray {
        return publicKey.clone()
    }

    fun hasIdentity(): Boolean {
        return keyStore.containsAlias(KEY_ALIAS)
    }
}
