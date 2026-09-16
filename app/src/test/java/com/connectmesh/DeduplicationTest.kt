package com.connectmesh

import com.connectmesh.mesh.DeduplicationManager
import org.junit.Assert.*
import org.junit.Test

class DeduplicationTest {

    @Test
    fun testDeduplicationSuppression() {
        val dedup = DeduplicationManager(maxCapacity = 100, ttlMs = 5000L)
        val packetId = 12345L

        // First attempt should not be duplicate
        assertFalse(dedup.isDuplicateOrAdd(packetId))

        // Second attempt immediately after should be duplicate
        assertTrue(dedup.isDuplicateOrAdd(packetId))

        // Different packet ID should not be duplicate
        assertFalse(dedup.isDuplicateOrAdd(67890L))
    }
}
