package com.connectmesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.classroom.ClassroomGroup
import com.connectmesh.identity.PeerIdentity
import com.connectmesh.service.MeshForegroundService
import com.connectmesh.ui.broadcast.CampusBroadcastScreen
import com.connectmesh.ui.classroom.ClassroomDetailScreen
import com.connectmesh.ui.classroom.ClassroomListScreen
import com.connectmesh.ui.chats.ChatDetailScreen
import com.connectmesh.ui.chats.ChatListScreen
import com.connectmesh.ui.diagnostics.DiagnosticsScreen
import com.connectmesh.ui.disaster.MountainDisasterScreen
import com.connectmesh.ui.more.MoreScreen
import com.connectmesh.ui.peers.PeerDetailScreen
import com.connectmesh.ui.peers.PeerListScreen
import com.connectmesh.ui.profile.ProfileScreen
import com.connectmesh.ui.sos.SosScreen
import com.connectmesh.ui.theme.*
import com.connectmesh.ui.topology.MeshTopologyScreen

sealed class Screen(val route: String, val title: String) {
    object Chats : Screen("chats", "Chats")
    object People : Screen("people", "People")
    object Classrooms : Screen("classrooms", "Classrooms")
    object Campus : Screen("campus", "Campus")
    object Topology : Screen("topology", "Mesh")
    object Sos : Screen("sos", "SOS")
    object More : Screen("more", "More")
    object Mountain : Screen("mountain", "Mountain")
    object Profile : Screen("profile", "Profile")
    object Diagnostics : Screen("diagnostics", "Diagnostics")
    object PeerDetail : Screen("peer_detail", "Peer Detail")
    object ChatDetail : Screen("chat_detail", "Chat")
    object ClassroomDetail : Screen("classroom_detail", "Classroom Detail")
}

