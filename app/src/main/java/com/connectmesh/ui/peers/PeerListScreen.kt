package com.connectmesh.ui.peers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.identity.PeerIdentity
import com.connectmesh.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(
    peers: List<PeerIdentity>,
    onPeerClick: (PeerIdentity) -> Unit
) {
    val directPeers = peers.filter { it.hopCount == 1 }
    val meshPeers = peers.filter { it.hopCount > 1 }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("People Nearby", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
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
            if (peers.isEmpty()) {
                item {
                    MeshCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = AppPrimaryAccent.copy(alpha = 0.12f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.CellTower,
                                        contentDescription = null,
                                        tint = AppPrimaryAccent,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                "Nobody nearby yet",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = AppTextPrimary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                "People running Connect-Mesh nearby will appear here automatically.",
                                fontSize = 13.sp,
                                color = AppTextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0F172A)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Bluetooth,
                                        contentDescription = null,
                                        tint = AppSuccessGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Ensure Bluetooth is turned ON on nearby phones.",
                                        fontSize = 11.sp,
                                        color = AppTextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // SECTION 1: NEARBY DIRECT PEERS
                if (directPeers.isNotEmpty()) {
                    item {
                        Text(
                            "DIRECTLY CONNECTED (${directPeers.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppSuccessGreen,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    items(directPeers) { peer ->
                        PeerListItemCard(peer = peer, allPeers = peers, onClick = { onPeerClick(peer) })
                    }
                }

                // SECTION 2: THROUGH MESH PEERS
                if (meshPeers.isNotEmpty()) {
                    item {
                        Text(
                            "REACHABLE VIA MESH RELAY (${meshPeers.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppWarningAmber,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }

                    items(meshPeers) { peer ->
                        PeerListItemCard(peer = peer, allPeers = peers, onClick = { onPeerClick(peer) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerListItemCard(
    peer: PeerIdentity,
    allPeers: List<PeerIdentity>,
    onClick: () -> Unit
) {
    val displayName = peer.nickname.ifBlank { "Connect-Mesh Peer" }

    val nextHopName = if (peer.hopCount > 1) {
        allPeers.find { it.peerId == peer.nextHopPeerId }?.nickname?.ifBlank { null }
            ?: "Mesh Node"
    } else null

    MeshCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (peer.hopCount == 1) AppSuccessGreen.copy(alpha = 0.15f) else AppWarningAmber.copy(alpha = 0.15f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = displayName,
                        tint = if (peer.hopCount == 1) AppSuccessGreen else AppWarningAmber,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = AppTextPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (peer.hopCount == 1) AppSuccessGreen.copy(alpha = 0.2f) else AppWarningAmber.copy(alpha = 0.2f)
                    ) {
                        Text(
                            if (peer.hopCount == 1) "Direct Link" else "$nextHopName (${peer.hopCount} hops)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (peer.hopCount == 1) AppSuccessGreen else AppWarningAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = AppTextSecondary
            )
        }
    }
}
