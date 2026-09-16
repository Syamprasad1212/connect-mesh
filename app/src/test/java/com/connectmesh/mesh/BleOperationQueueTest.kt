package com.connectmesh.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class BleOperationQueueTest {

    @Test
    fun testHighExecutesBeforeQueuedBulk() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)
        val executionOrder = CopyOnWriteArrayList<String>()

        var bulk1Callback: ((Boolean) -> Unit)? = null

        // 1. Enqueue first bulk operation and hold execution callback
        queue.enqueue(
            data = byteArrayOf(1),
            transferId = 100L,
            priority = BleOperationQueue.Priority.BULK
        ) { _, cb ->
            executionOrder.add("BULK_1")
            bulk1Callback = cb
        }

        // 2. Enqueue second bulk operation
        queue.enqueue(
            data = byteArrayOf(2),
            transferId = 100L,
            priority = BleOperationQueue.Priority.BULK
        ) { _, cb ->
            executionOrder.add("BULK_2")
            cb(true)
        }

        // 3. Enqueue high priority text operation while BULK_1 is in-flight
        queue.enqueue(
            data = byteArrayOf(3),
            priority = BleOperationQueue.Priority.HIGH
        ) { _, cb ->
            executionOrder.add("HIGH_TEXT")
            cb(true)
        }

        // Complete active BULK_1
        bulk1Callback?.invoke(true)
        Thread.sleep(100L)

        // HIGH_TEXT must be selected before BULK_2
        assertEquals(listOf("BULK_1", "HIGH_TEXT", "BULK_2"), executionOrder)
    }

    @Test
    fun testFailedOperationReleasesQueue() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)
        var secondExecuted = false

        queue.enqueue(
            data = byteArrayOf(1),
            priority = BleOperationQueue.Priority.HIGH
        ) { _, onComplete ->
            // Operation fails
            onComplete(false)
        }

        queue.enqueue(
            data = byteArrayOf(2),
            priority = BleOperationQueue.Priority.HIGH
        ) { _, onComplete ->
            secondExecuted = true
            onComplete(true)
        }

        Thread.sleep(100L)

        assertTrue("Queue must release and process second operation after failure", secondExecuted)
        assertFalse("Queue must not remain executing", queue.isExecuting)
    }

    @Test
    fun testTimedOutOperationReleasesQueue() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 100L, packetPacingDelayMs = 0L)
        var secondExecuted = false

        queue.enqueue(
            data = byteArrayOf(1),
            priority = BleOperationQueue.Priority.HIGH
        ) { _, _ ->
            // Never calls onComplete to simulate hang/timeout
        }

        queue.enqueue(
            data = byteArrayOf(2),
            priority = BleOperationQueue.Priority.HIGH
        ) { _, onComplete ->
            secondExecuted = true
            onComplete(true)
        }

        Thread.sleep(250L)

        assertTrue("Queue must release and process second operation after timeout", secondExecuted)
        assertFalse("Queue must not remain executing", queue.isExecuting)
    }

    @Test
    fun testExceptionReleasesQueue() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)
        var secondExecuted = false

        queue.enqueue(
            data = byteArrayOf(1),
            priority = BleOperationQueue.Priority.HIGH
        ) { _, _ ->
            throw RuntimeException("Simulated GATT exception")
        }

        queue.enqueue(
            data = byteArrayOf(2),
            priority = BleOperationQueue.Priority.HIGH
        ) { _, onComplete ->
            secondExecuted = true
            onComplete(true)
        }

        Thread.sleep(100L)

        assertTrue("Queue must release and process second operation after exception", secondExecuted)
        assertFalse("Queue must not remain executing", queue.isExecuting)
    }

    @Test
    fun testCancelTransferRemovesOnlyMatchingTransfer() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)
        val executedTransfers = CopyOnWriteArrayList<Long>()

        var initialCb: ((Boolean) -> Unit)? = null

        // Hold queue with active operation
        queue.enqueue(
            data = byteArrayOf(0),
            priority = BleOperationQueue.Priority.HIGH
        ) { _, cb ->
            initialCb = cb
        }

        // Add bulk operations while initial operation is in-flight
        queue.enqueue(data = byteArrayOf(1), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executedTransfers.add(100L); cb(true) }
        queue.enqueue(data = byteArrayOf(2), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executedTransfers.add(100L); cb(true) }
        queue.enqueue(data = byteArrayOf(3), transferId = 200L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executedTransfers.add(200L); cb(true) }

        // Cancel transfer 100 before initial operation finishes
        queue.cancelTransfer(100L)

        // Complete active initial operation
        initialCb?.invoke(true)
        Thread.sleep(100L)

        assertFalse("Transfer 100 operations must be cancelled", executedTransfers.contains(100L))
        assertTrue("Unrelated transfer 200 operations must remain", executedTransfers.contains(200L))
    }

    @Test
    fun testDuplicateCancellationIsSafe() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)

        // Cancel non-existent transfer multiple times
        queue.cancelTransfer(9999L)
        queue.cancelTransfer(9999L)
        queue.cancelTransfer(-1L)

        assertEquals(0, queue.size())
        assertFalse(queue.isExecuting)
    }

    @Test
    fun testQueueCanProcessNewMessageAfterVoiceFailure() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 100L, packetPacingDelayMs = 0L)
        var textDelivered = false

        var voiceCb: ((Boolean) -> Unit)? = null

        // 1. Voice transfer bulk fragment in-flight
        queue.enqueue(data = byteArrayOf(1, 2), transferId = 555L, priority = BleOperationQueue.Priority.BULK) { _, cb -> voiceCb = cb }
        queue.enqueue(data = byteArrayOf(3, 4), transferId = 555L, priority = BleOperationQueue.Priority.BULK) { _, cb -> cb(true) }

        // 2. Voice transfer fails -> cancel transfer 555 and complete active fragment with failure
        queue.cancelTransfer(555L)
        voiceCb?.invoke(false)

        // 3. Send text message immediately after
        queue.enqueue(data = byteArrayOf(9, 9), priority = BleOperationQueue.Priority.HIGH) { _, cb ->
            textDelivered = true
            cb(true)
        }

        Thread.sleep(150L)

        assertTrue("Text message must be delivered after voice failure/cancel", textDelivered)
        assertEquals(0, queue.size())
        assertFalse(queue.isExecuting)
    }

    @Test
    fun testFileEndDoesNotBypassPendingBulkChunksForSameTransfer() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)
        val executionOrder = CopyOnWriteArrayList<String>()

        var initialCb: ((Boolean) -> Unit)? = null

        // 1. Hold queue with active operation
        queue.enqueue(data = byteArrayOf(0), priority = BleOperationQueue.Priority.HIGH) { _, cb ->
            initialCb = cb
        }

        // 2. Enqueue FILE_CHUNK_1 (BULK) and FILE_CHUNK_2 (BULK) for transfer 100
        queue.enqueue(data = byteArrayOf(1), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb ->
            executionOrder.add("FILE_CHUNK_1")
            cb(true)
        }
        queue.enqueue(data = byteArrayOf(2), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb ->
            executionOrder.add("FILE_CHUNK_2")
            cb(true)
        }

        // 3. Enqueue FILE_END (HIGH) for transfer 100
        queue.enqueue(data = byteArrayOf(3), transferId = 100L, priority = BleOperationQueue.Priority.HIGH) { _, cb ->
            executionOrder.add("FILE_END")
            cb(true)
        }

        // 4. Release active operation
        initialCb?.invoke(true)
        val start = System.currentTimeMillis()
        while (queue.size() > 0 || queue.isExecuting) {
            if (System.currentTimeMillis() - start > 2000L) break
            Thread.sleep(10L)
        }

        // FILE_END for transfer 100 must NOT execute before FILE_CHUNK_1 and FILE_CHUNK_2
        assertEquals(listOf("FILE_CHUNK_1", "FILE_CHUNK_2", "FILE_END"), executionOrder)
    }

    @Test
    fun testUnrelatedHighOpNotBlockedByBulkChunksOfDifferentTransfer() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)
        val executionOrder = CopyOnWriteArrayList<String>()

        var initialCb: ((Boolean) -> Unit)? = null

        // Hold queue
        queue.enqueue(data = byteArrayOf(0), priority = BleOperationQueue.Priority.HIGH) { _, cb -> initialCb = cb }

        // Bulk chunks for transfer 100
        queue.enqueue(data = byteArrayOf(1), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executionOrder.add("CHUNK_100"); cb(true) }

        // FILE_END for transfer 100 (HIGH)
        queue.enqueue(data = byteArrayOf(2), transferId = 100L, priority = BleOperationQueue.Priority.HIGH) { _, cb -> executionOrder.add("END_100"); cb(true) }

        // Text message (HIGH, transferId = null)
        queue.enqueue(data = byteArrayOf(3), priority = BleOperationQueue.Priority.HIGH) { _, cb -> executionOrder.add("HIGH_TEXT"); cb(true) }

        initialCb?.invoke(true)
        val start = System.currentTimeMillis()
        while (queue.size() > 0 || queue.isExecuting) {
            if (System.currentTimeMillis() - start > 2000L) break
            Thread.sleep(10L)
        }

        // HIGH_TEXT (unrelated) can execute before END_100, but END_100 comes after CHUNK_100
        assertEquals(3, executionOrder.size)
        assertEquals("HIGH_TEXT", executionOrder[0])
        assertEquals("CHUNK_100", executionOrder[1])
        assertEquals("END_100", executionOrder[2])
    }

    @Test
    fun testFileCancelBypassesPendingBulkChunksForSameTransfer() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)
        val executionOrder = CopyOnWriteArrayList<String>()

        var initialCb: ((Boolean) -> Unit)? = null

        // 1. Hold queue
        queue.enqueue(data = byteArrayOf(0), priority = BleOperationQueue.Priority.HIGH) { _, cb -> initialCb = cb }

        // 2. Enqueue BULK chunks for transfer 100
        queue.enqueue(data = byteArrayOf(1), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executionOrder.add("CHUNK_1"); cb(true) }
        queue.enqueue(data = byteArrayOf(2), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executionOrder.add("CHUNK_2"); cb(true) }

        // 3. Enqueue FILE_CANCEL for transfer 100 with isCancel = true
        queue.enqueue(data = byteArrayOf(9), transferId = 100L, priority = BleOperationQueue.Priority.HIGH, isCancel = true) { _, cb -> executionOrder.add("FILE_CANCEL"); cb(true) }

        // 4. Release queue
        initialCb?.invoke(true)
        val start = System.currentTimeMillis()
        while (queue.size() > 0 || queue.isExecuting) {
            if (System.currentTimeMillis() - start > 2000L) break
            Thread.sleep(10L)
        }

        // FILE_CANCEL must execute BEFORE CHUNK_1 and CHUNK_2 for the same transferId
        assertEquals("FILE_CANCEL", executionOrder[0])
    }

    @Test
    fun testCancelTransferPreservesFileCancelOp() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 1000L, packetPacingDelayMs = 0L)
        val executionOrder = CopyOnWriteArrayList<String>()

        var initialCb: ((Boolean) -> Unit)? = null

        // 1. Hold queue
        queue.enqueue(data = byteArrayOf(0), priority = BleOperationQueue.Priority.HIGH) { _, cb -> initialCb = cb }

        // 2. Enqueue BULK chunks and FILE_CANCEL for transfer 100
        queue.enqueue(data = byteArrayOf(1), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executionOrder.add("CHUNK_1"); cb(true) }
        queue.enqueue(data = byteArrayOf(9), transferId = 100L, priority = BleOperationQueue.Priority.HIGH, isCancel = true) { _, cb -> executionOrder.add("FILE_CANCEL"); cb(true) }

        // 3. Cancel transfer 100
        queue.cancelTransfer(100L)

        // 4. Release queue
        initialCb?.invoke(true)
        val start = System.currentTimeMillis()
        while (queue.size() > 0 || queue.isExecuting) {
            if (System.currentTimeMillis() - start > 2000L) break
            Thread.sleep(10L)
        }

        // CHUNK_1 is purged, but FILE_CANCEL is preserved and executed
        assertEquals(listOf("FILE_CANCEL"), executionOrder)
    }

    @Test
    fun testCancelWhileChunkExecutingReleasesQueueForMessage() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 200L, packetPacingDelayMs = 0L)
        val executionOrder = CopyOnWriteArrayList<String>()
        var chunkCallback: ((Boolean) -> Unit)? = null

        // 1. Enqueue chunk for transfer 100 which gets polled and executed
        queue.enqueue(data = byteArrayOf(1), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb ->
            executionOrder.add("CHUNK_EXEC")
            chunkCallback = cb
        }

        Thread.sleep(50L)
        assertTrue("Chunk operation must be currently executing", queue.isExecuting)

        // 2. User cancels transfer 100 mid-flight
        queue.cancelTransfer(100L)

        // 3. User immediately sends text message
        queue.enqueue(data = byteArrayOf(9), priority = BleOperationQueue.Priority.HIGH) { _, cb ->
            executionOrder.add("TEXT_MSG")
            cb(true)
        }

        // 4. In-flight chunk finishes (e.g. failure callback from GATT write failure)
        chunkCallback?.invoke(false)

        val start = System.currentTimeMillis()
        while (queue.size() > 0 || queue.isExecuting) {
            if (System.currentTimeMillis() - start > 1000L) break
            Thread.sleep(10L)
        }

        assertEquals(listOf("CHUNK_EXEC", "TEXT_MSG"), executionOrder)
        assertFalse(queue.isExecuting)
    }

    @Test
    fun testFileCancelWriteFailureDoesNotPoisonQueue() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 200L, packetPacingDelayMs = 0L)
        val executionOrder = CopyOnWriteArrayList<String>()

        // Enqueue FILE_CANCEL that fails write
        queue.enqueue(data = byteArrayOf(9), transferId = 100L, priority = BleOperationQueue.Priority.HIGH, isCancel = true) { _, cb ->
            executionOrder.add("FILE_CANCEL")
            cb(false) // Write failed
        }

        // Enqueue text message
        queue.enqueue(data = byteArrayOf(8), priority = BleOperationQueue.Priority.HIGH) { _, cb ->
            executionOrder.add("MESSAGE_AFTER_CANCEL_FAIL")
            cb(true)
        }

        val start = System.currentTimeMillis()
        while (queue.size() > 0 || queue.isExecuting) {
            if (System.currentTimeMillis() - start > 1000L) break
            Thread.sleep(10L)
        }

        assertEquals(listOf("FILE_CANCEL", "MESSAGE_AFTER_CANCEL_FAIL"), executionOrder)
        assertFalse(queue.isExecuting)
    }

    @Test
    fun testMessageVoiceAndFileBExecuteAfterFileACancel() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val queue = BleOperationQueue(testScope, timeoutMs = 200L, packetPacingDelayMs = 0L)
        val executionOrder = CopyOnWriteArrayList<String>()
        var initialCb: ((Boolean) -> Unit)? = null

        // Hold queue
        queue.enqueue(data = byteArrayOf(0), priority = BleOperationQueue.Priority.HIGH) { _, cb -> initialCb = cb }

        // 1. Queue File A chunks while queue is held
        queue.enqueue(data = byteArrayOf(1), transferId = 100L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executionOrder.add("FILE_A"); cb(true) }

        // 2. Cancel File A
        queue.cancelTransfer(100L)

        // 3. Send MESSAGE, VOICE, and File B
        queue.enqueue(data = byteArrayOf(2), priority = BleOperationQueue.Priority.HIGH) { _, cb -> executionOrder.add("TEXT"); cb(true) }
        queue.enqueue(data = byteArrayOf(3), transferId = 200L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executionOrder.add("VOICE"); cb(true) }
        queue.enqueue(data = byteArrayOf(4), transferId = 300L, priority = BleOperationQueue.Priority.BULK) { _, cb -> executionOrder.add("FILE_B"); cb(true) }

        // Release holding op
        initialCb?.invoke(true)

        val start = System.currentTimeMillis()
        while (queue.size() > 0 || queue.isExecuting) {
            if (System.currentTimeMillis() - start > 1000L) break
            Thread.sleep(10L)
        }

        assertFalse("File A must be purged", executionOrder.contains("FILE_A"))
        assertTrue("TEXT must execute", executionOrder.contains("TEXT"))
        assertTrue("VOICE must execute", executionOrder.contains("VOICE"))
        assertTrue("FILE_B must execute", executionOrder.contains("FILE_B"))
        assertFalse(queue.isExecuting)
    }
}
