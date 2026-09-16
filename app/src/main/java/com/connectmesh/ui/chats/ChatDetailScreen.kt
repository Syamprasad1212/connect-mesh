package com.connectmesh.ui.chats

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.connectmesh.file.FileManager
import com.connectmesh.service.MeshForegroundService
import com.connectmesh.service.MeshForegroundService.ChatMessage
import com.connectmesh.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    title: String,
    recipientId: Long,
    hopCount: Int = 1,
    nextHopNickname: String? = null,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    onSendVoice: (ByteArray) -> Unit,
    onSendFile: (Uri) -> Unit,
    onCancelFile: (Long) -> Unit,
    onStartRecordVoice: () -> Boolean,
    onStopRecordVoice: () -> ByteArray?,
    onPlayVoice: (ByteArray) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var textState by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordDurationSeconds by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    var pendingFileUri by remember { mutableStateOf<Uri?>(null) }
    var pendingFileName by remember { mutableStateOf("") }
    var pendingFileSize by remember { mutableStateOf(0L) }
    var showFileConfirmDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val details = FileManager.getFileDetailsFromUri(context, uri)
            if (details != null) {
                if (details.second > FileManager.MAX_FILE_SIZE_BYTES) {
                    Toast.makeText(context, "Maximum file size is 50 MB.", Toast.LENGTH_LONG).show()
                } else {
                    pendingFileUri = uri
                    pendingFileName = details.first
                    pendingFileSize = details.second
                    showFileConfirmDialog = true
                }
            }
        }
    }

    val displayName = formatPeerDisplayName(title, recipientId)
    val shortId = formatPeerShortId(recipientId)

    // Timer effect for active recording
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordDurationSeconds = 0
            while (isRecording) {
                delay(1000)
                recordDurationSeconds++
            }
        }
    }

    // Auto-scroll to newest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (showFileConfirmDialog && pendingFileUri != null) {
        val sizeMbStr = String.format(Locale.getDefault(), "%.2f MB", pendingFileSize / (1024f * 1024f))
        AlertDialog(
            onDismissRequest = { showFileConfirmDialog = false },
            icon = { Icon(Icons.Default.AttachFile, contentDescription = null, tint = AppPrimaryAccent) },
            title = { Text("ATTACH FILE", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
            text = {
                Column {
                    Text("Selected File:", style = MaterialTheme.typography.labelMedium, color = AppTextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(pendingFileName, fontWeight = FontWeight.Bold, color = AppTextPrimary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("Size: $sizeMbStr", fontSize = 13.sp, color = AppTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "File will be streamed chunk-by-chunk over BLE mesh network without Internet.",
                        fontSize = 11.sp,
                        color = AppTextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingFileUri
                        showFileConfirmDialog = false
                        if (uri != null) {
                            onSendFile(uri)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent)
                ) {
                    Text("SEND FILE", fontWeight = FontWeight.Bold, color = AppBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFileConfirmDialog = false }) {
                    Text("CANCEL", color = AppTextSecondary)
                }
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppTextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(shortId, fontSize = 12.sp, color = AppTextMuted)
                        }
                        ConnectionBadge(hopCount = hopCount, nextHopNickname = nextHopNickname)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        MeshEmptyState(
                            icon = Icons.Default.Mic,
                            title = "No messages yet",
                            description = "Send a text, voice, or file message below to start the conversation over BLE mesh."
                        )
                    }
                } else {
                    items(messages) { msg ->
                        ModernChatBubble(
                            msg = msg,
                            onPlayVoice = onPlayVoice,
                            onOpenFile = { path, mime -> openFileWithSystemApp(context, path, mime) },
                            onCancelFile = onCancelFile
                        )
                    }
                }
            }

            // RECORDING CARD OR COMPOSER BAR
            if (isRecording) {
                Surface(
                    color = AppSecondaryBackground,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(AppEmergencyRed, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Recording... ${String.format("%02d:%02d", recordDurationSeconds / 60, recordDurationSeconds % 60)}",
                                fontWeight = FontWeight.Bold,
                                color = AppEmergencyRed
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    isRecording = false
                                    onStopRecordVoice() // Discard recording
                                }
                            ) {
                                Text("Cancel", color = AppTextSecondary)
                            }

                            Button(
                                onClick = {
                                    isRecording = false
                                    val voiceBytes = onStopRecordVoice()
                                    if (voiceBytes != null && voiceBytes.isNotEmpty()) {
                                        onSendVoice(voiceBytes)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Send Voice Note", tint = AppBackground)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Send", color = AppBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                Surface(
                    color = AppSecondaryBackground,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ATTACHMENT FILE BUTTON [ 📎 ]
                        IconButton(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = AppPrimaryAccent)
                        }

                        // VOICE RECORD BUTTON [ 🎙 ]
                        IconButton(
                            onClick = {
                                if (onStartRecordVoice()) {
                                    isRecording = true
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Record Audio", tint = AppPrimaryAccent)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        OutlinedTextField(
                            value = textState,
                            onValueChange = { textState = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type a message...", color = AppTextMuted) },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppPrimaryAccent,
                                unfocusedBorderColor = AppBorder,
                                focusedContainerColor = AppElevatedSurface,
                                unfocusedContainerColor = AppElevatedSurface,
                                focusedTextColor = AppTextPrimary,
                                unfocusedTextColor = AppTextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                if (textState.isNotBlank()) {
                                    onSendMessage(textState)
                                    textState = ""
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(if (textState.isNotBlank()) AppPrimaryAccent else AppElevatedSurface, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (textState.isNotBlank()) AppBackground else AppTextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModernChatBubble(
    msg: ChatMessage,
    onPlayVoice: (ByteArray) -> Unit,
    onOpenFile: (String, String) -> Unit,
    onCancelFile: (Long) -> Unit
) {
    val align = if (msg.isSelf) Alignment.End else Alignment.Start
    val bubbleColor = if (msg.isSelf) AppElevatedSurface else AppSecondaryBackground
    val border = AppBorder
    var isPlaying by remember { mutableStateOf(false) }

    val timeStr = remember(msg.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(bubbleColor, shape = RoundedCornerShape(18.dp))
                .border(1.dp, border, shape = RoundedCornerShape(18.dp))
                .padding(12.dp)
        ) {
            Column {
                if (msg.isFile) {
                    val sizeMbStr = String.format(Locale.getDefault(), "%.2f MB", msg.fileSize / (1024f * 1024f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = AppPrimaryAccent.copy(alpha = 0.15f),
                            shape = CircleShape,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val icon = when {
                                    msg.mimeType.startsWith("image/") -> Icons.Default.Image
                                    msg.mimeType.startsWith("video/") -> Icons.Default.Movie
                                    msg.mimeType.startsWith("audio/") -> Icons.Default.MusicNote
                                    msg.mimeType.contains("pdf") -> Icons.Default.PictureAsPdf
                                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                }
                                Icon(icon, contentDescription = null, tint = AppPrimaryAccent, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                msg.fileName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AppTextPrimary,
                                maxLines = 1
                            )
                            Text(sizeMbStr, fontSize = 11.sp, color = AppTextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    when (msg.fileStatus) {
                        FileManager.Status.SENDING, FileManager.Status.RECEIVING -> {
                            val statusLabel = if (msg.fileStatus == FileManager.Status.SENDING) "Sending..." else "Receiving..."
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("$statusLabel ${msg.fileProgress}%", fontSize = 11.sp, color = AppPrimaryAccent, fontWeight = FontWeight.Bold)
                                TextButton(
                                    onClick = { onCancelFile(msg.fileTransferId) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Cancel", fontSize = 11.sp, color = AppEmergencyRed)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { msg.fileProgress / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = AppPrimaryAccent,
                                trackColor = AppBorder,
                            )
                        }
                        FileManager.Status.REASSEMBLING -> {
                            Text("Reassembling file...", fontSize = 11.sp, color = AppWarningAmber, fontWeight = FontWeight.Bold)
                        }
                        FileManager.Status.VERIFYING -> {
                            Text("Verifying SHA-256...", fontSize = 11.sp, color = AppWarningAmber, fontWeight = FontWeight.Bold)
                        }
                        FileManager.Status.COMPLETED -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (msg.isSelf) "Delivered ✓" else "Completed ✓", fontSize = 11.sp, color = AppSuccessGreen, fontWeight = FontWeight.Bold)
                                if (!msg.localFilePath.isNullBuOrBlank()) {
                                    Button(
                                        onClick = { onOpenFile(msg.localFilePath!!, msg.mimeType) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                    ) {
                                        Text("Open", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppBackground)
                                    }
                                }
                            }
                        }
                        FileManager.Status.FAILED -> {
                            Text("File Transfer Failed", fontSize = 11.sp, color = AppEmergencyRed, fontWeight = FontWeight.Bold)
                        }
                        FileManager.Status.CANCELLED -> {
                            Text("File Transfer Cancelled", fontSize = 11.sp, color = AppTextMuted)
                        }
                        else -> {
                            Text("Preparing transfer...", fontSize = 11.sp, color = AppTextMuted)
                        }
                    }
                } else if (msg.isVoice && msg.voiceData != null) {
                    val sizeKb = (msg.voiceData.size + 1023) / 1024
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                isPlaying = !isPlaying
                                onPlayVoice(msg.voiceData)
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(AppPrimaryAccent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause Voice",
                                tint = AppBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf(12, 24, 16, 32, 20, 28, 14, 22, 18, 26).forEach { height ->
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(height.dp)
                                            .background(if (isPlaying) AppPrimaryAccent else AppTextMuted, RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Voice Note · ${sizeKb} KB", fontSize = 11.sp, color = AppTextSecondary)
                        }
                    }
                } else {
                    Text(
                        msg.text,
                        fontSize = 15.sp,
                        color = AppTextPrimary,
                        lineHeight = 20.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(timeStr, fontSize = 10.sp, color = AppTextMuted)
                    if (msg.isSelf) {
                        val (statusStr, statusColor) = when {
                            msg.isFile -> when (msg.fileStatus) {
                                FileManager.Status.COMPLETED -> Pair("Delivered ✓", AppSuccessGreen)
                                FileManager.Status.CANCELLED -> Pair("Cancelled", AppTextMuted)
                                FileManager.Status.FAILED -> Pair("Failed", AppEmergencyRed)
                                FileManager.Status.SENDING -> Pair("Sending... ${msg.fileProgress}%", AppWarningAmber)
                                FileManager.Status.WAITING_FOR_ACK -> Pair("Waiting for ACK...", AppWarningAmber)
                                else -> Pair("Preparing...", AppTextMuted)
                            }
                            msg.deliveryStatus == MeshForegroundService.DeliveryStatus.DELIVERED || msg.isDelivered -> Pair("Delivered ✓", AppSuccessGreen)
                            msg.deliveryStatus == MeshForegroundService.DeliveryStatus.FAILED -> Pair("Failed", AppEmergencyRed)
                            msg.deliveryStatus == MeshForegroundService.DeliveryStatus.CANCELLED -> Pair("Cancelled", AppTextMuted)
                            else -> Pair("Sending...", AppWarningAmber)
                        }
                        Text(statusStr, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = statusColor)
                    }
                }
            }
        }
    }
}

private fun String?.isNullBuOrBlank(): Boolean = this == null || this.isBlank()

private fun openFileWithSystemApp(context: Context, filePath: String, mimeType: String) {
    try {
        val file = File(filePath)
        if (!file.exists() || !file.canRead() || file.length() == 0L) {
            Toast.makeText(context, "File is not ready or incomplete", Toast.LENGTH_SHORT).show()
            return
        }
        val effectiveMime = if (mimeType.isNotBlank() && mimeType != "application/octet-stream") {
            mimeType
        } else {
            com.connectmesh.file.FileManager.getMimeTypeFromExtension(file.extension)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, effectiveMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No compatible app found to open this file.", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
