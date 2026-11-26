package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.NavTab
import com.example.ui.theme.LocalIosColors

@Composable
fun FloatingPillNavBar(
    selectedTab: NavTab,
    isTestOnlyMode: Boolean = false,
    onSelectTab: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val ios = LocalIosColors.current
    val haptics = LocalHapticFeedback.current
    val glassBg = if (ios.isDark) {
        Color(0xEB1C1C1E)
    } else {
        Color(0xEBFFFFFF)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .shadow(14.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.14f))
                .border(0.6.dp, ios.separator.copy(alpha = 0.5f), CircleShape),
            shape = CircleShape,
            color = glassBg
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavTab.entries.forEach { tab ->
                    val isLocked = isTestOnlyMode && tab != NavTab.TEST
                    val selected = tab == selectedTab
                    val activePill by animateColorAsState(
                        targetValue = if (selected) ios.blue.copy(alpha = 0.14f) else Color.Transparent,
                        label = "tabPill"
                    )
                    val tint by animateColorAsState(
                        targetValue = when {
                            isLocked -> ios.labelSecondary.copy(alpha = 0.45f)
                            selected -> ios.blue
                            else -> ios.labelSecondary
                        },
                        label = "tabTint"
                    )
                    val icon = when {
                        isLocked -> Icons.Outlined.Lock
                        tab == NavTab.NAME_DROP -> if (selected) Icons.Rounded.Sensors else Icons.Outlined.Sensors
                        tab == NavTab.SHARE -> if (selected) Icons.Rounded.PhotoLibrary else Icons.Outlined.PhotoLibrary
                        tab == NavTab.TEST -> if (selected) Icons.Rounded.Science else Icons.Outlined.Science
                        tab == NavTab.UPDATES -> if (selected) Icons.Rounded.AutoAwesome else Icons.Outlined.AutoAwesome
                        tab == NavTab.SETTINGS -> if (selected) Icons.Rounded.Settings else Icons.Outlined.Settings
                        else -> if (selected) Icons.Rounded.Help else Icons.Outlined.HelpOutline
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("nav_${tab.name.lowercase()}")
                            .clip(CircleShape)
                            .background(activePill)
                            .alpha(if (isLocked) 0.55f else 1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectTab(tab)
                            }
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = if (isLocked) "${tab.label} (Locked in Test Mode)" else tab.label,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
