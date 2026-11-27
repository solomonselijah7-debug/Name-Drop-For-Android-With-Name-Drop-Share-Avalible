package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.BleProximityState
import com.example.model.ContactCard
import com.example.model.GlowThemeStyle
import com.example.model.PermissionReadinessState
import com.example.model.SavedContactEntry
import com.example.ui.components.ContactPosterCard
import com.example.ui.components.DynamicIslandStatusPill
import com.example.ui.components.IosActionRow
import com.example.ui.components.IosCapsuleSwitch
import com.example.ui.components.IosGroupedSection
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosIconBadge
import com.example.ui.components.LiveRssiSignalMeter
import com.example.ui.components.PhoneTapIllustration
import com.example.ui.theme.LocalIosColors
import java.io.File

@Composable
fun NameDropHomeScreen(
    readiness: PermissionReadinessState,
    bleState: BleProximityState,
    myCard: ContactCard,
    savedContacts: List<SavedContactEntry>,
    activeGlowTheme: GlowThemeStyle,
    contentPadding: PaddingValues,
    onToggleNameDrop: (Boolean) -> Unit,
    onOpenPermissionsGate: () -> Unit,
    onRestartBluetoothScan: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onCustomizeCard: () -> Unit,
    onQuickTestSweep: () -> Unit,
    onSyncSavedContact: (String) -> Unit,
    onRequestContactsPermission: () -> Unit
) {
    val ios = LocalIosColors.current
    val context = LocalContext.current

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

            // Dynamic Island status pill
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                DynamicIslandStatusPill(
                    isBluetoothEnabled = readiness.isBluetoothEnabled,
                    isActive = bleState.isNameDropEnabled,
                    nearestRssi = bleState.smoothedRssi ?: bleState.nearestRssi,
                    threshold = bleState.rssiThreshold,
                    onTapFix = onOpenPermissionsGate
                )
            }

            Text(
                text = "Name Drop",
                style = MaterialTheme.typography.displayLarge,
                color = ios.labelPrimary
            )

            // Live Bluetooth / Readiness Alert Banner if Bluetooth or Location turned off later
            AnimatedVisibility(visible = !readiness.canProceedToContactSetup) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = ios.red.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BluetoothDisabled,
                            contentDescription = null,
                            tint = ios.red,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (!readiness.isBluetoothEnabled) {
                                    "Bluetooth is Turned Off"
                                } else {
                                    "Bluetooth or Location Needed"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = ios.labelPrimary
                            )
                            Text(
                                text = "Name Drop cannot detect nearby phones until Bluetooth and Location are active.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ios.labelSecondary
                            )
                        }
                        Button(
                            onClick = onOpenPermissionsGate,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = ios.red),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("home_fix_permissions_button")
                        ) {
                            Text("Enable", color = Color.White)
                        }
                    }
                }
            }

            // Master Start / Stop Card + Live RSSI Signal Meter
            IosGroupedSection(header = "Bluetooth Proximity") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IosIconBadge(
                        icon = Icons.Rounded.Sensors,
                        badgeColor = if (bleState.isNameDropEnabled) ios.green else ios.blue
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (bleState.isNameDropEnabled) "Name Drop is On" else "Start Name Drop",
                            style = MaterialTheme.typography.titleMedium,
                            color = ios.labelPrimary
                        )
                        Text(
                            text = when {
                                bleState.isNameDropEnabled && readiness.canDrawOverlays ->
                                    "Background overlay active • Triggers even when closed"
                                bleState.isNameDropEnabled ->
                                    "Background service active • Grant Overlay for auto pop-up"
                                else ->
                                    "Turn on to swap Contact Posters via BLE"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = ios.labelSecondary
                        )
                    }
                    IosCapsuleSwitch(
                        checked = bleState.isNameDropEnabled,
                        onCheckedChange = onToggleNameDrop,
                        testTag = "namedrop_master_switch"
                    )
                }
                IosHairlineDivider()
                LiveRssiSignalMeter(
                    nearestDeviceName = bleState.nearestDeviceName,
                    nearestRssi = bleState.nearestRssi,
                    smoothedRssi = bleState.smoothedRssi,
                    threshold = bleState.rssiThreshold,
                    isBluetoothEnabled = readiness.isBluetoothEnabled,
                    isNameDropEnabled = bleState.isNameDropEnabled,
                    isScanning = bleState.isScanning,
                    isAdvertising = bleState.isAdvertising,
                    bondedDeviceCount = bleState.bondedDeviceCount,
                    nearbySignalCount = bleState.nearbySignalCount,
                    lastScanError = bleState.lastScanError
                )
                IosHairlineDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onRestartBluetoothScan,
                        modifier = Modifier.testTag("home_refresh_ble_scan_button")
                    ) {
                        Text("Refresh Scan", color = ios.blue)
                    }
                    TextButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier.testTag("home_bluetooth_settings_button")
                    ) {
                        Text(
                            text = if (bleState.bondedDeviceCount > 0) {
                                "Bluetooth (${bleState.bondedDeviceCount} Paired)"
                            } else {
                                "Bluetooth Settings"
                            },
                            color = ios.blue
                        )
                    }
                }
            }

            // Hold tops of phones together guide card
            IosGroupedSection(header = "How to Drop") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PhoneTapIllustration()
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "1. Hold tops of phones together",
                            style = MaterialTheme.typography.titleMedium,
                            color = ios.labelPrimary
                        )
                        Text(
                            text = "2. Wait for the star, waves & sweep + chime, then tap Share or Receive Only.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ios.labelSecondary
                        )
                        TextButton(
                            onClick = onQuickTestSweep,
                            modifier = Modifier.testTag("home_preview_sweep_button")
                        ) {
                            Text("Preview Sweep Animation", color = ios.blue)
                        }
                    }
                }
            }

            // My Contact Poster Card
            IosGroupedSection(header = "Your Contact Poster & Profile") {
                IosActionRow(
                    title = "Customize Profile & Background",
                    subtitle = "${myCard.displayName.ifBlank { "Set your name" }} • ${myCard.phoneNumber.ifBlank { "Add number" }}",
                    icon = Icons.Rounded.Edit,
                    badgeColor = ios.blue,
                    trailingText = "Edit",
                    trailingStatusColor = ios.blue,
                    testTag = "customize_contact_row",
                    onClick = onCustomizeCard
                )
            }

            ContactPosterCard(
                contact = myCard,
                headerLabel = "My Contact Poster",
                glowTheme = activeGlowTheme,
                onPortraitClick = onCustomizeCard
            )

            IosGroupedSection(
                header = "Saved Contacts (${savedContacts.size})",
                footer = if (readiness.hasContactsPermissions) {
                    "Received and swapped cards auto-save to your phone's Contacts and here with full phone numbers."
                } else {
                    "Received and swapped cards auto-save here locally. Grant Contacts permission to sync numbers to your phone's Contacts book."
                }
            ) {
                if (!readiness.hasContactsPermissions) {
                    IosActionRow(
                        title = "Auto-Save to Phone Contacts",
                        subtitle = "Allow Contacts permission so received numbers go straight into your dialer",
                        icon = Icons.Rounded.Contacts,
                        badgeColor = ios.green,
                        trailingText = "Enable",
                        trailingStatusColor = ios.blue,
                        testTag = "home_enable_contacts_permission_row",
                        onClick = onRequestContactsPermission
                    )
                    if (savedContacts.isNotEmpty()) {
                        IosHairlineDivider()
                    }
                }

                if (savedContacts.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Contacts,
                            contentDescription = null,
                            tint = ios.labelSecondary
                        )
                        Text(
                            text = "No received contacts saved yet. Hold phones together to receive or swap a Contact Card.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ios.labelSecondary
                        )
                    }
                } else {
                    savedContacts.take(12).forEachIndexed { idx, entry ->
                        if (idx > 0) IosHairlineDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(entry.card.backgroundColorHex)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!entry.card.photoPath.isNullOrBlank()) {
                                    AsyncImage(
                                        model = File(entry.card.photoPath),
                                        contentDescription = entry.card.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.card.displayName.ifBlank { "Shared Contact" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ios.labelPrimary
                                )
                                Text(
                                    text = buildString {
                                        append(entry.card.phoneNumber.ifBlank { "No phone number" })
                                        append(" • ")
                                        append(entry.sourceLabel)
                                        append(if (entry.savedToSystemContacts) " • Phone Book" else " • Local")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entry.savedToSystemContacts) ios.green else ios.labelSecondary
                                )
                            }

                            if (!entry.savedToSystemContacts && readiness.hasContactsPermissions) {
                                IconButton(
                                    onClick = { onSyncSavedContact(entry.id) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Sync,
                                        contentDescription = "Sync to Phone Contacts",
                                        tint = ios.blue
                                    )
                                }
                            }

                            if (entry.card.phoneNumber.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        val dialIntent = Intent(
                                            Intent.ACTION_DIAL,
                                            Uri.parse("tel:${Uri.encode(entry.card.phoneNumber)}")
                                        )
                                        runCatching { context.startActivity(dialIntent) }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("saved_contact_call_${idx}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Call,
                                        contentDescription = "Call ${entry.card.displayName}",
                                        tint = ios.green
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
