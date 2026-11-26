package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.GlowPreviewMode
import com.example.model.GlowThemeStyle
import com.example.ui.components.IosActionRow
import com.example.ui.components.IosGroupedSection
import com.example.ui.components.IosHairlineDivider
import com.example.ui.theme.IosIndigo
import com.example.ui.theme.IosOrange
import com.example.ui.theme.LocalIosColors

@Composable
fun TestScreen(
    contentPadding: PaddingValues,
    isTestOnlyMode: Boolean = false,
    glowPreviewMode: GlowPreviewMode = GlowPreviewMode.AUTO_LOCAL_DATE,
    activeGlowTheme: GlowThemeStyle = GlowThemeStyle.NONE,
    onSwitchToBluetoothSetup: () -> Unit = {},
    onCustomizeCardInTestMode: () -> Unit = {},
    onGlowPreviewModeChange: (GlowPreviewMode) -> Unit = {},
    onTestPerimeterSweep: () -> Unit,
    onTestContactCardSync: () -> Unit,
    onTestMutualCardSwap: () -> Unit,
    onTestSinglePhotoShare: () -> Unit,
    onTestGroupShare: () -> Unit,
    onTestDropBadgeProgress: () -> Unit,
    onTestGlowTheme: (GlowThemeStyle) -> Unit
) {
    val ios = LocalIosColors.current

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
                text = if (isTestOnlyMode) "Test Mode" else "Test Lab",
                style = MaterialTheme.typography.displayLarge,
                color = ios.labelPrimary
            )

            Text(
                text = if (isTestOnlyMode) {
                    "Bluetooth-free Test Mode unlocked. Preview every animation, Full-Screen Contact Card, Swap, Drop Badge, and Glow theme right here."
                } else {
                    "Preview the 6-stage audio-synced camera bar sweep, full-screen NameDrop card, mutual swap, Drop ring progress, and scheduled Glow themes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ios.labelSecondary
            )

            AnimatedVisibility(visible = isTestOnlyMode) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = ios.orange.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Test-Only Mode Active (No Bluetooth Required)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ios.labelPrimary
                        )
                        Text(
                            text = "Live Bluetooth tabs are locked because this device skipped Bluetooth setup. Want to use real Bluetooth permissions instead?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ios.labelSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onSwitchToBluetoothSetup,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = ios.blue),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("switch_to_bluetooth_setup_button")
                            ) {
                                Text("Enable Bluetooth", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = onCustomizeCardInTestMode,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ios.card,
                                    contentColor = ios.labelPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("test_mode_edit_profile_button")
                            ) {
                                Text("Edit Profile", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            IosGroupedSection(
                header = "Animation & Audio Sync",
                footer = "Plays the slow-motion ~3.15s sequence from the detected camera cutout synced to the startup chime."
            ) {
                IosActionRow(
                    title = "Test 6-Stage Camera Sweep + Sound",
                    subtitle = "Drop star → Sparkle/Explode → Reform → Waves → Edge Sweep",
                    icon = Icons.Rounded.PlayCircle,
                    badgeColor = ios.blue,
                    trailingText = "Play",
                    trailingStatusColor = ios.blue,
                    testTag = "test_perimeter_sweep_row",
                    onClick = onTestPerimeterSweep
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "Test Full-Screen NameDrop Card",
                    subtitle = "Sweeps from camera bar, then opens full-screen iOS card",
                    icon = Icons.Rounded.Contacts,
                    badgeColor = ios.green,
                    trailingText = "Run",
                    trailingStatusColor = ios.blue,
                    testTag = "test_contact_sync_row",
                    onClick = onTestContactCardSync
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "Test Both Share (Contact Card Swap)",
                    subtitle = "Simulates both phones tapping Share → Swap Arc Animation",
                    icon = Icons.Rounded.SwapHoriz,
                    badgeColor = Color(0xFFAF52DE),
                    trailingText = "Swap",
                    trailingStatusColor = ios.blue,
                    testTag = "test_mutual_swap_row",
                    onClick = onTestMutualCardSwap
                )
            }

            IosGroupedSection(header = "Media Sharing & Drop Badge Flows") {
                IosActionRow(
                    title = "Test NameDrop Share (Single Photo)",
                    subtitle = "Sweep → Accept / Decline sheet → Save to Gallery",
                    icon = Icons.Rounded.PhotoLibrary,
                    badgeColor = IosIndigo,
                    trailingText = "Run",
                    trailingStatusColor = ios.blue,
                    testTag = "test_single_photo_row",
                    onClick = onTestSinglePhotoShare
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "Test Step-by-Step Group Share",
                    subtitle = "Sweep → Nearby Group Prompt → Save to Gallery",
                    icon = Icons.Rounded.Groups,
                    badgeColor = IosOrange,
                    trailingText = "Run",
                    trailingStatusColor = ios.blue,
                    testTag = "test_group_share_row",
                    onClick = onTestGroupShare
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "Test Drop Pop-Down & Sharing Ring",
                    subtitle = "Animates \"Sharing\" progress ring → green \"Shared\" badge",
                    icon = Icons.Rounded.Downloading,
                    badgeColor = ios.green,
                    trailingText = "Test",
                    trailingStatusColor = ios.blue,
                    testTag = "test_drop_badge_row",
                    onClick = onTestDropBadgeProgress
                )
            }

            IosGroupedSection(
                header = "Scheduled Glow Theme Tests",
                footer = "Active theme: ${activeGlowTheme.label}. Tap any chip or row to preview."
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlowPreviewMode.entries.forEach { mode ->
                            FilterChip(
                                selected = glowPreviewMode == mode,
                                onClick = { onGlowPreviewModeChange(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    IosHairlineDivider()
                    IosActionRow(
                        title = "Preview Nov 25 • Red Glow",
                        subtitle = "Red perimeter edge sweep & full-screen Red Glow border",
                        icon = Icons.Rounded.AutoAwesome,
                        badgeColor = ios.red,
                        trailingText = "Preview",
                        trailingStatusColor = ios.red,
                        testTag = "test_glow_up_row",
                        onClick = { onTestGlowTheme(GlowThemeStyle.RED_EDGE) }
                    )
                    IosHairlineDivider()
                    IosActionRow(
                        title = "Preview Nov 26 • Green Glow",
                        subtitle = "Green perimeter edge sweep & full-screen Green Glow border",
                        icon = Icons.Rounded.AutoAwesome,
                        badgeColor = ios.green,
                        trailingText = "Preview",
                        trailingStatusColor = ios.green,
                        testTag = "test_glow_green_row",
                        onClick = { onTestGlowTheme(GlowThemeStyle.GREEN_GLOW) }
                    )
                    IosHairlineDivider()
                    IosActionRow(
                        title = "Preview Nov 27 • Purple Surrounded Glow",
                        subtitle = "Purple perimeter edge sweep & surrounded full-screen border",
                        icon = Icons.Rounded.AutoAwesome,
                        badgeColor = Color(0xFFAF52DE),
                        trailingText = "Preview",
                        trailingStatusColor = Color(0xFFAF52DE),
                        testTag = "test_glow_purple_row",
                        onClick = { onTestGlowTheme(GlowThemeStyle.PURPLE_SURROUNDED) }
                    )
                }
            }

            if (isTestOnlyMode) {
                IosGroupedSection(header = "Profile & Background Preview") {
                    IosActionRow(
                        title = "Customize Contact & Full-Screen Background",
                        subtitle = "Create your poster & full-bleed background for Test Mode previews",
                        icon = Icons.Rounded.Edit,
                        badgeColor = ios.blue,
                        trailingText = "Edit",
                        trailingStatusColor = ios.blue,
                        onClick = onCustomizeCardInTestMode
                    )
                    IosHairlineDivider()
                    IosActionRow(
                        title = "Switch to Full Bluetooth Permissions",
                        subtitle = "Exit Test-Only Mode and complete Bluetooth setup",
                        icon = Icons.Rounded.Bluetooth,
                        badgeColor = ios.green,
                        trailingText = "Switch",
                        trailingStatusColor = ios.blue,
                        onClick = onSwitchToBluetoothSetup
                    )
                }
            }

            Spacer(modifier = Modifier.height(104.dp))
        }
    }
}
