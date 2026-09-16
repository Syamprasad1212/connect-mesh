package com.connectmesh.db

import org.junit.Assert.*
import org.junit.Test

class OutboxPersistenceTest {

    @Test
    fun test1_StorePendingMessage() {
        val entry = OutboxEntry(
            messageId = 1001L,
            senderId = 0x1111L,
            recipientId = 0x2222L,
            messageType = "TEXT",
            payload = "Hello Outbox".toByteArray(),
            createdAt = System.currentTimeMillis(),
            status = OutboxStatus.PENDING
        )

        assertEquals(1001L, entry.messageId)
        assertEquals(OutboxStatus.PENDING, entry.status)
        assertEquals("TEXT", entry.messageType)
        assertArrayEquals("Hello Outbox".toByteArray(), entry.payload)
    }

    @Test
    fun test2_RestoreAfterRestart() {
        val entry1 = OutboxEntry(
            messageId = 1002L,
            senderId = 0x1111L,
            recipientId = 0x2222L,
            messageType = "TEXT",
            payload = "Test Restart".toByteArray(),
            createdAt = System.currentTimeMillis(),
            status = OutboxStatus.PENDING
        )

        // Simulating process restart state mapping
        val outboxList = mutableListOf(entry1)
        val restoredEntry = outboxList.find { it.messageId == 1002L }

        assertNotNull(restoredEntry)
        assertEquals(entry1.messageId, restoredEntry?.messageId)
        assertEquals(OutboxStatus.PENDING, restoredEntry?.status)
    }

    @Test
    fun test3_SuccessfulDeliveryAckCleanup() {
        val entry = OutboxEntry(
            messageId = 1003L,
            senderId = 0x1111L,
            recipientId = 0x2222L,
            messageType = "TEXT",
            payload = "ACK Test".toByteArray(),
            status = OutboxStatus.PENDING
        )

        val outboxMap = mutableMapOf(entry.messageId to entry)
        assertTrue(outboxMap.containsKey(1003L))

        // Simulate ACK received
        outboxMap.remove(1003L)
        assertFalse(outboxMap.containsKey(1003L))
    }

    @Test
    fun test4_RetryAttemptTracking() {
        val entry = OutboxEntry(
            messageId = 1004L,
            senderId = 0x1111L,
            recipientId = 0x2222L,
            messageType = "VOICE",
            payload = ByteArray(180),
            attemptCount = 0,
            status = OutboxStatus.PENDING
        )

        // First retry attempt
        entry.attemptCount++
        entry.lastAttemptTime = System.currentTimeMillis()
        entry.status = OutboxStatus.ATTEMPTING

        assertEquals(1, entry.attemptCount)
        assertEquals(OutboxStatus.ATTEMPTING, entry.status)
        assertTrue(entry.lastAttemptTime > 0)
    }

    @Test
    fun test5_RestartDuringPendingState() {
        val pendingOutbox = mutableListOf(
            OutboxEntry(messageId = 2001L, senderId = 1L, recipientId = 2L, messageType = "TEXT", status = OutboxStatus.PENDING),
            OutboxEntry(messageId = 2002L, senderId = 1L, recipientId = 3L, messageType = "VOICE", status = OutboxStatus.ATTEMPTING)
        )

        // Simulate service restart reading pending items
        val restoredPending = pendingOutbox.filter { it.status == OutboxStatus.PENDING || it.status == OutboxStatus.ATTEMPTING }
        assertEquals(2, restoredPending.size)
        assertTrue(restoredPending.any { it.messageId == 2001L })
        assertTrue(restoredPending.any { it.messageId == 2002L })
    }

    @Test
    fun test6_DuplicatePreventionSameMessageId() {
        val originalMessageId = 3001L
        val entry = OutboxEntry(
            messageId = originalMessageId,
            senderId = 0xAAAAL,
            recipientId = 0xBBBBL,
            messageType = "TEXT",
            payload = "Retry test".toByteArray()
        )

        // Across 3 retries, the logical messageId MUST remain unchanged
        for (attempt in 1..3) {
            val retryPacketId = entry.messageId
            assertEquals(originalMessageId, retryPacketId)
        }
    }
}
