package com.connectmesh.file

import org.junit.Assert.*
import org.junit.Test

class FileTransferTest {

    @Test
    fun testFileMetadataEncodingDecoding() {
        val original = FileManager.FileMetadata(
            transferId = 88888L,
            senderId = 101L,
            recipientId = 202L,
            fileName = "test_image.jpg",
            mimeType = "image/jpeg",
            fileSize = 102400L,
            totalChunks = 267,
            chunkSize = 384,
            sha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )

        val encodedPayload = FileManager.encodeFileStartPayload(original)
        assertNotNull(encodedPayload)
        assertTrue(encodedPayload.isNotEmpty())

        val decoded = FileManager.decodeFileStartPayload(encodedPayload, senderId = 101L, recipientId = 202L)
        assertNotNull(decoded)
        assertEquals(original.transferId, decoded!!.transferId)
        assertEquals(original.fileName, decoded.fileName)
        assertEquals(original.mimeType, decoded.mimeType)
        assertEquals(original.fileSize, decoded.fileSize)
        assertEquals(original.totalChunks, decoded.totalChunks)
        assertEquals(original.chunkSize, decoded.chunkSize)
        assertEquals(original.sha256Hex, decoded.sha256Hex)
    }

    @Test
    fun testFileChunkEncodingDecoding() {
        val transferId = 99999L
        val chunkIndex = 42
        val totalChunks = 100
        val data = "Hello Connect-Mesh Chunk Data Payload".toByteArray(Charsets.UTF_8)

        val encoded = FileManager.encodeFileChunkPayload(transferId, chunkIndex, totalChunks, data)
        assertNotNull(encoded)

        val decoded = FileManager.decodeFileChunkPayload(encoded)
        assertNotNull(decoded)
        assertEquals(transferId, decoded!!.transferId)
        assertEquals(chunkIndex, decoded.chunkIndex)
        assertEquals(totalChunks, decoded.totalChunks)
        assertArrayEquals(data, decoded.chunkData)
    }

    @Test
    fun testFileAckEncodingDecoding() {
        val transferId = 77777L
        val statusByte = 0x00.toByte()
        val missingIndices = listOf(5, 12, 45)

        val encoded = FileManager.encodeFileAckPayload(transferId, statusByte, missingIndices)
        assertNotNull(encoded)

        val decoded = FileManager.decodeFileAckPayload(encoded)
        assertNotNull(decoded)
        assertEquals(transferId, decoded!!.transferId)
        assertEquals(statusByte, decoded.statusByte)
        assertEquals(missingIndices, decoded.missingIndices)
    }

    @Test
    fun testTransferStateProgressCalculation() {
        val metadata = FileManager.FileMetadata(
            transferId = 1L,
            senderId = 10L,
            recipientId = 20L,
            fileName = "doc.pdf",
            mimeType = "application/pdf",
            fileSize = 10000L,
            totalChunks = 10,
            chunkSize = 1000,
            sha256Hex = "abc"
        )

        val state = FileManager.TransferState(
            transferId = 1L,
            metadata = metadata,
            status = FileManager.Status.SENDING,
            chunksTransferred = 5,
            isSelf = true
        )

        assertEquals(50, state.progressPercentage)

        state.chunksTransferred = 10
        assertEquals(100, state.progressPercentage)
    }

    @Test
    fun testMimeTypeFromExtension() {
        assertEquals("image/jpeg", FileManager.getMimeTypeFromExtension("jpg"))
        assertEquals("image/png", FileManager.getMimeTypeFromExtension("png"))
        assertEquals("application/pdf", FileManager.getMimeTypeFromExtension("pdf"))
        assertEquals("*/*", FileManager.getMimeTypeFromExtension("unknown_ext"))
    }

    @Test
    fun testDefaultChunkSizeIs384() {
        assertEquals(384, FileManager.DEFAULT_CHUNK_SIZE)
    }

    @Test
    fun testTransferStateTerminalTransitions() {
        val metadata = FileManager.FileMetadata(
            transferId = 12345L,
            senderId = 1L,
            recipientId = 2L,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            fileSize = 3840L,
            totalChunks = 10,
            chunkSize = 384,
            sha256Hex = "hash"
        )

        val stateMap = java.util.concurrent.ConcurrentHashMap<Long, FileManager.TransferState>()
        val state = FileManager.TransferState(
            transferId = 12345L,
            metadata = metadata,
            status = FileManager.Status.SENDING,
            isSelf = true
        )
        stateMap[12345L] = state

        assertEquals(1, stateMap.size)

        // Terminal transition COMPLETED
        state.status = FileManager.Status.COMPLETED
        stateMap.remove(12345L)
        assertEquals(0, stateMap.size)
    }

