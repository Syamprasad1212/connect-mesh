package com.connectmesh.ui.classroom

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.classroom.ClassroomGroup
import com.connectmesh.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomListScreen(
    classrooms: List<ClassroomGroup>,
    canCreateClassroom: Boolean,
    onClassroomClick: (ClassroomGroup) -> Unit,
    onCreateClassroom: (name: String) -> ClassroomGroup?,
    onJoinClassroom: (code: String) -> ClassroomGroup?,
    findClassroomByCode: (code: String) -> ClassroomGroup?
) {
    val clipboardManager = LocalClipboardManager.current

    var showJoinDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf<ClassroomGroup?>(null) }
    var createdSuccessGroup by remember { mutableStateOf<ClassroomGroup?>(null) }

    var classroomCodeInput by remember { mutableStateOf("") }
    var joinTab by remember { mutableStateOf(0) } // 0: Code, 1: QR
    var joinPreviewGroup by remember { mutableStateOf<ClassroomGroup?>(null) }
    var joinError by remember { mutableStateOf<String?>(null) }

    // Form inputs for creation
    var newClassName by remember { mutableStateOf("") }
    var newDepartment by remember { mutableStateOf("") }
    var newSection by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }

    var successMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Classes & Groups", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
                actions = {
                    IconButton(onClick = {
                        classroomCodeInput = ""
                        joinPreviewGroup = null
                        joinError = null
                        showJoinDialog = true
                    }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Join Class", tint = AppPrimaryAccent)
                    }
                    if (canCreateClassroom) {
                        IconButton(onClick = {
                            newClassName = ""
                            newDepartment = ""
                            newSection = ""
                            createError = null
                            showCreateDialog = true
                        }) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Create Classroom", tint = AppSuccessGreen)
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ACTION HEADER BANNER
                item {
                    MeshCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = AppPrimaryAccent.copy(alpha = 0.15f),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.School, contentDescription = null, tint = AppPrimaryAccent)
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Campus Offline Classrooms",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = AppTextPrimary
                                )
                                Text(
                                    "Encrypted section messaging without Internet",
                                    fontSize = 12.sp,
                                    color = AppTextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    classroomCodeInput = ""
                                    joinPreviewGroup = null
                                    joinError = null
                                    showJoinDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Join Class", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            if (canCreateClassroom) {
                                OutlinedButton(
                                    onClick = {
                                        newClassName = ""
                                        newDepartment = ""
                                        newSection = ""
                                        createError = null
                                        showCreateDialog = true
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppSuccessGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(AppSuccessGreen))
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("New Class", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // SUCCESS BANNER IF ANY
                val msg = successMessage
                if (msg != null) {
                    item {
                        Surface(
                            color = AppSuccessGreen.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(msg, color = AppSuccessGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { successMessage = null }) {
                                    Text("Dismiss", color = AppSuccessGreen, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // ENROLLED CLASSROOMS
                if (classrooms.isEmpty()) {
                    item {
                        MeshCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.School, contentDescription = null, tint = AppTextMuted, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("No Enrolled Classrooms", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppTextPrimary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Tap 'Join Class' to enter a code provided by your faculty.",
                                    fontSize = 12.sp,
                                    color = AppTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "YOUR ENROLLED CLASSES (${classrooms.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    items(classrooms) { group ->
                        MeshCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onClassroomClick(group) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = AppSuccessGreen.copy(alpha = 0.15f),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.School, contentDescription = null, tint = AppSuccessGreen)
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(group.groupName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppTextPrimary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = AppSuccessGreen.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "Verified Scope ✓",
                                                color = AppSuccessGreen,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "Code: ${group.groupId} • Key V${group.groupKeyVersion}",
                                        fontSize = 12.sp,
                                        color = AppTextSecondary
                                    )
                                }
                                if (canCreateClassroom) {
                                    IconButton(onClick = { showInviteDialog = group }) {
                                        Icon(Icons.Default.Share, contentDescription = "Invite Students", tint = AppPrimaryAccent)
                                    }
                                }
                                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = AppTextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    // STUDENT JOIN CLASSROOM DIALOG
    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = {
                Text("Join Classroom", fontWeight = FontWeight.Bold, color = AppTextPrimary)
            },
            text = {
                Column {
                    TabRow(
                        selectedTabIndex = joinTab,
                        containerColor = AppSecondaryBackground,
                        contentColor = AppPrimaryAccent
                    ) {
                        Tab(
                            selected = joinTab == 0,
                            onClick = { joinTab = 0 },
                            text = { Text("Classroom Code", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        )
                        Tab(
                            selected = joinTab == 1,
                            onClick = { joinTab = 1 },
                            text = { Text("Scan QR", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (joinTab == 0) {
                        if (joinPreviewGroup == null) {
                            Text(
                                "Enter the Classroom Code provided by your faculty:",
                                fontSize = 13.sp,
                                color = AppTextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = classroomCodeInput,
                                onValueChange = {
                                    classroomCodeInput = it.uppercase()
                                    joinError = null
                                },
                                label = { Text("Classroom Code (e.g. GRP-CSE_A-8F31B9)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppPrimaryAccent,
                                    unfocusedBorderColor = AppBorder,
                                    focusedTextColor = AppTextPrimary,
                                    unfocusedTextColor = AppTextPrimary
                                )
                            )

                            val err = joinError
                            if (err != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        } else {
                            val preview = joinPreviewGroup!!
                            Surface(
                                color = AppPrimaryAccent.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(10.dp),
                                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(AppPrimaryAccent)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("CLASSROOM FOUND", color = AppPrimaryAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(preview.groupName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppTextPrimary)
                                    Text("Scope: ${preview.institutionScope}", fontSize = 12.sp, color = AppTextSecondary)
                                    Text("Code: ${preview.groupId}", fontSize = 12.sp, color = AppPrimaryAccent, fontWeight = FontWeight.Bold)
                                    Text("Creator: 0x${preview.createdByConnectMeshId.toString(16).uppercase()}", fontSize = 11.sp, color = AppTextMuted)
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier.size(140.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0F172A),
                                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(AppPrimaryAccent))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.QrCodeScanner,
                                        contentDescription = null,
                                        tint = AppPrimaryAccent,
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "QR scanning not implemented.\nPlease join using Classroom Code.",
                                fontSize = 12.sp,
                                color = AppTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (joinTab == 0) {
                    if (joinPreviewGroup == null) {
                        Button(
                            onClick = {
                                val found = findClassroomByCode(classroomCodeInput)
                                if (found != null) {
                                    joinPreviewGroup = found
                                    joinError = null
                                } else {
                                    joinError = "Invalid Classroom Code. Please check the code with your faculty."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent)
                        ) {
                            Text("Find Classroom", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = {
                                val preview = joinPreviewGroup!!
                                val joined = onJoinClassroom(preview.groupId)
                                if (joined != null) {
                                    successMessage = "✓ Successfully enrolled in ${preview.groupName}"
                                } else {
                                    successMessage = "Failed to join classroom."
                                }
                                showJoinDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppSuccessGreen)
                        ) {
                            Text("Confirm & Enroll", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (joinPreviewGroup != null) {
                        joinPreviewGroup = null
                    } else {
                        showJoinDialog = false
                    }
                }) {
                    Text(if (joinPreviewGroup != null) "Back" else "Cancel", color = AppTextSecondary)
                }
            },
            containerColor = AppSecondaryBackground
        )
    }

    // FACULTY CREATE CLASSROOM DIALOG
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = {
                Text("Create Classroom Group", fontWeight = FontWeight.Bold, color = AppTextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Authorized Faculty Creation", fontSize = 12.sp, color = AppSuccessGreen, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = newClassName,
                        onValueChange = { newClassName = it },
                        label = { Text("Classroom Name (e.g. CSE-A)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newDepartment,
                        onValueChange = { newDepartment = it },
                        label = { Text("Department (e.g. CSE)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newSection,
                        onValueChange = { newSection = it },
                        label = { Text("Section (e.g. Section A)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val err = createError
                    if (err != null) {
                        Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newClassName.isNotBlank()) {
                            val created = onCreateClassroom(newClassName)
                            if (created != null) {
                                showCreateDialog = false
                                createdSuccessGroup = created
                            } else {
                                createError = "Creation rejected: Only authorized Faculty/Admin can create classrooms."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppSuccessGreen)
                ) {
                    Text("Create Classroom", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = AppTextSecondary)
                }
            },
            containerColor = AppSecondaryBackground
        )
    }

    // CREATED SUCCESS DIALOG (Exposes unique Classroom Code)
    val createdGroup = createdSuccessGroup
    if (createdGroup != null) {
        AlertDialog(
            onDismissRequest = { createdSuccessGroup = null },
            title = { Text("Classroom Created!", fontWeight = FontWeight.Bold, color = AppSuccessGreen) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Share this Classroom Code with your students to let them join:", fontSize = 13.sp, color = AppTextSecondary, textAlign = TextAlign.Center)

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("CLASSROOM CODE", fontSize = 11.sp, color = AppTextMuted, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                createdGroup.groupId,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = AppPrimaryAccent
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(createdGroup.groupId))
                            successMessage = "Copied code ${createdGroup.groupId} to clipboard"
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppPrimaryAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Classroom Code", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { createdSuccessGroup = null },
                    colors = ButtonDefaults.buttonColors(containerColor = AppSuccessGreen)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            containerColor = AppSecondaryBackground
        )
    }

    // INVITE STUDENTS DIALOG
    val inviteGroup = showInviteDialog
    if (inviteGroup != null) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = null },
            title = { Text("Invite Students to ${inviteGroup.groupName}", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(120.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F172A)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.QrCode, contentDescription = null, tint = AppSuccessGreen, modifier = Modifier.size(64.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Classroom Code:", fontSize = 12.sp, color = AppTextSecondary)
                    Text(
                        inviteGroup.groupId,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AppPrimaryAccent
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(inviteGroup.groupId))
                            successMessage = "Copied code ${inviteGroup.groupId} to clipboard"
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppPrimaryAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Code", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "QR Bitmap Generation: NOT IMPLEMENTED\nText code works 100% independently.",
                        fontSize = 11.sp,
                        color = AppTextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showInviteDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            containerColor = AppSecondaryBackground
        )
    }
}
