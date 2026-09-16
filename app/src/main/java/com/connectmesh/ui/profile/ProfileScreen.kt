package com.connectmesh.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    deviceId: Long,
    currentNickname: String,
    connectedPeerCount: Int,
    totalKnownPeers: Int,
    isMeshActive: Boolean = true,
    onNicknameChange: (String) -> Unit,
    onToggleMesh: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSendPingTest: () -> Unit
) {
    var nicknameInput by remember(currentNickname) { mutableStateOf(currentNickname) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Profile & Settings", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // HEADER AVATAR
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(AppSecondaryBackground, CircleShape)
                            .border(2.dp, AppPrimaryAccent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = AppPrimaryAccent,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(currentNickname.ifBlank { "Nearby device" }, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppTextPrimary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("0x${deviceId.toString(16).uppercase()}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AppTextSecondary)
                }
            }

            // SECTION 1: PROFILE
            item {
                MeshCard {
                    Text("PROFILE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppTextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = nicknameInput,
                        onValueChange = {
                            nicknameInput = it
                            onNicknameChange(it)
                        },
                        label = { Text("Display Name") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = AppPrimaryAccent) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppPrimaryAccent,
                            unfocusedBorderColor = AppBorder,
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = AppBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("CRYPTONYM DEVICE ID", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text(
                                "0x${deviceId.toString(16).uppercase()}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AppPrimaryAccent
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("MESH STATUS", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text(
                                if (isMeshActive) "● Active ($connectedPeerCount Direct / $totalKnownPeers Known)" else "○ Stopped",
                                fontWeight = FontWeight.Bold,
                                color = if (isMeshActive) AppSuccessGreen else AppTextMuted
                            )
                        }
                    }
                }
            }

            // SECTION 2: MESH SERVICE CONTROL
            item {
                MeshCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("BACKGROUND MESH SERVICE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppTextSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (isMeshActive) "Mesh service active & listening" else "Mesh service is currently stopped",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextPrimary
                            )
                        }

                        Button(
                            onClick = { onToggleMesh(!isMeshActive) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMeshActive) AppEmergencyRed else AppSuccessGreen
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                if (isMeshActive) "Stop Mesh" else "Start Mesh",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // SECTION 3: NETWORK & DIAGNOSTICS
            item {
                MeshCard {
                    Text("NETWORK & TOOLS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppTextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppSecondaryBackground),
                        shape = RoundedCornerShape(12.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(AppBorder))
                    ) {
                        Icon(Icons.Default.Assessment, contentDescription = null, tint = AppPrimaryAccent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Network Diagnostics & Event Logs", color = AppTextPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onSendPingTest,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(AppBorder))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = AppPrimaryAccent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Send PING Test Packet To All Peers", color = AppTextPrimary)
                    }
                }
            }

            // SECTION 3: ABOUT
            item {
                MeshCard {
                    Text("ABOUT CONNECT-MESH", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppTextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("100% Private Offline BLE Mesh Messenger", style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary, fontWeight = FontWeight.Bold)
                    Text("Operates completely without Internet, Cellular Data, Servers, or Cloud Infrastructure.", style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
                }
            }
        }
    }
}
