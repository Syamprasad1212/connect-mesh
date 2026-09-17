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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.broadcast.BroadcastPriority
import com.connectmesh.broadcast.CampusEnrollmentDetails
import com.connectmesh.broadcast.CollegeBroadcast
import com.connectmesh.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private enum class SetupTab { JOIN, CREATE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusBroadcastScreen(
    broadcasts: List<CollegeBroadcast>,
    enrolledCampusScope: String? = "COLLEGE:CAMPUS_01",
    canCreateBroadcast: Boolean,
    localEnrollmentDetails: CampusEnrollmentDetails? = null,
    onCreateAdminCampus: ((scope: String) -> CampusEnrollmentDetails?)? = null,
    onCreateBroadcast: (title: String, message: String, priority: BroadcastPriority) -> Unit,
    onRegisterTrustIssuer: ((issuerId: String, publicKey: String, campusScope: String) -> Boolean)? = null
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showTrustDialog by remember { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf("") }
    var messageInput by remember { mutableStateOf("") }
    var selectedPriority by remember { mutableStateOf(BroadcastPriority.NORMAL) }

    var selectedSetupTab by remember { mutableStateOf(if (canCreateBroadcast) SetupTab.CREATE else SetupTab.JOIN) }
    var createdDetails by remember(localEnrollmentDetails) { mutableStateOf(localEnrollmentDetails) }

    var campusScopeInput by remember(enrolledCampusScope) { mutableStateOf(enrolledCampusScope?.removePrefix("COLLEGE:") ?: "CAMPUS_01") }
    var issuerIdInput by remember { mutableStateOf("") }
    var publicKeyInput by remember { mutableStateOf("") }
    var pasteInput by remember { mutableStateOf("") }

    var trustErrorMessage by remember { mutableStateOf<String?>(null) }
    var trustSuccessMessage by remember { mutableStateOf<String?>(null) }

    val clipboardManager = LocalClipboardManager.current
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Campus Announcements", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
                actions = {
                    if (onRegisterTrustIssuer != null || onCreateAdminCampus != null) {
                        IconButton(onClick = { showTrustDialog = true }) {
                            Icon(Icons.Default.School, contentDescription = "Campus Setup", tint = AppPrimaryAccent)
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
                        Surface(
                            color = if (enrolledCampusScope != null) AppSuccessGreen.copy(alpha = 0.15f) else AppEmergencyRed.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.School,
                                        contentDescription = null,
                                        tint = if (enrolledCampusScope != null) AppSuccessGreen else AppEmergencyRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (enrolledCampusScope != null) "✓ ENROLLED CAMPUS: ${enrolledCampusScope.removePrefix("COLLEGE:")}" else "CAMPUS NOT ENROLLED",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (enrolledCampusScope != null) AppSuccessGreen else AppEmergencyRed
                                    )
                                }
                                if (onRegisterTrustIssuer != null || onCreateAdminCampus != null) {
                                    TextButton(
                                        onClick = { showTrustDialog = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Campus Setup", fontSize = 11.sp, color = AppPrimaryAccent, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

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

            if (showTrustDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showTrustDialog = false
                        trustErrorMessage = null
                        trustSuccessMessage = null
                    },
                    title = { Text("Campus Setup & Enrollment", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                FilterChip(
                                    selected = selectedSetupTab == SetupTab.JOIN,
                                    onClick = { selectedSetupTab = SetupTab.JOIN },
                                    label = { Text("Join Campus") }
                                )
                                if (canCreateBroadcast || onCreateAdminCampus != null) {
                                    FilterChip(
                                        selected = selectedSetupTab == SetupTab.CREATE,
                                        onClick = { selectedSetupTab = SetupTab.CREATE },
                                        label = { Text("Create Campus") }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (selectedSetupTab == SetupTab.CREATE) {
                                Text(
                                    "Configure this device as Campus Admin and obtain enrollment credentials for student devices.",
                                    fontSize = 12.sp,
                                    color = AppTextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = campusScopeInput,
                                    onValueChange = { campusScopeInput = it; trustErrorMessage = null },
                                    label = { Text("Campus Scope (e.g. CAMPUS_01)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        if (campusScopeInput.isNotBlank() && onCreateAdminCampus != null) {
                                            val details = onCreateAdminCampus(campusScopeInput)
                                            if (details != null) {
                                                createdDetails = details
                                                trustSuccessMessage = "Campus ${details.campusScope} created & active!"
                                                trustErrorMessage = null
                                            } else {
                                                trustErrorMessage = "Failed to create campus authority"
                                            }
                                        } else {
                                            trustErrorMessage = "Please enter a valid Campus Scope"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent)
                                ) {
                                    Text("Create & Register Campus Authority")
                                }

                                val currentDetails = createdDetails ?: localEnrollmentDetails
                                if (currentDetails != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Surface(
                                        color = AppSecondaryBackground,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("CAMPUS ENROLLMENT DETAILS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppPrimaryAccent)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Scope: ${currentDetails.campusScope}", fontSize = 12.sp, color = AppTextPrimary)
                                            Text("Authority ID: ${currentDetails.authorityIdHex}", fontSize = 12.sp, color = AppTextPrimary)
                                            Text("Public Key: ${currentDetails.authorityPublicKeyBase64.take(24)}...", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppTextSecondary)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(
                                                onClick = {
                                                    clipboardManager.setText(AnnotatedString(currentDetails.toCopyableString()))
                                                    trustSuccessMessage = "Copied enrollment config to clipboard!"
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = AppSuccessGreen)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Copy Enrollment Details", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    "Enroll this device into a campus to receive official verified announcements offline.",
                                    fontSize = 12.sp,
                                    color = AppTextSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = pasteInput,
                                    onValueChange = { input ->
                                        pasteInput = input
                                        val parsed = CampusEnrollmentDetails.parse(input)
                                        if (parsed != null) {
                                            campusScopeInput = parsed.campusScope.removePrefix("COLLEGE:")
                                            issuerIdInput = parsed.authorityIdHex
                                            publicKeyInput = parsed.authorityPublicKeyBase64
                                            trustSuccessMessage = "Auto-filled from copied configuration!"
                                            trustErrorMessage = null
                                        }
                                    },
                                    label = { Text("Paste Full Config (Optional)") },
                                    placeholder = { Text("SCOPE|ISSUER_ID|PUBLIC_KEY") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            val clip = clipboardManager.getText()?.text
                                            if (!clip.isNullOrBlank()) {
                                                val parsed = CampusEnrollmentDetails.parse(clip)
                                                if (parsed != null) {
                                                    pasteInput = clip
                                                    campusScopeInput = parsed.campusScope.removePrefix("COLLEGE:")
                                                    issuerIdInput = parsed.authorityIdHex
                                                    publicKeyInput = parsed.authorityPublicKeyBase64
                                                    trustSuccessMessage = "Auto-filled from clipboard!"
                                                    trustErrorMessage = null
                                                } else {
                                                    trustErrorMessage = "Clipboard content is not a valid campus config"
                                                }
                                            } else {
                                                trustErrorMessage = "Clipboard is empty"
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Auto-Fill from Clipboard", fontSize = 11.sp)
                                    }
                                }

                                OutlinedTextField(
                                    value = campusScopeInput,
                                    onValueChange = { campusScopeInput = it; trustErrorMessage = null },
                                    label = { Text("Campus Scope (e.g. CAMPUS_01)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = issuerIdInput,
                                    onValueChange = { issuerIdInput = it; trustErrorMessage = null },
                                    label = { Text("Campus Authority ID (Hex or Dec)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = publicKeyInput,
                                    onValueChange = { publicKeyInput = it; trustErrorMessage = null },
                                    label = { Text("Campus Authority Key (Base64 / Hex)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2
                                )
                            }

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
                        if (selectedSetupTab == SetupTab.JOIN && onRegisterTrustIssuer != null) {
                            Button(
                                onClick = {
                                    if (issuerIdInput.isNotBlank() && publicKeyInput.isNotBlank() && campusScopeInput.isNotBlank()) {
                                        val success = onRegisterTrustIssuer(issuerIdInput, publicKeyInput, campusScopeInput)
                                        if (success) {
                                            trustSuccessMessage = "Enrolled in ${campusScopeInput.trim().uppercase()} Successfully!"
                                            trustErrorMessage = null
                                            issuerIdInput = ""
                                            publicKeyInput = ""
                                            pasteInput = ""
                                        } else {
                                            trustErrorMessage = "Invalid Key format or ID"
                                            trustSuccessMessage = null
                                        }
                                    } else {
                                        trustErrorMessage = "Please fill in all fields"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent)
                            ) {
                                Text("Enroll Campus")
                            }
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

