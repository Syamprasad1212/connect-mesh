package com.connectmesh.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// COLOR PALETTE SPECIFICATION
val AppBackground = Color(0xFF0B1220)
val AppSecondaryBackground = Color(0xFF111B2E)
val AppElevatedSurface = Color(0xFF18243A)
val AppBorder = Color(0xFF223452)
val AppPrimaryAccent = Color(0xFF22D3EE)
val AppSuccessGreen = Color(0xFF22C55E)
val AppWarningAmber = Color(0xFFF59E0B)
val AppEmergencyRed = Color(0xFFEF4444)

val AppTextPrimary = Color(0xFFF8FAFC)
val AppTextSecondary = Color(0xFF94A3B8)
val AppTextMuted = Color(0xFF64748B)

val ThemeDarkColorScheme = darkColorScheme(
    primary = AppPrimaryAccent,
    onPrimary = Color(0xFF042F2E),
    secondary = AppPrimaryAccent,
    onSecondary = Color.White,
    background = AppBackground,
    onBackground = AppTextPrimary,
    surface = AppElevatedSurface,
    onSurface = AppTextPrimary,
    surfaceVariant = AppSecondaryBackground,
    onSurfaceVariant = AppTextSecondary,
    outline = AppBorder
)

fun formatPeerDisplayName(nickname: String, peerId: Long): String {
    return if (nickname.isNotBlank() && !nickname.startsWith("Peer ...") && !nickname.startsWith("Peer 0x") && !nickname.startsWith("Nearby device")) {
        nickname
    } else {
        "Nearby device"
    }
}

fun formatPeerShortId(peerId: Long): String {
    return "ID: ...${peerId.toString(16).takeLast(4).uppercase()}"
}

@Composable
fun ConnectionBadge(
    hopCount: Int,
    nextHopNickname: String? = null,
    modifier: Modifier = Modifier
) {
    val (dotColor, text, textColor) = when {
        hopCount == 1 -> Triple(AppSuccessGreen, "Direct BLE", AppSuccessGreen)
        hopCount > 1 -> {
            val viaText = if (!nextHopNickname.isNullOrBlank()) " · via $nextHopNickname" else ""
            Triple(AppWarningAmber, "$hopCount hops$viaText", AppWarningAmber)
        }
        else -> Triple(AppTextMuted, "Offline", AppTextMuted)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
fun MeshCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .background(AppElevatedSurface, RoundedCornerShape(18.dp))
            .border(1.dp, AppBorder, RoundedCornerShape(18.dp))
            .clickable { onClick() }
    } else {
        modifier
            .fillMaxWidth()
            .background(AppElevatedSurface, RoundedCornerShape(18.dp))
            .border(1.dp, AppBorder, RoundedCornerShape(18.dp))
    }

    Column(
        modifier = cardModifier.padding(16.dp),
        content = content
    )
}

@Composable
fun MeshEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(AppSecondaryBackground, CircleShape)
                .border(1.dp, AppBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppPrimaryAccent,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
            textAlign = TextAlign.Center
        )

        if (actionText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onActionClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimaryAccent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text(actionText, color = AppBackground, fontWeight = FontWeight.Bold)
            }
        }
    }
}
