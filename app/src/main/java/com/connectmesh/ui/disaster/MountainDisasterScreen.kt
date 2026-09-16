package com.connectmesh.ui.disaster

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.db.OutboxEntry
import com.connectmesh.identity.PeerIdentity
import com.connectmesh.mesh.RouteTable
import com.connectmesh.relay.MeshNodeRole
import com.connectmesh.relay.StaticRelayController
import com.connectmesh.service.MeshForegroundService.SosAlert
import com.connectmesh.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountainDisasterScreen(
    localDeviceId: Long,
    userNickname: String,
    peers: List<PeerIdentity>,
    sosAlerts: List<SosAlert>,
    routeTable: RouteTable,
    staticRelayController: StaticRelayController,
    pendingOutboxEntries: List<OutboxEntry>,
    onSendSos: (String) -> Unit,
    onAcknowledgeSos: (packetId: Long, originatorId: Long) -> Unit
) {
    var showSosDialog by remember { mutableStateOf(false) }
    var sosMessageText by remember { mutableStateOf("MOUNTAIN DISASTER SOS: Need assistance at location!") }

    val directCount = peers.count { it.hopCount == 1 }
    val maxHops = if (peers.isNotEmpty()) peers.maxOf { it.hopCount } else 1
    val activeRoutesCount = routeTable.getAllRoutes().size
    val isLocalRelay = staticRelayController.isStaticRelay()
    val staticRelayPeers = peers.filter { it.nickname.contains("RELAY", ignoreCase = true) || (routeTable.getRoute(it.peerId)?.hopCount ?: 1) > 1 }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Terrain,
                            contentDescription = null,
                            tint = AppWarningAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "Mountain / Disaster Mode",
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary,
                                fontSize = 18.sp
                            )
                            Text(
                                "Zero-Connectivity BLE Mesh Network",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextSecondary
                            )
                        }
                    }
                },
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
            // 1. DISASTER MODE STATUS & ZERO-CONNECTIVITY BANNER
            item {
                MeshCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1E293B),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.SignalCellularOff,
                                    contentDescription = null,
                                    tint = AppWarningAmber
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "OFFLINE FIELD OPERATION",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AppWarningAmber
                            )
                            Text(
                                "Cellular & Internet Damaged / Absent",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF065F46)
                        ) {
                            Text(
                                "BLE MESH ON",
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = AppBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DisasterStatusPill(label = "BLE MESH", active = true, activeText = "100% Peer Mesh")
                        DisasterStatusPill(label = "CELLULAR", active = false, activeText = "Not Needed")
                        DisasterStatusPill(label = "INTERNET", active = false, activeText = "Not Needed")
                    }
                }
            }

            // 2. DISASTER TELEMETRY & NETWORK COUNTERS CARD
            item {
                MeshCard {
                    Text(
                        "DISASTER MESH TELEMETRY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TelemetryMetric(
                            value = "${peers.size}",
                            label = "Reachable Devices",
                            color = AppSuccessGreen
                        )
                        TelemetryMetric(
                            value = "$directCount",
                            label = "Direct Neighbors",
                            color = AppPrimaryAccent
                        )
                        TelemetryMetric(
                            value = "$activeRoutesCount",
                            label = "Active Routes",
                            color = Color(0xFF38BDF8)
                        )
                        TelemetryMetric(
                            value = "$maxHops",
                            label = "Max Hop Depth",
                            color = AppWarningAmber
                        )
                    }
                }
            }

            // 3. EMERGENCY SOS LAUNCHER CARD
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0808)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AppEmergencyRed))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = AppEmergencyRed,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "EMERGENCY DISASTER SOS",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    "Multi-Hop Emergency Broadcast across all mesh nodes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFCA5A5)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { showSosDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppEmergencyRed),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Emergency, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "BROADCAST DISASTER SOS ALERT",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // 4. ACTIVE DISASTER SOS FEED
            if (sosAlerts.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "ACTIVE DISASTER ALERTS (${sosAlerts.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppEmergencyRed
                        )
                    }
                }

                items(sosAlerts.distinctBy { it.packetId }) { alert ->
                    val isSelf = alert.senderId == localDeviceId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = AppSecondaryBackground),
                        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AppEmergencyRed))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = AppEmergencyRed)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (isSelf) "YOUR SOS BROADCAST" else alert.senderNickname.ifBlank { "Remote Peer" },
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 15.sp
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (alert.isAcknowledgedByMe) AppSuccessGreen.copy(alpha = 0.2f) else AppEmergencyRed.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        if (alert.isAcknowledgedByMe) "ACKNOWLEDGED" else "UNACKNOWLEDGED",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = if (alert.isAcknowledgedByMe) AppSuccessGreen else AppEmergencyRed,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "\"${alert.message}\"",
                                color = AppTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Sender ID: 0x${alert.senderId.toString(16).takeLast(8).uppercase()} • Time: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(alert.timestamp))}",
                                color = AppTextMuted,
                                fontSize = 11.sp
                            )

                            if (!isSelf && !alert.isAcknowledgedByMe) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { onAcknowledgeSos(alert.packetId, alert.senderId) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppSuccessGreen),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("SEND EMERGENCY ACKNOWLEDGEMENT", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 5. STATIC RELAY NODE ARCHITECTURE CARD
            item {
                MeshCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Router,
                            contentDescription = null,
                            tint = AppPrimaryAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "STATIC RELAY INFRASTRUCTURE",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AppTextPrimary
                            )
                            Text(
                                "Solar / Battery High-Gain Mountain Nodes",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isLocalRelay) AppPrimaryAccent else Color(0xFF334155)
                        ) {
                            Text(
                                if (isLocalRelay) "THIS NODE: RELAY" else "THIS NODE: USER",
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = AppBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Reachable Relays", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text("${staticRelayPeers.size} static nodes", fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        }
                        Column {
                            Text("Relay Status", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text(if (staticRelayPeers.isNotEmpty() || isLocalRelay) "Operational" else "Searching...", fontWeight = FontWeight.Bold, color = if (staticRelayPeers.isNotEmpty() || isLocalRelay) AppSuccessGreen else AppWarningAmber)
                        }
                        Column {
                            Text("Relay Forwarding", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text("Autonomous", fontWeight = FontWeight.Bold, color = AppPrimaryAccent)
                        }
                    }
                }
            }

            // 6. PERSISTENT STORE-AND-FORWARD OUTBOX CARD
            item {
                MeshCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Outbox,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "STORE-AND-FORWARD OUTBOX",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AppTextPrimary
                            )
                            Text(
                                "Offline Persistence for Disconnected Peers",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (pendingOutboxEntries.isNotEmpty()) AppWarningAmber else AppSuccessGreen
                        ) {
                            Text(
                                "${pendingOutboxEntries.size} QUEUED",
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (pendingOutboxEntries.isEmpty()) {
                        Text(
                            "Outbox is empty. All outgoing messages have been delivered across the mesh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextMuted
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            pendingOutboxEntries.take(3).forEach { entry ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF0F172A)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                "Type: ${entry.messageType} • To: 0x${entry.recipientId.toString(16).takeLast(6).uppercase()}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = AppTextPrimary
                                            )
                                            Text(
                                                "Attempts: ${entry.attemptCount} • Status: ${entry.status}",
                                                fontSize = 10.sp,
                                                color = AppTextSecondary
                                            )
                                        }
                                        Icon(
                                            Icons.Default.HourglassTop,
                                            contentDescription = null,
                                            tint = AppWarningAmber,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            if (pendingOutboxEntries.size > 3) {
                                Text(
                                    "+ ${pendingOutboxEntries.size - 3} more queued items waiting for target peer",
                                    fontSize = 11.sp,
                                    color = AppTextMuted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // 7. VISUAL MESH TOPOLOGY GRAPH
            item {
                MeshCard {
                    Text(
                        "FIELD MESH TOPOLOGY MAP",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (peers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .background(Color(0xFF0F172A), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Hub, contentDescription = null, tint = AppTextMuted, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("No remote mesh peers in range yet", color = AppTextMuted, fontSize = 12.sp)
                                Text("Start another Connect-Mesh node nearby over BLE", color = AppTextMuted, fontSize = 10.sp)
                            }
                        }
                    } else {
                        DisasterMeshCanvas(
                            localDeviceId = localDeviceId,
                            peers = peers
                        )
                    }
                }
            }

            // 8. DISASTER OPERATIONS FIELD MANUAL
            item {
                MeshCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, tint = AppPrimaryAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "FIELD OPERATIONS GUIDE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = AppTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "1. Zero Infrastructure: Operates without internet or cellular connectivity via BLE peer-to-peer.\n" +
                        "2. Multi-Hop Relay: Messages automatically hop through intermediate devices (A -> B -> C).\n" +
                        "3. End-to-End Encryption: All text, voice, and files encrypted via Noise XX protocol.\n" +
                        "4. Store-and-Forward Outbox: Undelivered packets persist in SQLite database and retry automatically upon peer reconnection.\n" +
                        "5. Emergency SOS: Priority flood-routing bypasses congestion to alert all reachable nodes instantly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // CONFIRM DISASTER SOS DIALOG
    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AppEmergencyRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Broadcast Disaster SOS?", fontWeight = FontWeight.Bold, color = AppTextPrimary)
                }
            },
            text = {
                Column {
                    Text(
                        "This will flood broadcast an emergency SOS alert across all multi-hop mesh nodes.",
                        color = AppTextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = sosMessageText,
                        onValueChange = { sosMessageText = it },
                        label = { Text("Emergency Message") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppEmergencyRed,
                            unfocusedBorderColor = AppBorder,
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSendSos(sosMessageText)
                        showSosDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppEmergencyRed)
                ) {
                    Text("BROADCAST SOS", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) {
                    Text("CANCEL", color = AppTextSecondary)
                }
            },
            containerColor = AppSecondaryBackground
        )
    }
}

@Composable
private fun DisasterStatusPill(label: String, active: Boolean, activeText: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (active) AppSuccessGreen.copy(alpha = 0.15f) else Color(0xFF334155)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = if (active) AppSuccessGreen else AppTextMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(activeText, fontSize = 10.sp, color = AppTextSecondary)
    }
}

@Composable
private fun TelemetryMetric(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color)
        Text(label, fontSize = 10.sp, color = AppTextSecondary)
    }
}

@Composable
private fun DisasterMeshCanvas(localDeviceId: Long, peers: List<PeerIdentity>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            // Draw local node center
            drawCircle(
                color = Color(0xFF6366F1),
                radius = 16.dp.toPx(),
                center = Offset(centerX, centerY)
            )

            val radius = size.height.coerceAtMost(size.width) / 2.6f
            val totalPeers = peers.size

            peers.forEachIndexed { index, peer ->
                val angle = (2 * Math.PI / totalPeers) * index
                val peerX = centerX + (radius * Math.cos(angle)).toFloat()
                val peerY = centerY + (radius * Math.sin(angle)).toFloat()

                val isRelay = peer.nickname.contains("RELAY", ignoreCase = true)
                val connectionColor = when {
                    isRelay -> Color(0xFF38BDF8)
                    peer.hopCount == 1 -> Color(0xFF10B981)
                    else -> Color(0xFFF59E0B)
                }

                // Draw link line
                drawLine(
                    color = connectionColor,
                    start = Offset(centerX, centerY),
                    end = Offset(peerX, peerY),
                    strokeWidth = 2.dp.toPx()
                )

                // Draw peer node circle
                drawCircle(
                    color = connectionColor,
                    radius = 12.dp.toPx(),
                    center = Offset(peerX, peerY)
                )
            }
        }

        // Overlay text labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text("● You (Center)", fontSize = 10.sp, color = Color(0xFF818CF8), fontWeight = FontWeight.Bold)
            Text("● Direct (Green)", fontSize = 10.sp, color = AppSuccessGreen, fontWeight = FontWeight.Bold)
            Text("● Multi-Hop (Amber)", fontSize = 10.sp, color = AppWarningAmber, fontWeight = FontWeight.Bold)
            Text("● Relay (Sky)", fontSize = 10.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
        }
    }
}
