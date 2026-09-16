package com.connectmesh.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectmesh.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    userNickname: String,
    deviceId: Long,
    onNavigateToMountain: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("More Options", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // USER PROFILE CARD
            item {
                MeshCard(onClick = onNavigateToProfile) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = AppPrimaryAccent.copy(alpha = 0.15f),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = AppPrimaryAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                userNickname.ifBlank { "Connect-Mesh User" },
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = AppTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "ID: 0x${deviceId.toString(16).takeLast(8).uppercase()}",
                                fontSize = 12.sp,
                                color = AppTextSecondary
                            )
                        }

                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = AppTextSecondary
                        )
                    }
                }
            }

            // SECTION HEADER
            item {
                Text(
                    "SPECIAL MODES & SETTINGS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // MOUNTAIN / DISASTER MODE ITEM
            item {
                MoreMenuItem(
                    icon = Icons.Default.Terrain,
                    iconTint = AppWarningAmber,
                    title = "Mountain / Disaster Mode",
                    subtitle = "Offline communication for disaster & internet-denied terrain",
                    onClick = onNavigateToMountain
                )
            }

            // PROFILE & IDENTITY ITEM
            item {
                MoreMenuItem(
                    icon = Icons.Default.AccountCircle,
                    iconTint = AppPrimaryAccent,
                    title = "Profile & Identity",
                    subtitle = "Display name, Keystore identity, and security preferences",
                    onClick = onNavigateToProfile
                )
            }

            // ADVANCED & DIAGNOSTICS ITEM
            item {
                MoreMenuItem(
                    icon = Icons.Default.BugReport,
                    iconTint = Color(0xFF38BDF8),
                    title = "Advanced & Diagnostics",
                    subtitle = "Network event logs, ping tests, and route telemetry",
                    onClick = onNavigateToDiagnostics
                )
            }

            // ABOUT CONNECT-MESH CARD
            item {
                MeshCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = AppSuccessGreen
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "About Connect-Mesh",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = AppTextPrimary
                            )
                            Text(
                                "Version 2.0 • Offline Bluetooth Mesh Communication",
                                fontSize = 12.sp,
                                color = AppTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreMenuItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    MeshCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = iconTint.copy(alpha = 0.12f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AppTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = AppTextSecondary,
                    lineHeight = 16.sp
                )
            }

            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = AppTextSecondary
            )
        }
    }
}
