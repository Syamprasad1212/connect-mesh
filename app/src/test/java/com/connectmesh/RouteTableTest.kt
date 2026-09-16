package com.connectmesh

import com.connectmesh.mesh.RouteTable
import org.junit.Assert.*
import org.junit.Test

class RouteTableTest {

    @Test
    fun testRouteTableLookupAndSelection() {
        val routeTable = RouteTable()
        val nodeA = 100L
        val nodeB = 200L
        val nodeC = 300L

        // Direct link A -> B
        routeTable.updateRoute(nodeB, nodeB, 1)
        assertEquals(nodeB, routeTable.getNextHop(nodeB))
        assertEquals(1, routeTable.getRoute(nodeB)?.hopCount)

        // Multi-hop link A -> B -> C
        routeTable.updateRoute(nodeC, nodeB, 2)
        assertEquals(nodeB, routeTable.getNextHop(nodeC))
        assertEquals(2, routeTable.getRoute(nodeC)?.hopCount)
    }
}
