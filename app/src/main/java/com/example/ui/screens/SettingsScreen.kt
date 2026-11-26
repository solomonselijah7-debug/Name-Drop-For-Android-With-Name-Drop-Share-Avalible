package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.YearlyUpdateSchedule
import com.example.data.DebugLogger
import com.example.model.CountryProfile
import com.example.model.GlowPreviewMode
import com.example.model.GlowThemeStyle
import com.example.model.PermissionReadinessState
import com.example.ui.components.IosActionRow
import com.example.ui.components.IosGroupedSection
import com.example.ui.components.IosHairlineDivider
import com.example.ui.theme.IosIndigo
import com.example.ui.theme.IosOrange
import com.example.ui.theme.LocalIosColors
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    readiness: PermissionReadinessState,
    rssiThreshold: Int,
    country: CountryProfile,
    glowPreviewMode: GlowPreviewMode,
    activeGlowTheme: GlowThemeStyle,
    contentPadding: PaddingValues,
    onRssiThresholdChange: (Int) -> Unit,
    onGlowPreviewModeChange: (GlowPreviewMode) -> Unit,
    onOpenPermissionsGate: () -> Unit,
    onOpenCountrySetup: () -> Unit
) {
    val ios = LocalIosColors.current
    val context = LocalContext.current
    val logs by DebugLogger.logs.collectAsState()
    var copiedNotice by remember { mutableStateOf(false) }
    val localDateKey = remember(country.zoneId) {
        YearlyUpdateSchedule.getLocalDateKey(country.zoneId)
    }

    val activeGlowColor = when (activeGlowTheme) {
        GlowThemeStyle.RED_EDGE -> ios.red
        GlowThemeStyle.GREEN_GLOW -> ios.green
        GlowThemeStyle.PURPLE_SURROUNDED -> Color(0xFFAF52DE)
        GlowThemeStyle.NONE -> ios.blue
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ios.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Settings",
                style = MaterialTheme.typography.displayLarge,
                color = ios.labelPrimary
            )

            IosGroupedSection(
                header = "Permissions & Background Mode",
                footer = if (readiness.canDrawOverlays) {
                    "Display Over Other Apps is enabled — Name Drop launches automatically on top when another phone touches yours, even when closed."
                } else {
                    "Background BLE foreground service is active. Grant Display Over Other Apps to launch the full-screen animation automatically when the app is closed."
                }
            ) {
                IosActionRow(
                    title = "Bluetooth, Location & Background",
                    subtitle = if (readiness.canProceedToContactSetup) {
                        "Required active • Background ${readiness.backgroundReadyCount}/4"
                    } else {
                        "Required permissions incomplete"
                    },
                    icon = Icons.Rounded.Security,
                    badgeColor = if (readiness.canProceedToContactSetup) ios.green else ios.red,
                    trailingText = "Manage",
                    trailingStatusColor = ios.blue,
                    testTag = "settings_permissions_row",
                    onClick = onOpenPermissionsGate
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "Display Over Other Apps",
                    subtitle = if (readiness.canDrawOverlays) {
                        "Background Pop-Up Trigger Active"
                    } else {
                        "Enable to pop up when app is closed"
                    },
                    icon = Icons.Rounded.Layers,
                    badgeColor = IosIndigo,
                    trailingText = if (readiness.canDrawOverlays) "Active" else "Setup",
                    trailingStatusColor = if (readiness.canDrawOverlays) ios.green else ios.orange,
                    onClick = onOpenPermissionsGate
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "Country & Phone Prefix",
                    subtitle = "${country.countryName} (${country.dialPrefix}) • ${country.zoneId}",
                    icon = Icons.Rounded.Public,
                    badgeColor = ios.blue,
                    trailingText = if (country.isVerified) "Verified" else "Verify",
                    trailingStatusColor = if (country.isVerified) ios.green else ios.orange,
                    testTag = "settings_country_row",
                    onClick = onOpenCountrySetup
                )
            }

            IosGroupedSection(
                header = "Local Date Glow Schedule & Preview",
                footer = "Offline device date schedule: Nov 25 = Red Glow • Nov 26 = Green Glow • Nov 27 = Purple Surrounded Glow."
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = activeGlowColor
                            )
                            Column {
                                Text(
                                    text = "Preview / Test Glow by Date",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ios.labelPrimary
                                )
                                Text(
                                    text = "Device Date: $localDateKey",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ios.labelSecondary
                                )
                            }
                        }
                        Text(
                            text = activeGlowTheme.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = activeGlowColor
                        )
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlowPreviewMode.entries.forEach { mode ->
                            FilterChip(
                                selected = glowPreviewMode == mode,
                                onClick = { onGlowPreviewModeChange(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("glow_preview_chip_${mode.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            IosGroupedSection(
                header = "BLE Proximity Sensitivity",
                footer = "Recommended: -38 to -48 dBm for touching phones. Slide left (-60 dBm) for thick phone cases."
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Trigger RSSI Threshold",
                            style = MaterialTheme.typography.titleMedium,
                            color = ios.labelPrimary
                        )
                        Text(
                            text = "$rssiThreshold dBm",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ios.blue
                        )
                    }
                    Slider(
                        value = rssiThreshold.toFloat(),
                        onValueChange = { onRssiThresholdChange(it.roundToInt()) },
                        valueRange = -70f..-30f,
                        colors = SliderDefaults.colors(
                            thumbColor = ios.blue,
                            activeTrackColor = ios.blue
                        ),
                        modifier = Modifier.testTag("settings_rssi_slider")
                    )
                }
            }

            IosGroupedSection(header = "Developer → Debug Log") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BugReport,
                                contentDescription = null,
                                tint = IosOrange
                            )
                            Text(
                                text = "Recent Logs (${logs.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = ios.labelPrimary
                            )
                        }
                        TextButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText("Name Drop Debug Log", DebugLogger.exportAsText())
                                )
                                copiedNotice = true
                            },
                            modifier = Modifier.testTag("copy_debug_log_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                tint = ios.blue
                            )
                            Text(
                                text = if (copiedNotice) " Copied!" else " Copy Log",
                                color = ios.blue
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ios.background,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val recent = logs.takeLast(14).reversed()
                            if (recent.isEmpty()) {
                                Text(
                                    text = "No BLE or permission events recorded yet.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ios.labelSecondary
                                )
                            } else {
                                recent.forEach { entry ->
                                    Text(
                                        text = "[${entry.timestamp}] ${entry.tag}: ${entry.message}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (entry.level == "ERROR") ios.red else ios.labelSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(104.dp))
        }
    }
}
