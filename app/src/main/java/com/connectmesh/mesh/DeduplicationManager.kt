package com.connectmesh.mesh

import java.util.LinkedHashMap

class DeduplicationManager(
    private val maxCapacity: Int = BleConstants.DEDUPLICATION_CACHE_LIMIT,
    private val ttlMs: Long = BleConstants.DEDUPLICATION_TTL_MS
) {
    private val cache = object : LinkedHashMap<Long, Long>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean {
            return size > maxCapacity
        }
    }

    @Synchronized
    fun isDuplicateOrAdd(packetId: Long): Boolean {
        val now = System.currentTimeMillis()
        val timestamp = cache[packetId]
        if (timestamp != null && (now - timestamp) < ttlMs) {
            return true // Duplicate found within TTL
        }
        cache[packetId] = now
        return false // New packet, added to cache
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
