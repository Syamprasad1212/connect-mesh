package com.connectmesh.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.connectmesh.diagnostics.NetworkEventLogger
import com.connectmesh.mesh.RelayManager
import com.connectmesh.mesh.RouteTable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    deviceId: Long,
    connectedCount: Int,
    knownCount: Int,
    relayManager: RelayManager,
    routeTable: RouteTable,
    onSendPingTest: () -> Unit,
    onSendMultiHopTest: () -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    val events by NetworkEventLogger.eventsFlow.collectAsState()
    val routes = remember(events.size) { routeTable.getAllRoutes() }
    val directRoutes = routes.filter { it.hopCount == 1 }
    val remoteRoutes = routes.filter { it.hopCount > 1 }

    val activeCount = (relayManager.fileTransfersSent.get() + relayManager.fileTransfersReceived.get() - relayManager.fileTransfersCompleted.get() - relayManager.fileTransfersFailed.get()).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mesh Diagnostics & Event Stream", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("My Device ID: 0x${deviceId.toString(16).uppercase()}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Discovered Peers: $knownCount | Connected Peers: $connectedCount", fontWeight = FontWeight.SemiBold)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Packets Sent: ${relayManager.packetsSent.get()}")
                    Text("Packets Received: ${relayManager.packetsReceived.get()}")
                    Text("ACKs Received: ${relayManager.acksReceived.get()}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Total Packets Relayed: ${relayManager.packetsRelayed.get()}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    Text("  • Text Relayed: ${relayManager.textPacketsRelayed.get()}")
                    Text("  • Voice Chunks Relayed: ${relayManager.voiceFragmentsRelayed.get()}")
                    Text("  • ACKs Relayed: ${relayManager.ackPacketsRelayed.get()}")
                    Text("  • Control Relayed: ${relayManager.controlPacketsRelayed.get()}")
                    Text("Packets Dropped: ${relayManager.packetsDropped.get()}")
                    Text("Duplicates Suppressed: ${relayManager.duplicatesSuppressed.get()}")

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("🚨 SOS EMERGENCY METRICS", fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                    Text("  • SOS Sent: ${relayManager.sosPacketsSent.get()}")
                    Text("  • SOS Received: ${relayManager.sosPacketsReceived.get()}")
                    Text("  • SOS Relayed: ${relayManager.sosPacketsRelayed.get()}")
                    Text("  • SOS ACKs Received: ${relayManager.sosAcksReceived.get()}")
                    Text("  • SOS Duplicates Suppressed: ${relayManager.sosDuplicatesSuppressed.get()}")

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("📁 FILE SHARING METRICS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("File Transfers:")
                    Text("  • Active: $activeCount")
                    Text("  • Files Sent: ${relayManager.fileTransfersSent.get()}")
                    Text("  • Files Received: ${relayManager.fileTransfersReceived.get()}")
                    Text("  • Completed: ${relayManager.fileTransfersCompleted.get()}")
                    Text("  • Failed: ${relayManager.fileTransfersFailed.get()}")
                    Text("File Chunks:")
                    Text("  • Chunks Sent: ${relayManager.fileChunksSent.get()}")
                    Text("  • Chunks Received: ${relayManager.fileChunksReceived.get()}")
                    Text("  • Chunks Retransmitted: ${relayManager.fileChunksRetransmitted.get()}")
                    Text("  • Duplicate Chunks Suppressed: ${relayManager.fileDuplicatesSuppressed.get()}")
                    Text("File Relay:")
                    Text("  • File Chunks Relayed: ${relayManager.fileChunksRelayed.get()}")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // MULTI-HOP TEST SECTION
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("MULTI-HOP MESH TEST (A → B → C)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Direct Peers: ${directRoutes.size} | Remote Mesh Peers: ${remoteRoutes.size}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSendPingTest,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("PING Direct Peers", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = onSendMultiHopTest,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Send Route Test (A → B → C)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("ACTIVE ROUTE TABLE (${routes.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (routes.isEmpty()) {
                        Text("No active mesh routes.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Destination", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Next Hop", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Hops", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Type", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        routes.forEach { r ->
                            val type = if (r.hopCount == 1) "DIRECT" else "RELAY"
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("0x${r.destinationId.toString(16).takeLast(4).uppercase()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                                Text("0x${r.nextHopId.toString(16).takeLast(4).uppercase()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                                Text("${r.hopCount}", style = MaterialTheme.typography.labelSmall)
                                Text(type, fontWeight = FontWeight.Bold, color = if (type == "DIRECT") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("CONNECT_MESH Event Log Stream", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (events.isEmpty()) {
                    item {
                        Text("No network events logged yet.", color = Color.Gray)
                    }
                } else {
                    items(events) { entry ->
                        Text(
                            entry,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
