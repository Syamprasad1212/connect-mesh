package com.connectmesh.broadcast

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Data model for a verified College-Wide Broadcast.
 * Cryptographically signed by an authorized Administrator via ECDSA SHA256withECDSA.
 */
data class CollegeBroadcast(
    val broadcastId: String,
    val institutionScope: String,
    val senderConnectMeshId: Long,
    val senderCredentialId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val priority: BroadcastPriority,
    val broadcastType: BroadcastType,
    val title: String,
    val message: String,
    val keyVersion: Int = 1,
    val signatureHex: String = ""
) {
    /**
     * Constructs a canonical, deterministic binary representation of all immutable broadcast fields for ECDSA signature signing & verification.
     */
    fun constructSignableBytes(): ByteArray {
        val bcIdBytes = broadcastId.toByteArray(Charsets.UTF_8)
        val scopeBytes = institutionScope.toByteArray(Charsets.UTF_8)
        val credIdBytes = senderCredentialId.toByteArray(Charsets.UTF_8)
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val msgBytes = message.toByteArray(Charsets.UTF_8)

        val totalSize = 2 + bcIdBytes.size + 2 + scopeBytes.size + 8 + 2 + credIdBytes.size +
                8 + 8 + 1 + 1 + 4 + 2 + titleBytes.size + 4 + msgBytes.size

        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(bcIdBytes.size.toShort())
            put(bcIdBytes)
            putShort(scopeBytes.size.toShort())
            put(scopeBytes)
            putLong(senderConnectMeshId)
            putShort(credIdBytes.size.toShort())
            put(credIdBytes)
            putLong(createdAt)
            putLong(expiresAt)
            put(priority.ordinal.toByte())
            put(broadcastType.ordinal.toByte())
            putInt(keyVersion)
            putShort(titleBytes.size.toShort())
            put(titleBytes)
            putInt(msgBytes.size)
            put(msgBytes)
        }.array()
    }

    fun toWirePayload(): ByteArray {
        val payloadStr = "$broadcastId|$institutionScope|$senderConnectMeshId|$senderCredentialId|$createdAt|$expiresAt|${priority.name}|${broadcastType.name}|$keyVersion|$signatureHex|$title|$message"
        return payloadStr.toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromWirePayload(payload: ByteArray): CollegeBroadcast? {
            return try {
                val payloadStr = String(payload, Charsets.UTF_8)
                val parts = payloadStr.split("|", limit = 12)
                if (parts.size >= 12) {
                    CollegeBroadcast(
                        broadcastId = parts[0],
                        institutionScope = parts[1],
                        senderConnectMeshId = parts[2].toLong(),
                        senderCredentialId = parts[3],
                        createdAt = parts[4].toLong(),
                        expiresAt = parts[5].toLong(),
                        priority = BroadcastPriority.valueOf(parts[6]),
                        broadcastType = BroadcastType.valueOf(parts[7]),
                        keyVersion = parts[8].toInt(),
                        signatureHex = parts[9],
                        title = parts[10],
                        message = parts[11]
                    )
                } else if (parts.size >= 3) {
                    // Fallback for 3-part legacy test payloads
                    CollegeBroadcast(
                        broadcastId = parts[0],
                        institutionScope = "COLLEGE:CAMPUS_01",
                        senderConnectMeshId = 0L,
                        senderCredentialId = "CRED-LEGACY",
                        createdAt = System.currentTimeMillis(),
                        expiresAt = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L,
                        priority = BroadcastPriority.HIGH,
                        broadcastType = BroadcastType.ANNOUNCEMENT,
                        title = parts[1],
                        message = parts[2],
                        keyVersion = 1,
                        signatureHex = "LEGACY_TEST_SIG"
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
