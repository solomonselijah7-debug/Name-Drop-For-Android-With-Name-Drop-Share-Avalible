package com.example.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.YearlyUpdateSchedule
import com.example.model.BleProximityState
import com.example.model.CountryProfile
import com.example.model.DailyUpdateEntry
import com.example.model.GlowThemeStyle
import com.example.model.PermissionReadinessState
import com.example.model.SelfTestReport
import com.example.ui.components.IosActionRow
import com.example.ui.components.IosGroupedSection
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosStatusPillBadge
import com.example.ui.theme.LocalIosColors

@Composable
fun UpdatesScreen(
    todayUpdate: DailyUpdateEntry,
    activeGlowTheme: GlowThemeStyle,
    country: CountryProfile,
    selfTest: SelfTestReport,
    readiness: PermissionReadinessState,
    bleState: BleProximityState,
    isGeminiLoading: Boolean,
    geminiError: String?,
    versionLabel: String,
    contentPadding: PaddingValues,
    onRunGeminiUpdateAndSelfTest: () -> Unit,
    onPreviewActiveGlow: (GlowThemeStyle) -> Unit,
    onSelectCalendarDate: (Int, Int, Int) -> Unit,
    onOpenWhatsNew: () -> Unit,
    onOpenTestTab: () -> Unit,
    onOpenSettingsTab: () -> Unit,
    onOpenPermissionsGate: () -> Unit
) {
    val ios = LocalIosColors.current
    val worldClocks = remember(todayUpdate.dateKey) { YearlyUpdateSchedule.getWorldClockStatuses() }
    val yearEntries = remember { YearlyUpdateSchedule.getFullYearEntries(2026) }
    var selectedMonth by remember { mutableIntStateOf(11) }
    val monthEntries = remember(selectedMonth, yearEntries) {
        yearEntries.filter { it.monthDayKey.startsWith(String.format("%02d-", selectedMonth)) }
    }
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    val displayGlow = if (activeGlowTheme != GlowThemeStyle.NONE) activeGlowTheme else todayUpdate.glowTheme
    val glowAccentColor = when (displayGlow) {
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
                text = "Updates",
                style = MaterialTheme.typography.displayLarge,
                color = ios.labelPrimary
            )

            IosGroupedSection(header = "Installed App Release") {
                IosActionRow(
                    title = "What's New in This Update",
                    subtitle = "$versionLabel • View changelog anytime",
                    icon = Icons.Rounded.NewReleases,
                    badgeColor = ios.blue,
                    trailingText = "Open",
                    trailingStatusColor = ios.blue,
                    testTag = "updates_whats_new_row",
                    onClick = onOpenWhatsNew
                )
            }

            // Active Local-Date & Daily Edition Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = ios.card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
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
                                tint = glowAccentColor
                            )
                            Text(
                                text = "${todayUpdate.category} • ${todayUpdate.dateKey}",
                                style = MaterialTheme.typography.labelMedium,
                                color = glowAccentColor
                            )
                        }
                        IosStatusPillBadge(
                            isOk = todayUpdate.isFromGemini,
                            okText = "Gemini Live",
                            missingText = "Local Schedule"
                        )
                    }

                    Text(
                        text = todayUpdate.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = ios.labelPrimary
                    )
                    Text(
                        text = todayUpdate.featureHighlight,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ios.labelSecondary
                    )

                    if (!geminiError.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ios.red.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "Gemini Error: $geminiError",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ios.red,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onRunGeminiUpdateAndSelfTest,
                            enabled = !isGeminiLoading,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("run_gemini_update_button"),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = ios.blue)
                        ) {
                            if (isGeminiLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Run Gemini Update", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Button(
                            onClick = {
                                val targetGlow = if (displayGlow != GlowThemeStyle.NONE) {
                                    displayGlow
                                } else {
                                    GlowThemeStyle.GREEN_GLOW
                                }
                                onPreviewActiveGlow(targetGlow)
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("preview_glow_up_button"),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = glowAccentColor.copy(alpha = 0.14f),
                                contentColor = glowAccentColor
                            )
                        ) {
                            Text(
                                text = if (displayGlow != GlowThemeStyle.NONE) "Preview ${displayGlow.label}" else "Preview Glow",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Daily Self-Test Card
            IosGroupedSection(header = "Daily Gemini Self-Test (${selfTest.checkedAtText})") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (selfTest.requiresReopenWarning) ios.orange else ios.green
                        )
                        Text(
                            text = selfTest.headline,
                            style = MaterialTheme.typography.titleMedium,
                            color = ios.labelPrimary
                        )
                    }
                    Text(
                        text = selfTest.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ios.labelSecondary
                    )
                    if (selfTest.requiresReopenWarning) {
                        TextButton(
                            onClick = onOpenPermissionsGate,
                            modifier = Modifier.testTag("selftest_fix_permissions_button")
                        ) {
                            Text("Open Permissions & Background Setup", color = ios.blue)
                        }
                    }
                }
            }

            // Proximity & Signal Readiness Spotlight
            IosGroupedSection(header = "Proximity & Signal Readiness Spotlight") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sensors,
                            contentDescription = null,
                            tint = if (readiness.canProceedToContactSetup) ios.green else ios.red
                        )
                        Text(
                            text = if (readiness.canProceedToContactSetup) {
                                "BLE Signal Ready (${bleState.rssiThreshold} dBm)"
                            } else {
                                "Bluetooth / Location Setup Incomplete"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = ios.labelPrimary
                        )
                    }
                    Text(
                        text = "Your active proximity trigger is ${bleState.rssiThreshold} dBm. Use Test to verify sweep timing or Settings to preview Glow themes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ios.labelSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onOpenTestTab) { Text("Open Test", color = ios.blue) }
                        TextButton(onClick = onOpenSettingsTab) { Text("Open Settings", color = ios.blue) }
                    }
                }
            }

            // World Clocks
            IosGroupedSection(
                header = "Local-Time Schedule Clocks (${country.countryName})",
                footer = "Local YYYY-MM-DD Glow Schedule activates automatically at midnight: Nov 25 Red • Nov 26 Green • Nov 27 Purple Surrounded."
            ) {
                worldClocks.forEachIndexed { idx, clock ->
                    if (idx > 0) IosHairlineDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Public,
                            contentDescription = null,
                            tint = ios.blue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(clock.label, style = MaterialTheme.typography.bodyLarge, color = ios.labelPrimary)
                            Text(clock.nextNoonCountdown, style = MaterialTheme.typography.labelMedium, color = ios.labelSecondary)
                        }
                        Text(
                            text = clock.localTimeText,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (clock.isPastNoon) ios.green else ios.labelSecondary
                        )
                    }
                }
            }

            // 365-Day Schedule Browser (Jan - Dec)
            IosGroupedSection(header = "365-Day Schedule (Jan – Dec)") {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        monthNames.forEachIndexed { index, name ->
                            FilterChip(
                                selected = selectedMonth == index + 1,
                                onClick = { selectedMonth = index + 1 },
                                label = { Text(name) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    monthEntries.forEachIndexed { i, entry ->
                        val entryColor = when (entry.glowTheme) {
                            GlowThemeStyle.RED_EDGE -> ios.red
                            GlowThemeStyle.GREEN_GLOW -> ios.green
                            GlowThemeStyle.PURPLE_SURROUNDED -> Color(0xFFAF52DE)
                            GlowThemeStyle.NONE -> ios.labelPrimary
                        }
                        if (i > 0) IosHairlineDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${entry.dateKey} • ${entry.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = entryColor
                                )
                                Text(
                                    text = entry.subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ios.labelSecondary
                                )
                            }
                            TextButton(
                                onClick = {
                                    val day = entry.dateKey.takeLast(2).toIntOrNull() ?: 1
                                    onSelectCalendarDate(2026, selectedMonth, day)
                                }
                            ) {
                                Text("Inspect", color = ios.blue)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(104.dp))
        }
    }
}
