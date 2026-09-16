package com.connectmesh.ui.classroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.connectmesh.classroom.ClassroomGroup
import com.connectmesh.classroom.ClassroomMessage
import com.connectmesh.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomDetailScreen(
    group: ClassroomGroup,
    messages: List<ClassroomMessage>,
    onSendMessage: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(group.groupName, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(color = AppSuccessGreen.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                Text("CLASSROOM ENCRYPTED ✓", color = AppSuccessGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text("Scope: ${group.institutionScope} • Group Key V${group.groupKeyVersion}", fontSize = 11.sp, color = AppTextSecondary, fontFamily = FontFamily.Monospace)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages.reversed()) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isSelf) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (msg.isSelf) AppPrimaryAccent else AppSecondaryBackground,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (!msg.isSelf) {
                                    Text(
                                        "CM-...${msg.senderId.toString(16).takeLast(4).uppercase()}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = AppSuccessGreen
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Text(
                                    msg.text,
                                    color = if (msg.isSelf) Color.Black else AppTextPrimary,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    timeFormat.format(Date(msg.timestamp)),
                                    fontSize = 10.sp,
                                    color = if (msg.isSelf) Color.Black.copy(alpha = 0.6f) else AppTextSecondary,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }
                    }
                }
            }

            // INPUT BAR
            Surface(
                color = AppSecondaryBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Send classroom message...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppPrimaryAccent,
                            unfocusedBorderColor = AppBorder,
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onSendMessage(textInput)
                                textInput = ""
                            }
                        },
                        modifier = Modifier.background(AppPrimaryAccent, RoundedCornerShape(20.dp))
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.Black)
                    }
                }
            }
        }
    }
}
