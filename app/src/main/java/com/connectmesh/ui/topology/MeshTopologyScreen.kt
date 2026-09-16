package com.connectmesh.ui.topology

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.identity.PeerIdentity
import com.connectmesh.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshTopologyScreen(localPeerId: Long, userNickname: String, peers: List<PeerIdentity>) {
    val directCount = peers.count { it.hopCount == 1 }
    val relayCount = peers.count { it.hopCount > 1 }
    val maxHops = if (peers.isNotEmpty()) peers.maxOf { it.hopCount } else 0

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Mesh Network Topology", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // MESH STATUS SUMMARY CARD
            item {
                MeshCard {
                    Text("MESH STATUS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppTextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(AppSuccessGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Network Active",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = AppSuccessGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = AppBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Reachable Nodes", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text("${peers.size} devices", fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        }
                        Column {
                            Text("Direct Connections", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text("$directCount direct", fontWeight = FontWeight.Bold, color = AppSuccessGreen)
                        }
                        Column {
                            Text("Max Network Depth", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text("$maxHops hops", fontWeight = FontWeight.Bold, color = AppWarningAmber)
                        }
                    }
                }
            }

            // VISUAL TOPOLOGY CANVAS OR EMPTY STATE
            item {
                if (peers.isEmpty()) {
                    MeshEmptyState(
                        icon = Icons.Default.Hub,
                        title = "Your mesh is waiting",
                        description = "Nearby devices will appear here automatically when Connect-Mesh is running on nearby Android phones over BLE."
                    )
                } else {
                    MeshCard {
                        Text("REAL ROUTE TABLE TOPOLOGY", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AppPrimaryAccent)
                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .background(AppSecondaryBackground, shape = MaterialTheme.shapes.medium),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val center = Offset(size.width / 2, size.height / 2)
                                val radiusDistance = minOf(size.width, size.height) * 0.35f

                                // Draw center local node
                                drawCircle(color = AppPrimaryAccent, radius = 24f, center = center)

                                // Draw peers and connection lines
                                peers.forEachIndexed { idx, peer ->
                                    val angle = (2 * Math.PI * idx / peers.size).toFloat()
                                    val peerOffset = Offset(
                                        center.x + radiusDistance * kotlin.math.cos(angle),
                                        center.y + radiusDistance * kotlin.math.sin(angle)
                                    )
                                    val lineColor = if (peer.hopCount == 1) AppSuccessGreen else AppWarningAmber
                                    drawLine(
                                        color = lineColor,
                                        start = center,
                                        end = peerOffset,
                                        strokeWidth = 5f
                                    )
                                    drawCircle(color = lineColor, radius = 18f, center = peerOffset)
                                }
                            }

                            Text(
                                "You ($userNickname)",
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(top = 40.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // LEGEND
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LegendItem(color = AppPrimaryAccent, label = "🔵 You")
                            LegendItem(color = AppSuccessGreen, label = "🟢 Direct Link")
                            LegendItem(color = AppWarningAmber, label = "🟡 Mesh Relay")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = AppTextSecondary, fontWeight = FontWeight.Medium)
    }
}
