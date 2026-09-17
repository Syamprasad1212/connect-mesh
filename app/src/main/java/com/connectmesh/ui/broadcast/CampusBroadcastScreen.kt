package com.connectmesh.ui.broadcast

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.broadcast.BroadcastPriority
import com.connectmesh.broadcast.CollegeBroadcast
import com.connectmesh.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusBroadcastScreen(
    broadcasts: List<CollegeBroadcast>,
    canCreateBroadcast: Boolean,
    onCreateBroadcast: (title: String, message: String, priority: BroadcastPriority) -> Unit,
    onRegisterTrustIssuer: ((issuerId: String, publicKey: String) -> Boolean)? = null
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showTrustDialog by remember { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf("") }
    var messageInput by remember { mutableStateOf("") }
    var selectedPriority by remember { mutableStateOf(BroadcastPriority.NORMAL) }

    var issuerIdInput by remember { mutableStateOf("") }
    var publicKeyInput by remember { mutableStateOf("") }
    var trustErrorMessage by remember { mutableStateOf<String?>(null) }
    var trustSuccessMessage by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Campus Announcements", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
                actions = {
                    if (onRegisterTrustIssuer != null) {
                        IconButton(onClick = { showTrustDialog = true }) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = "Campus Trust Setup", tint = AppPrimaryAccent)
                        }
                    }
                    if (canCreateBroadcast) {
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Campaign, contentDescription = "Create Announcement", tint = AppPrimaryAccent)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (broadcasts.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Campaign,
                        contentDescription = null,
                        tint = AppTextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No Campus Announcements",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AppTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Official verified college-wide alerts from administration will appear here offline.",
                        color = AppTextSecondary,
                        fontSize = 13.sp
                    )
                    if (canCreateBroadcast) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { showCreateDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Campus Alert", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "VERIFIED INSTITUTIONAL ALERTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(broadcasts) { bc ->
                        val (priorityColor, priorityText) = when (bc.priority) {
                            BroadcastPriority.CRITICAL -> Pair(AppEmergencyRed, "🚨 CRITICAL ALERT")
                            BroadcastPriority.HIGH -> Pair(Color(0xFFF59E0B), "⚠️ HIGH PRIORITY")
                            BroadcastPriority.NORMAL -> Pair(AppPrimaryAccent, "📢 ANNOUNCEMENT")
                        }

                        MeshCard(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = priorityColor.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            priorityText,
                                            color = priorityColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }

                                    Surface(
                                        color = AppSuccessGreen.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "VERIFIED ADMIN ✓",
                                            color = AppSuccessGreen,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Text(bc.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppTextPrimary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(bc.message, fontSize = 14.sp, color = AppTextPrimary)

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = AppBorder)
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Issued by: Campus Admin (${bc.senderConnectMeshId.toString(16).takeLast(6).uppercase()})",
                                        fontSize = 11.sp,
                                        color = AppTextSecondary
                                    )
                                    Text(
                                        dateFormat.format(Date(bc.createdAt)),
                                        fontSize = 11.sp,
                                        color = AppTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showCreateDialog) {
                AlertDialog(
                    onDismissRequest = { showCreateDialog = false },
                    title = { Text("Create Campus Broadcast", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = titleInput,
                                onValueChange = { titleInput = it },
                                label = { Text("Announcement Title") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = messageInput,
                                onValueChange = { messageInput = it },
                                label = { Text("Broadcast Message") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Priority Level:", fontSize = 12.sp, color = AppTextSecondary)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                FilterChip(
                                    selected = selectedPriority == BroadcastPriority.NORMAL,
                                    onClick = { selectedPriority = BroadcastPriority.NORMAL },
                                    label = { Text("Normal") }
                                )
                                FilterChip(
                                    selected = selectedPriority == BroadcastPriority.HIGH,
                                    onClick = { selectedPriority = BroadcastPriority.HIGH },
                                    label = { Text("High") }
                                )
                                FilterChip(
                                    selected = selectedPriority == BroadcastPriority.CRITICAL,
                                    onClick = { selectedPriority = BroadcastPriority.CRITICAL },
                                    label = { Text("Critical") }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (titleInput.isNotBlank() && messageInput.isNotBlank()) {
                                    onCreateBroadcast(titleInput, messageInput, selectedPriority)
                                    titleInput = ""
                                    messageInput = ""
                                    showCreateDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent)
                        ) {
                            Text("Sign & Send Alert")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCreateDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            if (showTrustDialog && onRegisterTrustIssuer != null) {
                AlertDialog(
                    onDismissRequest = {
                        showTrustDialog = false
                        trustErrorMessage = null
                        trustSuccessMessage = null
                    },
                    title = { Text("Campus Trust Anchor Setup", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                "Enroll trusted campus authority ID and EC P-256 public key (Base64 or Hex) to verify offline campus broadcasts.",
                                fontSize = 12.sp,
                                color = AppTextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = issuerIdInput,
                                onValueChange = { issuerIdInput = it; trustErrorMessage = null },
                                label = { Text("Campus Issuer ID (Hex or Dec)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = publicKeyInput,
                                onValueChange = { publicKeyInput = it; trustErrorMessage = null },
                                label = { Text("Issuer Public Key (Base64 / Hex)") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )

                            if (trustErrorMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(trustErrorMessage!!, color = AppEmergencyRed, fontSize = 12.sp)
                            }
                            if (trustSuccessMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(trustSuccessMessage!!, color = AppSuccessGreen, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (issuerIdInput.isNotBlank() && publicKeyInput.isNotBlank()) {
                                    val success = onRegisterTrustIssuer(issuerIdInput, publicKeyInput)
                                    if (success) {
                                        trustSuccessMessage = "Campus Issuer Enrolled Successfully!"
                                        trustErrorMessage = null
                                        issuerIdInput = ""
                                        publicKeyInput = ""
                                    } else {
                                        trustErrorMessage = "Invalid Public Key format or ID"
                                        trustSuccessMessage = null
                                    }
                                } else {
                                    trustErrorMessage = "Please fill in all fields"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent)
                        ) {
                            Text("Enroll Trust Anchor")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showTrustDialog = false
                            trustErrorMessage = null
                            trustSuccessMessage = null
                        }) {
                            Text("Close")
                        }
                    }
                )
            }
        }
    }
}
