package com.connectmesh.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.identity.PeerIdentity
import com.connectmesh.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    userNickname: String,
    peers: List<PeerIdentity>,
    onPeerClick: (PeerIdentity) -> Unit
) {
    val directCount = peers.count { it.hopCount == 1 }
    val relayCount = peers.count { it.hopCount > 1 }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Hello, $userNickname 👋",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = AppTextPrimary
                        )
                        Text(
                            "Your mesh is ready",
                            fontSize = 12.sp,
                            color = AppTextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                    titleContentColor = AppTextPrimary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // MESH ACTIVE STATUS COMPACT CARD
            item {
                MeshCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(AppSuccessGreen, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "MESH NETWORK ACTIVE",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = AppSuccessGreen
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "● ${peers.size} nearby devices  ● $directCount direct  ● $relayCount mesh routes",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "CONVERSATIONS (${peers.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            if (peers.isEmpty()) {
                item {
                    MeshEmptyState(
                        icon = Icons.Default.Chat,
                        title = "No conversations yet",
                        description = "Bring another device running Connect-Mesh nearby over BLE to start chatting."
                    )
                }
            } else {
                items(peers) { peer ->
                    val displayName = formatPeerDisplayName(peer.nickname, peer.peerId)
                    val shortId = formatPeerShortId(peer.peerId)

                    val nextHopName = if (peer.hopCount > 1) {
                        peers.find { it.peerId == peer.nextHopPeerId }?.let { formatPeerDisplayName(it.nickname, it.peerId) }
                            ?: "...${peer.nextHopPeerId.toString(16).takeLast(4).uppercase()}"
                    } else null

                    MeshCard(
                        onClick = { onPeerClick(peer) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(AppSecondaryBackground, CircleShape)
                                    .border(1.dp, AppBorder, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = displayName,
                                    tint = AppPrimaryAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        displayName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = AppTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        shortId,
                                        fontSize = 11.sp,
                                        color = AppTextMuted
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                ConnectionBadge(
                                    hopCount = peer.hopCount,
                                    nextHopNickname = nextHopName
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