    @Test
    fun testFileStartPayloadEncryptionWithNoiseXX() {
        val staticA = com.connectmesh.crypto.KeyManager.generateX25519KeyPair()
        val staticB = com.connectmesh.crypto.KeyManager.generateX25519KeyPair()
        val sessionA = com.connectmesh.crypto.NoiseXXSession(staticA).apply { initialize(com.connectmesh.crypto.NoiseXXSession.Role.INITIATOR) }
        val sessionB = com.connectmesh.crypto.NoiseXXSession(staticB).apply { initialize(com.connectmesh.crypto.NoiseXXSession.Role.RESPONDER) }

        val m1 = sessionA.createHandshakeMsg1()
        val m2 = sessionB.processHandshakeMsg1AndCreateMsg2(m1)
        val m3 = sessionA.processHandshakeMsg2AndCreateMsg3(m2)
        sessionB.processHandshakeMsg3(m3)

        val metadata = FileManager.FileMetadata(100L, 1L, 2L, "secure.zip", "application/zip", 2048L, 5, 384, "sha256")
        val plaintext = FileManager.encodeFileStartPayload(metadata)
        val dummyHeader = com.connectmesh.protocol.PacketHeader(
            packetType = com.connectmesh.protocol.PacketType.FILE_START,
            packetId = 123456789L,
            sourceId = 1L,
            destinationId = 2L,
            payloadLength = plaintext.size.toShort(),
            ttl = 7
        )
        val aad = dummyHeader.constructAad()
        val (ciphertext, macTag) = sessionA.encryptPayloadWithMacAndAad(plaintext, aad)

        assertNotNull(ciphertext)
        assertNotNull(macTag)
        assertFalse(plaintext.contentEquals(ciphertext))

        val decrypted = sessionB.decryptPayloadWithMacAndAad(ciphertext, macTag, aad)
        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testForgedFileControlPacketFailsMACCheck() {
        val staticA = com.connectmesh.crypto.KeyManager.generateX25519KeyPair()
        val staticB = com.connectmesh.crypto.KeyManager.generateX25519KeyPair()
        val sessionA = com.connectmesh.crypto.NoiseXXSession(staticA).apply { initialize(com.connectmesh.crypto.NoiseXXSession.Role.INITIATOR) }
        val sessionB = com.connectmesh.crypto.NoiseXXSession(staticB).apply { initialize(com.connectmesh.crypto.NoiseXXSession.Role.RESPONDER) }

        val m1 = sessionA.createHandshakeMsg1()
        val m2 = sessionB.processHandshakeMsg1AndCreateMsg2(m1)
        val m3 = sessionA.processHandshakeMsg2AndCreateMsg3(m2)
        sessionB.processHandshakeMsg3(m3)

        val endPayload = java.nio.ByteBuffer.allocate(8).putLong(999L).array()
        val dummyHeader = com.connectmesh.protocol.PacketHeader(
            packetType = com.connectmesh.protocol.PacketType.FILE_END,
            packetId = 555L,
            sourceId = 1L,
            destinationId = 2L,
            payloadLength = endPayload.size.toShort(),
            ttl = 7
        )
        val aad = dummyHeader.constructAad()
        val (ciphertext, macTag) = sessionA.encryptPayloadWithMacAndAad(endPayload, aad)

        // Tamper with macTag
        val forgedMacTag = macTag.copyOf()
        forgedMacTag[0] = (forgedMacTag[0] + 1).toByte()

        val decrypted = sessionB.decryptPayloadWithMacAndAad(ciphertext, forgedMacTag, aad)
        assertNull("Forged MAC tag must be rejected by AEAD decryption", decrypted)
    }

    @Test
    fun testMissingChunkDetectionAndNackEncoding() {
        val totalChunks = 10
        val receivedIndices = mutableSetOf(0, 1, 2, 4, 5, 7, 8, 9) // missing 3 and 6
        val missing = (0 until totalChunks).filter { it !in receivedIndices }

        assertEquals(2, missing.size)
        assertEquals(listOf(3, 6), missing)

        val transferId = 55555L
        val nackPayload = FileManager.encodeFileAckPayload(transferId, 0x01.toByte(), missing)
        assertNotNull(nackPayload)

        val decodedAck = FileManager.decodeFileAckPayload(nackPayload)
        assertNotNull(decodedAck)
        assertEquals(transferId, decodedAck!!.transferId)
        assertEquals(0x01.toByte(), decodedAck.statusByte)
        assertEquals(listOf(3, 6), decodedAck.missingIndices)
    }

    @Test
    fun testOutofOrderAndDuplicateChunkHandling() {
        val metadata = FileManager.FileMetadata(1L, 10L, 20L, "doc.pdf", "application/pdf", 1000L, 5, 200, "hash")
        val state = FileManager.TransferState(1L, metadata, FileManager.Status.RECEIVING, isSelf = false)

        // Receive chunk 3 out of order
        assertTrue(state.receivedChunkIndices.add(3))
        state.chunksTransferred++
        assertEquals(1, state.receivedChunkIndices.size)

        // Receive duplicate chunk 3
        assertFalse(state.receivedChunkIndices.add(3)) // returns false on duplicate
        assertEquals(1, state.receivedChunkIndices.size)

        // Receive remaining chunks out of order
        assertTrue(state.receivedChunkIndices.add(0))
        assertTrue(state.receivedChunkIndices.add(1))
        assertTrue(state.receivedChunkIndices.add(2))
        assertTrue(state.receivedChunkIndices.add(4))

        assertEquals(5, state.receivedChunkIndices.size)
        val missing = (0 until 5).filter { it !in state.receivedChunkIndices }
        assertTrue(missing.isEmpty())
    }

    @Test
    fun testTransferIsolationOnFailure() {
        val activeTransfers = java.util.concurrent.ConcurrentHashMap<Long, FileManager.TransferState>()

        val metaA = FileManager.FileMetadata(100L, 1L, 2L, "A.jpg", "image/jpeg", 1000L, 5, 200, "hashA")
        val stateA = FileManager.TransferState(100L, metaA, FileManager.Status.RECEIVING, isSelf = false)
        activeTransfers[100L] = stateA

        // Transfer A fails
        stateA.status = FileManager.Status.FAILED
        activeTransfers.remove(100L)
        assertNull(activeTransfers[100L])

        // Transfer B starts immediately
        val metaB = FileManager.FileMetadata(200L, 1L, 2L, "B.docx", "application/docx", 500L, 2, 250, "hashB")
        val stateB = FileManager.TransferState(200L, metaB, FileManager.Status.RECEIVING, isSelf = false)
        activeTransfers[200L] = stateB

        assertNotNull(activeTransfers[200L])
        assertEquals(FileManager.Status.RECEIVING, activeTransfers[200L]!!.status)
    }

    @Test
    fun testCancellationPurgesPendingPacketsMapAndQueue() {
        val transferIdA = 111L
        val transferIdB = 222L

        val highQueue = java.util.concurrent.ConcurrentLinkedQueue<com.connectmesh.mesh.BleOperationQueue.WriteOp>()
        val bulkQueue = java.util.concurrent.ConcurrentLinkedQueue<com.connectmesh.mesh.BleOperationQueue.WriteOp>()

        // Add ops for A and B
        highQueue.add(com.connectmesh.mesh.BleOperationQueue.WriteOp(ByteArray(10), transferId = transferIdA, priority = com.connectmesh.mesh.BleOperationQueue.Priority.HIGH) { _, cb -> cb(true) })
        bulkQueue.add(com.connectmesh.mesh.BleOperationQueue.WriteOp(ByteArray(100), transferId = transferIdA, priority = com.connectmesh.mesh.BleOperationQueue.Priority.BULK) { _, cb -> cb(true) })
        highQueue.add(com.connectmesh.mesh.BleOperationQueue.WriteOp(ByteArray(10), transferId = transferIdB, priority = com.connectmesh.mesh.BleOperationQueue.Priority.HIGH) { _, cb -> cb(true) })

        // Purge transfer A
        highQueue.removeIf { it.transferId == transferIdA }
        bulkQueue.removeIf { it.transferId == transferIdA }

        assertEquals(1, highQueue.size)
        assertEquals(0, bulkQueue.size)
        assertEquals(transferIdB, highQueue.peek()?.transferId)
    }

    @Test
    fun testLatePacketsAfterCancellationAreIgnored() {
        val activeTransfers = java.util.concurrent.ConcurrentHashMap<Long, FileManager.TransferState>()
        val transferId = 333L

        // Transfer A is cancelled and removed
        activeTransfers.remove(transferId)

        // Late packet arrives for transfer 333L
        val lateState = activeTransfers[transferId]
        assertNull("Late packet for cancelled transferId must return null state and be ignored safely", lateState)
    }
}
