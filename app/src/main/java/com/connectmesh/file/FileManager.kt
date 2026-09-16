package com.connectmesh.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.connectmesh.diagnostics.NetworkEventLogger
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object FileManager {

    const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB initial limit
    const val DEFAULT_CHUNK_SIZE = 384 // 384 bytes payload fits within single GATT packet

    fun getMimeTypeFromExtension(ext: String): String {
        return when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "amr" -> "audio/amr"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }

    enum class Status {
        PREPARING,
        SENDING,
        WAITING_FOR_ACK,
        RECEIVING,
        MISSING_CHUNKS,
        REASSEMBLING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    data class FileMetadata(
        val transferId: Long,
        val senderId: Long,
        val recipientId: Long,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val totalChunks: Int,
        val chunkSize: Int,
        val sha256Hex: String
    )

    data class TransferState(
        val transferId: Long,
        val metadata: FileMetadata,
        var status: Status,
        var chunksTransferred: Int = 0,
        var bytesTransferred: Long = 0L,
        val isSelf: Boolean,
        var localFilePath: String? = null,
        val receivedChunkIndices: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    ) {
        val progressPercentage: Int
            get() = if (metadata.totalChunks > 0) {
                ((chunksTransferred.toFloat() / metadata.totalChunks.toFloat()) * 100).toInt().coerceIn(0, 100)
            } else 0
    }

    fun getFileDetailsFromUri(context: Context, uri: Uri): Pair<String, Long>? {
        var fileName: String? = null
        var fileSize: Long = -1L
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx != -1) fileName = c.getString(nameIdx)
                    if (sizeIdx != -1 && !c.isNull(sizeIdx)) fileSize = c.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: ERROR_QUERYING_URI: ${e.message}")
        }

        if (fileName == null) {
            fileName = uri.lastPathSegment ?: "shared_file"
        }
        if (fileSize <= 0) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                pfd?.use { fileSize = it.statSize }
            } catch (e: Exception) {}
        }

        return if (fileSize > 0) Pair(fileName!!, fileSize) else null
    }

    fun calculateSha256(context: Context, uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val buffer = ByteArray(8192)
            var read: Int
            inputStream.use { stream ->
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: ERROR_CALCULATING_SHA256: ${e.message}")
            null
        }
    }

    fun calculateSha256ForFile(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val inputStream = file.inputStream()
            val buffer = ByteArray(8192)
            var read: Int
            inputStream.use { stream ->
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: ERROR_CALCULATING_FILE_SHA256: ${e.message}")
            null
        }
    }

    fun encodeFileStartPayload(metadata: FileMetadata): ByteArray {
        val fnBytes = metadata.fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = metadata.mimeType.toByteArray(Charsets.UTF_8)
        val shaBytes = metadata.sha256Hex.toByteArray(Charsets.UTF_8)

        val totalSize = 8 + 8 + 4 + 4 + 2 + shaBytes.size + 2 + fnBytes.size + 2 + mimeBytes.size
        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(metadata.transferId)
            putLong(metadata.fileSize)
            putInt(metadata.totalChunks)
            putInt(metadata.chunkSize)
            putShort(shaBytes.size.toShort())
            put(shaBytes)
            putShort(fnBytes.size.toShort())
            put(fnBytes)
            putShort(mimeBytes.size.toShort())
            put(mimeBytes)
        }.array()
    }

    fun decodeFileStartPayload(payload: ByteArray, senderId: Long, recipientId: Long): FileMetadata? {
        return try {
            val buf = ByteBuffer.wrap(payload).apply { order(ByteOrder.BIG_ENDIAN) }
            val transferId = buf.long
            val fileSize = buf.long
            val totalChunks = buf.int
            val chunkSize = buf.int

            val shaLen = buf.short.toInt()
            val shaBytes = ByteArray(shaLen)
            buf.get(shaBytes)
            val sha256Hex = String(shaBytes, Charsets.UTF_8)

            val fnLen = buf.short.toInt()
            val fnBytes = ByteArray(fnLen)
            buf.get(fnBytes)
            val fileName = String(fnBytes, Charsets.UTF_8)

            val mimeLen = buf.short.toInt()
            val mimeBytes = ByteArray(mimeLen)
            buf.get(mimeBytes)
            val mimeType = String(mimeBytes, Charsets.UTF_8)

            FileMetadata(
                transferId = transferId,
                senderId = senderId,
                recipientId = recipientId,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = fileSize,
                totalChunks = totalChunks,
                chunkSize = chunkSize,
                sha256Hex = sha256Hex
            )
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: DECODE_START_ERROR: ${e.message}")
            null
        }
    }

    fun encodeFileChunkPayload(transferId: Long, chunkIndex: Int, totalChunks: Int, chunkData: ByteArray): ByteArray {
        val totalSize = 8 + 4 + 4 + chunkData.size
        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(transferId)
            putInt(chunkIndex)
            putInt(totalChunks)
            put(chunkData)
        }.array()
    }

    data class DecodedChunk(
        val transferId: Long,
        val chunkIndex: Int,
        val totalChunks: Int,
        val chunkData: ByteArray
    )

    fun decodeFileChunkPayload(payload: ByteArray): DecodedChunk? {
        return try {
            val buf = ByteBuffer.wrap(payload).apply { order(ByteOrder.BIG_ENDIAN) }
            val transferId = buf.long
            val chunkIndex = buf.int
            val totalChunks = buf.int
            val chunkData = ByteArray(buf.remaining())
            buf.get(chunkData)
            DecodedChunk(transferId, chunkIndex, totalChunks, chunkData)
        } catch (e: Exception) {
            null
        }
    }

    fun encodeFileAckPayload(transferId: Long, statusByte: Byte, missingIndices: List<Int>): ByteArray {
        val totalSize = 8 + 1 + 4 + (missingIndices.size * 4)
        return ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(transferId)
            put(statusByte)
            putInt(missingIndices.size)
            missingIndices.forEach { putInt(it) }
        }.array()
    }

    data class DecodedAck(
        val transferId: Long,
        val statusByte: Byte,
        val missingIndices: List<Int>
    )

    fun decodeFileAckPayload(payload: ByteArray): DecodedAck? {
        return try {
            val buf = ByteBuffer.wrap(payload).apply { order(ByteOrder.BIG_ENDIAN) }
            val transferId = buf.long
            val statusByte = buf.get()
            val count = buf.int
            val list = mutableListOf<Int>()
            for (i in 0 until count) {
                if (buf.remaining() >= 4) list.add(buf.int)
            }
            DecodedAck(transferId, statusByte, list)
        } catch (e: Exception) {
            null
        }
    }
}
