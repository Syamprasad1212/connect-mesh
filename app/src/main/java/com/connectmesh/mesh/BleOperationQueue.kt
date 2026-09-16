package com.connectmesh.mesh

import com.connectmesh.diagnostics.NetworkEventLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class BleOperationQueue(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 4000L,
    private val packetPacingDelayMs: Long = 15L
) {
    enum class Priority {
        HIGH, // Control, Text, ACK, SOS, ANNOUNCE
        BULK  // Voice fragments, File fragments
    }

    data class WriteOp(
        val data: ByteArray,
        val transferId: Long? = null,
        val priority: Priority = Priority.HIGH,
        val isCancel: Boolean = false,
        val executeWrite: (ByteArray, (Boolean) -> Unit) -> Unit
    )

    private val highPriorityQueue = ConcurrentLinkedQueue<WriteOp>()
    private val bulkQueue = ConcurrentLinkedQueue<WriteOp>()

    @Volatile
    private var isExecutingInternal = false

    val isExecuting: Boolean
        get() = isExecutingInternal

    fun enqueue(
        data: ByteArray,
        transferId: Long? = null,
        priority: Priority = Priority.HIGH,
        isCancel: Boolean = false,
        executeWrite: (ByteArray, (Boolean) -> Unit) -> Unit
    ) {
        val op = WriteOp(data, transferId, priority, isCancel, executeWrite)
        if (priority == Priority.HIGH) {
            highPriorityQueue.add(op)
        } else {
            bulkQueue.add(op)
        }
        val totalSize = size()
        NetworkEventLogger.log("CONNECT_MESH_BLE: QUEUE_ADD (HighPrio=${highPriorityQueue.size}, Bulk=${bulkQueue.size}, Total=$totalSize, IsCancel=$isCancel)")
        processNext()
    }

    @Synchronized
    private fun processNext() {
        if (isExecutingInternal) return

        val op = pollNextOperation() ?: return

        isExecutingInternal = true
        val remaining = size()
        NetworkEventLogger.log("CONNECT_MESH_BLE: QUEUE_START (Prio=${op.priority}, TransferId=${op.transferId}, IsCancel=${op.isCancel}, Remaining=$remaining)")

        scope.launch {
            try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        try {
                            op.executeWrite(op.data) { success ->
                                if (cont.isActive) {
                                    cont.resumeWith(Result.success(success))
                                }
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) {
                                cont.resumeWith(Result.success(false))
                            }
                        }
                    }
                }
                NetworkEventLogger.log("CONNECT_MESH_BLE: QUEUE_COMPLETE")
            } catch (e: Exception) {
                NetworkEventLogger.log("CONNECT_MESH_BLE: QUEUE_TIMEOUT_OR_ERROR: ${e.message}")
            } finally {
                // Apply pacing delay ONLY if previous op was BULK and next waiting op is also BULK
                if (op.priority == Priority.BULK && highPriorityQueue.isEmpty() && bulkQueue.isNotEmpty() && packetPacingDelayMs > 0) {
                    try { delay(packetPacingDelayMs) } catch (e: Exception) {}
                }
                isExecutingInternal = false
                processNext()
            }
        }
    }

    private fun pollNextOperation(): WriteOp? {
        // Select HIGH priority operation first, provided it is a cancel op, has no transferId, or is NOT waiting for pending BULK operations of the same transferId
        val readyHighOp = highPriorityQueue.find { op ->
            op.isCancel || op.transferId == null || bulkQueue.none { bulkOp -> bulkOp.transferId == op.transferId }
        }
        if (readyHighOp != null) {
            highPriorityQueue.remove(readyHighOp)
            return readyHighOp
        }

        // Next, select BULK operation if available
        val bulkOp = bulkQueue.poll()
        if (bulkOp != null) {
            return bulkOp
        }

        // Fallback: poll whatever remains in HIGH queue (e.g. if bulk has been cleared)
        return highPriorityQueue.poll()
    }

    fun hasPendingBulkOperations(transferId: Long): Boolean {
        if (transferId <= 0) return false
        return bulkQueue.any { it.transferId == transferId }
    }

    suspend fun awaitTransferBulkDrain(transferId: Long, timeoutMs: Long = 10_000L): Boolean {
        if (transferId <= 0) return true
        val start = System.currentTimeMillis()
        while (hasPendingBulkOperations(transferId) || isExecutingInternal) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                NetworkEventLogger.log("CONNECT_MESH_BLE: AWAIT_BULK_DRAIN_TIMEOUT id=$transferId")
                return false
            }
            delay(10)
        }
        return true
    }

    /**
     * Purges all pending operations matching transferId (e.g. failed/cancelled voice or file transfer).
     * Preserves cancellation operations (isCancel = true).
     */
    fun cancelTransfer(transferId: Long) {
        if (transferId <= 0) return
        val highRemoved = highPriorityQueue.removeIf { it.transferId == transferId && !it.isCancel }
        val bulkRemoved = bulkQueue.removeIf { it.transferId == transferId }
        if (highRemoved || bulkRemoved) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: QUEUE_TRANSFER_CANCELLED id=$transferId (HighRemoved=$highRemoved, BulkRemoved=$bulkRemoved, Remaining Total=${size()})")
        }
    }

    fun clear() {
        highPriorityQueue.clear()
        bulkQueue.clear()
        isExecutingInternal = false
    }

    fun size(): Int = highPriorityQueue.size + bulkQueue.size

    fun getHighPriorityCount(): Int = highPriorityQueue.size
    fun getBulkCount(): Int = bulkQueue.size
}
