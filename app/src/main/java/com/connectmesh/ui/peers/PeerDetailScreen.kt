package com.connectmesh.ui.peers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.identity.PeerIdentity
import com.connectmesh.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetailScreen(
    peer: PeerIdentity,
    allPeers: List<PeerIdentity>,
    onStartChat: () -> Unit,
    onBackClick: () -> Unit
) {
    val displayName = formatPeerDisplayName(peer.nickname, peer.peerId)
    val shortId = formatPeerShortId(peer.peerId)
    var isDetailsExpanded by remember { mutableStateOf(false) }

    val nextHopName = if (peer.hopCount > 1) {
        allPeers.find { it.peerId == peer.nextHopPeerId }?.let { formatPeerDisplayName(it.nickname, it.peerId) }
            ?: "...${peer.nextHopPeerId.toString(16).takeLast(4).uppercase()}"
    } else null

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Peer Identity", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
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
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // AVATAR & HEADER
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(AppSecondaryBackground, CircleShape)
                    .border(2.dp, AppPrimaryAccent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = displayName,
                    tint = AppPrimaryAccent,
                    modifier = Modifier.size(44.dp)
                )
            }

            Text(displayName, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppTextPrimary)
            Text(shortId, fontSize = 14.sp, color = AppTextSecondary)

            ConnectionBadge(hopCount = peer.hopCount, nextHopNickname = nextHopName)

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onStartChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, tint = AppBackground)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Direct Chat", color = AppBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            // COLLAPSIBLE TECHNICAL DETAILS CARD
            MeshCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isDetailsExpanded = !isDetailsExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "CONNECTION DETAILS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppTextSecondary
                        )
                        Icon(
                            if (isDetailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle Details",
                            tint = AppTextSecondary
                        )
                    }

                    if (isDetailsExpanded) {
                        HorizontalDivider(color = AppBorder)

                        DetailRow(label = "Permanent Device ID", value = "0x${peer.peerId.toString(16).uppercase()}")
                        DetailRow(label = "Connection Type", value = if (peer.hopCount == 1) "Direct BLE Link" else "Multi-Hop Mesh Route")
                        DetailRow(label = "Network Depth", value = "${peer.hopCount} ${if (peer.hopCount == 1) "hop" else "hops"}")
                        if (peer.hopCount > 1) {
                            DetailRow(label = "Next Relay Hop", value = nextHopName ?: "Unknown Relay")
                        }
                        if (peer.bleAddress.isNotBlank()) {
                            DetailRow(label = "BLE MAC Address", value = peer.bleAddress)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AppTextPrimary)
    }
}
