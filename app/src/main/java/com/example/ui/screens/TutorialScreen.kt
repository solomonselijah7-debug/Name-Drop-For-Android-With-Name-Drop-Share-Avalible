package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ContactPhone
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.components.IosActionRow
import com.example.ui.components.IosGroupedSection
import com.example.ui.components.IosHairlineDivider
import com.example.ui.theme.IosIndigo
import com.example.ui.theme.IosOrange
import com.example.ui.theme.LocalIosColors

@Composable
fun TutorialScreen(
    contentPadding: PaddingValues,
    onOpenPermissionsGate: () -> Unit
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
                text = "Tutorial",
                style = MaterialTheme.typography.displayLarge,
                color = ios.labelPrimary
            )

            Text(
                text = "Step-by-step guide to reliable Bluetooth Low Energy Name Drops and Huawei EMUI background setup.",
                style = MaterialTheme.typography.bodyMedium,
                color = ios.labelSecondary
            )

            IosGroupedSection(header = "Getting Started") {
                IosActionRow(
                    title = "1. Enable Bluetooth & Location First",
                    subtitle = "Both Bluetooth Power and Location must be ON so Android returns BLE proximity scans.",
                    icon = Icons.Rounded.Bluetooth,
                    badgeColor = ios.blue,
                    showChevron = true,
                    onClick = onOpenPermissionsGate
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "2. Set Up Your Contact Poster",
                    subtitle = "Verify your country prefix, enter your name & number, and pick your exact portrait photo.",
                    icon = Icons.Rounded.ContactPhone,
                    badgeColor = ios.green,
                    showChevron = false,
                    onClick = {}
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "3. Hold Tops of Phones Together",
                    subtitle = "Watch the live RSSI meter cross -48 dBm. The 3-2-1 sweep & chime plays, then pops up the card.",
                    icon = Icons.Rounded.PhoneAndroid,
                    badgeColor = IosIndigo,
                    showChevron = false,
                    onClick = {}
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "4. Share Photos & Group Drops",
                    subtitle = "Use the Share tab to send single or group photos directly to the receiver's Gallery.",
                    icon = Icons.Rounded.PhotoLibrary,
                    badgeColor = ios.blue,
                    showChevron = false,
                    onClick = {}
                )
            }

            IosGroupedSection(
                header = "Huawei Mate 20 Lite & EMUI Guide",
                footer = "If your phone blocks background popups or Display Over Other Apps is off, keep Name Drop open on screen during drops."
            ) {
                IosActionRow(
                    title = "App Launch → Manage Manually",
                    subtitle = "Settings → Apps → App launch → Name Drop → Toggle OFF Automatic, enable Auto-launch, Secondary launch & Run in background.",
                    icon = Icons.Rounded.BatteryChargingFull,
                    badgeColor = IosOrange,
                    onClick = onOpenPermissionsGate
                )
                IosHairlineDivider()
                IosActionRow(
                    title = "Close & Reopen After Setup",
                    subtitle = "After granting Background Location ('Allow all the time') and Display Over Other Apps, swipe Name Drop closed and reopen it once.",
                    icon = Icons.Rounded.PhoneAndroid,
                    badgeColor = ios.red,
                    onClick = onOpenPermissionsGate
                )
            }

            Spacer(modifier = Modifier.height(104.dp))
        }
    }
}
