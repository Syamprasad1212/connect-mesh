package com.connectmesh.ui.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.service.MeshForegroundService.SosAlert
import com.connectmesh.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosScreen(
    currentDeviceId: Long,
    sosAlerts: List<SosAlert>,
    onSendSos: (String) -> Unit,
    onAcknowledgeSos: (packetId: Long, originatorId: Long) -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    var customSosMessage by remember { mutableStateOf("I need immediate assistance!") }

    val deduplicatedAlerts = remember(sosAlerts) {
        sosAlerts.distinctBy { it.packetId }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AppEmergencyRed) },
            title = { Text("BROADCAST EMERGENCY SOS?", fontWeight = FontWeight.Bold, color = AppEmergencyRed) },
            text = {
                Column {
                    Text("This will alert all reachable devices through the Connect-Mesh network. No internet required.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customSosMessage,
                        onValueChange = { customSosMessage = it },
                        label = { Text("Emergency Note") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        onSendSos(customSosMessage)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppEmergencyRed)
                ) {
                    Text("SEND SOS NOW", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("CANCEL", color = AppTextSecondary)
                }
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Emergency SOS", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // SOS MAIN TRIGGER CARD
            MeshCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🚨", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "EMERGENCY SOS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppEmergencyRed
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Broadcast an emergency alert through nearby mesh devices. No internet required.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = { showConfirmDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppEmergencyRed),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("BROADCAST SOS", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "ACTIVE INCIDENTS (${deduplicatedAlerts.size})",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (deduplicatedAlerts.isEmpty()) {
                    item {
                        MeshEmptyState(
                            icon = Icons.Default.Warning,
                            title = "No active emergencies",
                            description = "Emergency alerts received through the mesh will appear here automatically."
                        )
                    }
                } else {
                    items(deduplicatedAlerts) { alert ->
                        val senderName = formatPeerDisplayName(alert.senderNickname, alert.senderId)
                        val shortId = formatPeerShortId(alert.senderId)
                        val isSelfAlert = (alert.senderId == currentDeviceId)

                        MeshCard {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(AppEmergencyRed, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(senderName, fontWeight = FontWeight.Bold, color = AppTextPrimary, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(shortId, fontSize = 11.sp, color = AppTextMuted)
                                    }
                                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(alert.timestamp))
                                    Text(timeStr, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "\"${alert.message}\"",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = Color(0xFFFCA5A5)
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = AppBorder)
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ConnectionBadge(hopCount = alert.hopCount)

                                    if (isSelfAlert) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppSuccessGreen, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "ACKs: ${alert.acknowledgedCount}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = AppSuccessGreen,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        if (alert.isAcknowledgedByMe) {
                                            Surface(
                                                color = AppSuccessGreen.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppSuccessGreen, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        "ACKNOWLEDGED ✓",
                                                        fontSize = 12.sp,
                                                        color = AppSuccessGreen,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        } else {
                                            Button(
                                                onClick = { onAcknowledgeSos(alert.packetId, alert.senderId) },
                                                colors = ButtonDefaults.buttonColors(containerColor = AppSuccessGreen),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("ACKNOWLEDGE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
