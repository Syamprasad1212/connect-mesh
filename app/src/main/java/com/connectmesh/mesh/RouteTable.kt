package com.connectmesh.mesh

import com.connectmesh.diagnostics.NetworkEventLogger
import java.util.concurrent.ConcurrentHashMap

class RouteTable {
    companion object {
        const val MAX_MESH_HOPS = 5
    }

    data class RouteEntry(
        val destinationId: Long,
        val nextHopId: Long,
        val hopCount: Int,
        val lastUpdatedMs: Long = System.currentTimeMillis()
    )

    private val routes = ConcurrentHashMap<Long, RouteEntry>()

    fun updateRoute(destinationId: Long, nextHopId: Long, hopCount: Int, localDeviceId: Long = 0L): Boolean {
        if (destinationId == 0L || nextHopId == 0L) {
            NetworkEventLogger.log("CONNECT_MESH_ROUTE: ROUTE_REJECTED reason=ZERO_ID dest=0x${destinationId.toString(16).uppercase()} nextHop=0x${nextHopId.toString(16).uppercase()}")
            return false
        }
        if (localDeviceId != 0L && (destinationId == localDeviceId || nextHopId == localDeviceId)) {
            if (destinationId == localDeviceId) {
                NetworkEventLogger.log("CONNECT_MESH_ROUTE: ROUTE_REJECTED reason=SELF_DESTINATION dest=0x${destinationId.toString(16).uppercase()}")
                return false
            }
        }
        if (hopCount < 1 || hopCount > MAX_MESH_HOPS) {
            NetworkEventLogger.log("CONNECT_MESH_ROUTE: ROUTE_REJECTED reason=INVALID_HOP_COUNT hops=$hopCount dest=0x${destinationId.toString(16).uppercase()}")
            return false
        }

        val existing = routes[destinationId]
        if (existing == null || hopCount <= existing.hopCount || System.currentTimeMillis() - existing.lastUpdatedMs > 30000L) {
            routes[destinationId] = RouteEntry(destinationId, nextHopId, hopCount)
            val type = if (hopCount == 1) "DIRECT" else "RELAY"
            NetworkEventLogger.log("CONNECT_MESH_ROUTE: ROUTE_LEARNED dest=0x${destinationId.toString(16).uppercase()} nextHop=0x${nextHopId.toString(16).uppercase()} hops=$hopCount type=$type")
            return true
        }
        return false
    }

    fun getNextHop(destinationId: Long): Long? {
        return routes[destinationId]?.nextHopId
    }

    fun getRoute(destinationId: Long): RouteEntry? {
        return routes[destinationId]
    }

    fun removeRoute(destinationId: Long) {
        val removed = routes.remove(destinationId)
        if (removed != null) {
            NetworkEventLogger.log("CONNECT_MESH_ROUTE: ROUTE_REMOVED destination=0x${destinationId.toString(16).uppercase()} nextHop=0x${removed.nextHopId.toString(16).uppercase()}")
        }
    }

    fun cleanExpiredRoutes(localDeviceId: Long = 0L, timeoutMs: Long = 45000L) {
        val now = System.currentTimeMillis()
        val expired = routes.values.filter { entry ->
            (now - entry.lastUpdatedMs > timeoutMs) ||
            (entry.hopCount < 1 || entry.hopCount > MAX_MESH_HOPS) ||
            (entry.destinationId == 0L || entry.nextHopId == 0L) ||
            (localDeviceId != 0L && entry.destinationId == localDeviceId)
        }
        expired.forEach {
            removeRoute(it.destinationId)
            NetworkEventLogger.log("CONNECT_MESH_ROUTE: ROUTE_EXPIRED destination=0x${it.destinationId.toString(16).uppercase()}")
        }
    }

    fun getAllRoutes(): List<RouteEntry> = routes.values.filter { it.hopCount in 1..MAX_MESH_HOPS }

    fun clear() {
        routes.clear()
    }
}
