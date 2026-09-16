package com.connectmesh.mesh

import java.util.concurrent.atomic.AtomicLong

class RelayManager {
    val packetsSent = AtomicLong(0)
    val packetsReceived = AtomicLong(0)
    val packetsRelayed = AtomicLong(0)
    val textPacketsRelayed = AtomicLong(0)
    val voiceFragmentsRelayed = AtomicLong(0)
    val ackPacketsRelayed = AtomicLong(0)
    val controlPacketsRelayed = AtomicLong(0)
    val packetsDropped = AtomicLong(0)
    val duplicatesSuppressed = AtomicLong(0)
    val acksReceived = AtomicLong(0)

    // SOS Mesh Counters
    val sosPacketsSent = AtomicLong(0)
    val sosPacketsReceived = AtomicLong(0)
    val sosPacketsRelayed = AtomicLong(0)
    val sosAcksReceived = AtomicLong(0)
    val sosDuplicatesSuppressed = AtomicLong(0)

    // File Sharing Mesh Counters
    val fileTransfersSent = AtomicLong(0)
    val fileTransfersReceived = AtomicLong(0)
    val fileTransfersCompleted = AtomicLong(0)
    val fileTransfersFailed = AtomicLong(0)
    val fileChunksSent = AtomicLong(0)
    val fileChunksReceived = AtomicLong(0)
    val fileChunksRetransmitted = AtomicLong(0)
    val fileChunksRelayed = AtomicLong(0)
    val fileDuplicatesSuppressed = AtomicLong(0)

    fun reset() {
        packetsSent.set(0)
        packetsReceived.set(0)
        packetsRelayed.set(0)
        textPacketsRelayed.set(0)
        voiceFragmentsRelayed.set(0)
        ackPacketsRelayed.set(0)
        controlPacketsRelayed.set(0)
        packetsDropped.set(0)
        duplicatesSuppressed.set(0)
        acksReceived.set(0)
        sosPacketsSent.set(0)
        sosPacketsReceived.set(0)
        sosPacketsRelayed.set(0)
        sosAcksReceived.set(0)
        sosDuplicatesSuppressed.set(0)
        fileTransfersSent.set(0)
        fileTransfersReceived.set(0)
        fileTransfersCompleted.set(0)
        fileTransfersFailed.set(0)
        fileChunksSent.set(0)
        fileChunksReceived.set(0)
        fileChunksRetransmitted.set(0)
        fileChunksRelayed.set(0)
        fileDuplicatesSuppressed.set(0)
    }
}