@Composable
fun ConnectMeshApp(
    service: MeshForegroundService?,
    showBluetoothPrompt: Boolean = false,
    onRequestEnableBluetooth: () -> Unit = {},
    onDismissBluetoothPrompt: () -> Unit = {}
) {
    ConnectMeshTheme {
        if (showBluetoothPrompt) {
            AlertDialog(
                onDismissRequest = onDismissBluetoothPrompt,
                title = { Text("Bluetooth Required", color = AppTextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text("Connect-Mesh uses Bluetooth to communicate with nearby devices. Please turn on Bluetooth to continue.", color = AppTextSecondary) },
                confirmButton = {
                    Button(
                        onClick = onRequestEnableBluetooth,
                        colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent)
                    ) {
                        Text("Turn On Bluetooth", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissBluetoothPrompt) {
                        Text("Cancel", color = AppTextSecondary)
                    }
                },
                containerColor = AppElevatedSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (service == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppPrimaryAccent)
            }
            return@ConnectMeshTheme
        }

        var currentScreen by remember { mutableStateOf<Screen>(Screen.Chats) }
        var activeChatPeer by remember { mutableStateOf<PeerIdentity?>(null) }
        var selectedPeerDetail by remember { mutableStateOf<PeerIdentity?>(null) }
        var activeClassroomGroup by remember { mutableStateOf<ClassroomGroup?>(null) }

        val peers by service.peerManager.peersFlow.collectAsState()
        val messages by service.messagesFlow.collectAsState()
        val sosAlerts by service.sosAlertsFlow.collectAsState()

        val activeRemoteSos = sosAlerts.lastOrNull { it.senderId != service.deviceIdentity.deviceId }

        Scaffold(
            containerColor = AppBackground,
            bottomBar = {
                if (currentScreen != Screen.ChatDetail && currentScreen != Screen.Diagnostics && currentScreen != Screen.PeerDetail && currentScreen != Screen.ClassroomDetail) {
                    NavigationBar(
                        containerColor = AppSecondaryBackground,
                        tonalElevation = 8.dp
                    ) {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Chat, contentDescription = "Chats") },
                            label = { Text("Chats", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                            selected = currentScreen == Screen.Chats,
                            onClick = { currentScreen = Screen.Chats },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppBackground,
                                selectedTextColor = AppPrimaryAccent,
                                indicatorColor = AppPrimaryAccent,
                                unselectedIconColor = AppTextSecondary,
                                unselectedTextColor = AppTextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.People, contentDescription = "People") },
                            label = { Text("People", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                            selected = currentScreen == Screen.People,
                            onClick = { currentScreen = Screen.People },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppBackground,
                                selectedTextColor = AppPrimaryAccent,
                                indicatorColor = AppPrimaryAccent,
                                unselectedIconColor = AppTextSecondary,
                                unselectedTextColor = AppTextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.School, contentDescription = "Classrooms") },
                            label = { Text("Classes", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                            selected = currentScreen == Screen.Classrooms,
                            onClick = { currentScreen = Screen.Classrooms },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppBackground,
                                selectedTextColor = AppPrimaryAccent,
                                indicatorColor = AppPrimaryAccent,
                                unselectedIconColor = AppTextSecondary,
                                unselectedTextColor = AppTextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Campaign, contentDescription = "Campus") },
                            label = { Text("Campus", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                            selected = currentScreen == Screen.Campus,
                            onClick = { currentScreen = Screen.Campus },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppBackground,
                                selectedTextColor = AppPrimaryAccent,
                                indicatorColor = AppPrimaryAccent,
                                unselectedIconColor = AppTextSecondary,
                                unselectedTextColor = AppTextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Hub, contentDescription = "Mesh") },
                            label = { Text("Mesh", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                            selected = currentScreen == Screen.Topology,
                            onClick = { currentScreen = Screen.Topology },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppBackground,
                                selectedTextColor = AppPrimaryAccent,
                                indicatorColor = AppPrimaryAccent,
                                unselectedIconColor = AppTextSecondary,
                                unselectedTextColor = AppTextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Warning, contentDescription = "SOS") },
                            label = { Text("SOS", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                            selected = currentScreen == Screen.Sos,
                            onClick = { currentScreen = Screen.Sos },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = AppEmergencyRed,
                                indicatorColor = AppEmergencyRed,
                                unselectedIconColor = AppEmergencyRed,
                                unselectedTextColor = AppEmergencyRed
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "More") },
                            label = { Text("More", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                            selected = currentScreen == Screen.More || currentScreen == Screen.Mountain || currentScreen == Screen.Profile || currentScreen == Screen.Diagnostics,
                            onClick = { currentScreen = Screen.More },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppBackground,
                                selectedTextColor = AppPrimaryAccent,
                                indicatorColor = AppPrimaryAccent,
                                unselectedIconColor = AppTextSecondary,
                                unselectedTextColor = AppTextSecondary
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                Column(modifier = Modifier.fillMaxSize()) {

                    // HIGH-PRIORITY INCOMING SOS BANNER
                    if (activeRemoteSos != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            color = Color(0xFF450A0A),
                            shape = RoundedCornerShape(14.dp),
                            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AppEmergencyRed))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AppEmergencyRed)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "🚨 EMERGENCY SOS ALERT",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        "${formatPeerDisplayName(activeRemoteSos.senderNickname, activeRemoteSos.senderId)}: \"${activeRemoteSos.message}\"",
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFFCA5A5),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        formatPeerShortId(activeRemoteSos.senderId),
                                        color = AppTextMuted,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (!activeRemoteSos.isAcknowledgedByMe) {
                                    Button(
                                        onClick = { service.sendSosAck(activeRemoteSos.packetId, activeRemoteSos.senderId) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AppSuccessGreen)
                                    ) {
                                        Text("ACKNOWLEDGE", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                                    }
                                } else {
                                    IconButton(
                                        onClick = { service.dismissSosAlert(activeRemoteSos.packetId) }
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "Dismiss", tint = AppSuccessGreen)
                                    }
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        when (currentScreen) {
                            Screen.Chats -> ChatListScreen(
                                userNickname = service.deviceIdentity.nickname,
                                peers = peers,
                                onPeerClick = { peer ->
                                    activeChatPeer = peer
                                    currentScreen = Screen.ChatDetail
                                }
                            )
                            Screen.People -> PeerListScreen(
                                peers = peers,
                                onPeerClick = { peer ->
                                    selectedPeerDetail = peer
                                    currentScreen = Screen.PeerDetail
                                }
                            )
                            Screen.Classrooms -> {
                                val classrooms by service.classroomsFlow.collectAsState()
                                ClassroomListScreen(
                                    classrooms = classrooms,
                                    canCreateClassroom = service.authorizationManager.hasRole(com.connectmesh.auth.UserRole.TEACHER),
                                    onClassroomClick = { grp ->
                                        activeClassroomGroup = grp
                                        currentScreen = Screen.ClassroomDetail
                                    },
                                    onCreateClassroom = { name ->
                                        service.createClassroom(name)
                                    },
                                    onJoinClassroom = { code ->
                                        service.joinClassroomByCode(code)
                                    },
                                    findClassroomByCode = { code ->
                                        service.classroomManager.findClassroomByCode(code)
                                    }
                                )
                            }
                            Screen.ClassroomDetail -> {
                                val grp = activeClassroomGroup
                                if (grp != null) {
                                    val lastUpdate by service.classroomMessagesUpdateFlow.collectAsState()
                                    val classroomMsgs = remember(grp.groupId, lastUpdate) {
                                        service.classroomManager.getMessages(grp.groupId)
                                    }
                                    ClassroomDetailScreen(
                                        group = grp,
                                        messages = classroomMsgs,
                                        onSendMessage = { text ->
                                            service.sendClassroomMessage(grp.groupId, text)
                                        },
                                        onBackClick = {
                                            currentScreen = Screen.Classrooms
                                        }
                                    )
                                }
                            }
                            Screen.Campus -> {
                                val broadcasts by service.campusBroadcastsFlow.collectAsState()
                                CampusBroadcastScreen(
                                    broadcasts = broadcasts,
                                    canCreateBroadcast = service.authorizationManager.hasRole(com.connectmesh.auth.UserRole.ADMIN),
                                    onCreateBroadcast = { title, msg, priority ->
                                        service.createCampusBroadcast(title, msg, priority)
                                    },
                                    onRegisterTrustIssuer = { issuerIdStr, pubKeyStr ->
                                        val cleanIdStr = issuerIdStr.trim().removePrefix("0x").removePrefix("0X")
                                        val issuerId = cleanIdStr.toLongOrNull(16)
                                            ?: cleanIdStr.toLongOrNull()
                                            ?: 0L
                                        service.registerTrustedCampusIssuer(issuerId, pubKeyStr)
                                    }
                                )
                            }
                            Screen.PeerDetail -> {
                                val p = selectedPeerDetail
                                if (p != null) {
                                    PeerDetailScreen(
                                        peer = p,
                                        allPeers = peers,
                                        onStartChat = {
                                            activeChatPeer = p
                                            currentScreen = Screen.ChatDetail
                                        },
                                        onBackClick = {
                                            currentScreen = Screen.People
                                        }
                                    )
                                }
                            }
                            Screen.Topology -> MeshTopologyScreen(
                                localPeerId = service.deviceIdentity.deviceId,
                                userNickname = service.deviceIdentity.nickname,
                                peers = peers
                            )
                            Screen.Mountain -> {
                                val pendingOutbox = remember(peers, messages) { service.getPendingOutboxEntries() }
                                MountainDisasterScreen(
                                    localDeviceId = service.deviceIdentity.deviceId,
                                    userNickname = service.deviceIdentity.nickname,
                                    peers = peers,
                                    sosAlerts = sosAlerts,
                                    routeTable = service.routeTable,
                                    staticRelayController = service.staticRelayController,
                                    pendingOutboxEntries = pendingOutbox,
                                    onSendSos = { customMsg ->
                                        service.sendSosAlert(customMsg)
                                    },
                                    onAcknowledgeSos = { packetId, originatorId ->
                                        service.sendSosAck(packetId, originatorId)
                                    }
                                )
                            }
                            Screen.More -> MoreScreen(
                                userNickname = service.deviceIdentity.nickname,
                                deviceId = service.deviceIdentity.deviceId,
                                onNavigateToMountain = { currentScreen = Screen.Mountain },
                                onNavigateToProfile = { currentScreen = Screen.Profile },
                                onNavigateToDiagnostics = { currentScreen = Screen.Diagnostics }
                            )
                            Screen.Sos -> SosScreen(
                                currentDeviceId = service.deviceIdentity.deviceId,
                                sosAlerts = sosAlerts,
                                onSendSos = { customMsg ->
                                    service.sendSosAlert(customMsg)
                                },
                                onAcknowledgeSos = { packetId, originatorId ->
                                    service.sendSosAck(packetId, originatorId)
                                }
                            )
                            Screen.Profile -> ProfileScreen(
                                deviceId = service.deviceIdentity.deviceId,
                                currentNickname = service.deviceIdentity.nickname,
                                connectedPeerCount = peers.count { it.hopCount == 1 },
                                totalKnownPeers = peers.size,
                                isMeshActive = service.isBleStackRunning,
                                onNicknameChange = { newNick ->
                                    service.updateNickname(newNick)
                                },
                                onToggleMesh = { start ->
                                    if (start) service.startBleStack() else service.stopMeshService()
                                },
                                onOpenDiagnostics = {
                                    currentScreen = Screen.Diagnostics
                                },
                                onSendPingTest = {
                                    peers.forEach { peer ->
                                        service.sendPing(peer.peerId)
                                    }
                                }
                            )
                            Screen.Diagnostics -> DiagnosticsScreen(
                                deviceId = service.deviceIdentity.deviceId,
                                connectedCount = peers.count { it.hopCount == 1 },
                                knownCount = peers.size,
                                relayManager = service.relayManager,
                                routeTable = service.routeTable,
                                onSendPingTest = {
                                    peers.forEach { peer ->
                                        service.sendPing(peer.peerId)
                                    }
                                },
                                onSendMultiHopTest = {
                                    val remotePeers = peers.filter { it.hopCount > 1 }
                                    remotePeers.forEach { peer ->
                                        service.sendMessage(peer.peerId, "Multi-Hop Test Message (A -> B -> C)")
                                    }
                                },
                                onBackClick = {
                                    currentScreen = Screen.Profile
                                }
                            )
                            Screen.ChatDetail -> {
                                val peer = activeChatPeer
                                val title = peer?.nickname ?: "Direct Chat"
                                val recipientId = peer?.peerId ?: 0L
                                val hopCount = peer?.hopCount ?: 1
                                val nextHopName = if (peer != null && peer.hopCount > 1) {
                                    peers.find { it.peerId == peer.nextHopPeerId }?.let { formatPeerDisplayName(it.nickname, it.peerId) }
                                } else null

                                val filteredMessages = messages.filter {
                                    it.senderId == recipientId || it.recipientId == recipientId
                                }
                                ChatDetailScreen(
                                    title = title,
                                    recipientId = recipientId,
                                    hopCount = hopCount,
                                    nextHopNickname = nextHopName,
                                    messages = filteredMessages,
                                    onSendMessage = { text ->
                                        service.sendMessage(recipientId, text)
                                    },
                                    onSendVoice = { voiceBytes ->
                                        service.sendVoiceNote(recipientId, voiceBytes)
                                    },
                                    onSendFile = { uri ->
                                        service.sendFile(recipientId, uri)
                                    },
                                    onCancelFile = { transferId ->
                                        service.cancelFileTransfer(transferId)
                                    },
                                    onStartRecordVoice = {
                                        service.voiceManager.startRecording()
                                    },
                                    onStopRecordVoice = {
                                        service.voiceManager.stopRecording()
                                    },
                                    onPlayVoice = { voiceBytes ->
                                        service.voiceManager.playVoice(voiceBytes)
                                    },
                                    onBackClick = { currentScreen = Screen.Chats }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
