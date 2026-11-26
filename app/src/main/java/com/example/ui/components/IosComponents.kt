package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalIosColors

@Composable
fun IosGroupedSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val ios = LocalIosColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (!header.isNullOrBlank()) {
            Text(
                text = header.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ios.labelSecondary,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, end = 16.dp)
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = ios.card,
            tonalElevation = 0.dp,
            shadowElevation = if (ios.isDark) 0.dp else 1.dp
        ) {
            Column(content = content)
        }
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelMedium,
                color = ios.labelSecondary,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp)
            )
        }
    }
}

@Composable
fun IosHairlineDivider() {
    val ios = LocalIosColors.current
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        thickness = 0.5.dp,
        color = ios.separator.copy(alpha = 0.7f)
    )
}

@Composable
fun IosIconBadge(
    icon: ImageVector,
    badgeColor: Color,
    contentDescription: String? = null
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(badgeColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun IosActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    badgeColor: Color,
    trailingText: String? = null,
    trailingStatusColor: Color? = null,
    showChevron: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit
) {
    val ios = LocalIosColors.current
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IosIconBadge(icon = icon, badgeColor = badgeColor)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = ios.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ios.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!trailingText.isNullOrBlank()) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelLarge,
                color = trailingStatusColor ?: ios.labelSecondary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = ios.labelSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun IosCapsuleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String = "ios_switch"
) {
    val ios = LocalIosColors.current
    val haptics = LocalHapticFeedback.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) ios.green else ios.secondaryFill,
        label = "switchTrack"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "switchThumb"
    )

    Box(
        modifier = Modifier
            .testTag(testTag)
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(28.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun DynamicIslandStatusPill(
    isBluetoothEnabled: Boolean,
    isActive: Boolean,
    nearestRssi: Int?,
    threshold: Int,
    onTapFix: () -> Unit
) {
    val ios = LocalIosColors.current
    val bgColor = when {
        !isBluetoothEnabled -> ios.red
        isActive && nearestRssi != null && nearestRssi >= threshold -> ios.green
        isActive -> Color(0xFF1C1C1E)
        else -> ios.secondaryFill
    }
    val textColor = when {
        !isBluetoothEnabled || isActive -> Color.White
        else -> ios.labelPrimary
    }
    val label = when {
        !isBluetoothEnabled -> "Bluetooth Off — Tap to Enable"
        isActive && nearestRssi != null -> "Nearby ${nearestRssi} dBm (Target $threshold)"
        isActive -> "Name Drop Listening ($threshold dBm)"
        else -> "Name Drop is Off"
    }

    Surface(
        shape = CircleShape,
        color = bgColor,
        modifier = Modifier
            .testTag("dynamic_island_pill")
            .heightIn(min = 38.dp)
            .clickable(enabled = !isBluetoothEnabled) { onTapFix() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (!isBluetoothEnabled) {
                    Icons.Rounded.BluetoothDisabled
                } else {
                    Icons.Rounded.Bluetooth
                },
                contentDescription = null,
                tint = if (isActive && isBluetoothEnabled) ios.blue else textColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LiveRssiSignalMeter(
    nearestDeviceName: String?,
    nearestRssi: Int?,
    smoothedRssi: Int?,
    threshold: Int,
    isBluetoothEnabled: Boolean,
    isNameDropEnabled: Boolean = true,
    isScanning: Boolean = false,
    isAdvertising: Boolean = false,
    bondedDeviceCount: Int = 0,
    nearbySignalCount: Int = 0,
    lastScanError: String? = null
) {
    val ios = LocalIosColors.current
    val effectiveRssi = smoothedRssi ?: nearestRssi
    val normalized = if (effectiveRssi != null && isBluetoothEnabled) {
        ((effectiveRssi + 90).coerceIn(0, 65)) / 65f
    } else {
        0f
    }
    val animatedFill by animateFloatAsState(
        targetValue = normalized,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "rssiBar"
    )
    val isInRange = effectiveRssi != null && effectiveRssi >= threshold

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Proximity Signal",
                style = MaterialTheme.typography.titleMedium,
                color = ios.labelPrimary
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isInRange) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = when {
                        !isBluetoothEnabled -> ios.red
                        !isNameDropEnabled -> ios.labelSecondary
                        isInRange -> ios.green
                        else -> ios.labelSecondary
                    },
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when {
                        !isBluetoothEnabled -> "BT Off"
                        !isNameDropEnabled -> "Off"
                        effectiveRssi != null -> "$effectiveRssi dBm"
                        isScanning -> "Scanning..."
                        else -> "Waiting..."
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        !isBluetoothEnabled -> ios.red
                        !isNameDropEnabled -> ios.labelSecondary
                        isInRange -> ios.green
                        else -> ios.blue
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(ios.secondaryFill)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFill)
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(if (isInRange) ios.green else ios.blue)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when {
                !isBluetoothEnabled -> "Turn on Bluetooth to detect nearby phones."
                !isNameDropEnabled -> "Turn on Start Name Drop above to scan & broadcast."
                lastScanError != null -> "Scan issue: $lastScanError"
                nearestDeviceName != null && effectiveRssi != null ->
                    "$nearestDeviceName at $effectiveRssi dBm • Triggers at $threshold dBm ($nearbySignalCount nearby)"
                else -> {
                    val advLabel = if (isAdvertising) "Broadcasting ON" else "Scan-only mode"
                    val pairedLabel = if (bondedDeviceCount > 0) "$bondedDeviceCount paired" else "0 paired"
                    "$advLabel • $pairedLabel • Hold phone tops together ($threshold dBm)"
                }
            },
            style = MaterialTheme.typography.labelMedium,
            fontSize = 12.sp,
            color = if (lastScanError != null) ios.red else ios.labelSecondary
        )
    }
}

@Composable
fun IosStatusPillBadge(
    isOk: Boolean,
    okText: String = "Enabled",
    missingText: String = "Required"
) {
    val ios = LocalIosColors.current
    val tint = if (isOk) ios.green else ios.red
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .border(0.5.dp, tint.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (isOk) okText else missingText,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
