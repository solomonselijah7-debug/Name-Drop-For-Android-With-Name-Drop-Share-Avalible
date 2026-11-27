package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.ble.BlePermissions
import com.example.data.DebugLogger
import com.example.model.PermissionReadinessState
import com.example.ui.components.IosActionRow
import com.example.ui.components.IosGroupedSection
import com.example.ui.components.IosHairlineDivider
import com.example.ui.theme.IosIndigo
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosPurple
import com.example.ui.theme.IosTeal
import com.example.ui.theme.LocalIosColors

/**
 * First-launch & readiness gate screen that checks and requests Bluetooth adapter state,
 * BLE runtime permissions, Foreground Location (for BLE scanning), Location toggle,
 * and guides Background Location, Battery Optimization, Notifications, and Display Over Other Apps
 * before allowing the user to proceed to Contact Card setup.
 */
@Composable
fun PermissionsSetupScreen(
    readiness: PermissionReadinessState,
    showBluetoothPrompt: Boolean,
    onBluetoothPromptYes: () -> Unit,
    onBluetoothPromptNo: () -> Unit,
    onShowBluetoothPromptAgain: () -> Unit,
    onRefreshReadiness: () -> Unit,
    onContinueToContactSetup: () -> Unit
) {
    val context = LocalContext.current
    val ios = LocalIosColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var bgLocationAttempts by remember { mutableIntStateOf(0) }

    // Automatically re-check all system toggles & settings permissions when returning to the app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshReadiness()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val btEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshReadiness()
    }

    val genericSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshReadiness()
    }

    val primaryPermsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        DebugLogger.log("Perms", "Primary permissions result: $results")
        onRefreshReadiness()
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        DebugLogger.log("Perms", "Background location prompt returned granted=$granted")
        onRefreshReadiness()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        DebugLogger.log("Perms", "Notification permission granted=$granted")
        onRefreshReadiness()
    }

    val contactsPermsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        DebugLogger.log("Perms", "Contacts auto-save permission result: $results")
        onRefreshReadiness()
    }

    fun requestBluetoothEnable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !readiness.hasBluetoothConnect) {
            primaryPermsLauncher.launch(BlePermissions.requiredPrimaryPermissions())
            return
        }
        runCatching {
            btEnableLauncher.launch(BlePermissions.createEnableBluetoothIntent())
        }.onFailure {
            runCatching {
                genericSettingsLauncher.launch(BlePermissions.createBluetoothSettingsIntent())
            }
        }
    }

    fun requestBluetoothPermissions() {
        val perms = BlePermissions.requiredBluetoothPermissions()
        if (perms.isNotEmpty()) {
            primaryPermsLauncher.launch(perms)
        } else {
            onRefreshReadiness()
        }
    }

    fun requestForegroundLocation() {
        primaryPermsLauncher.launch(BlePermissions.requiredLocationPermissions())
    }

    fun requestBackgroundLocation() {
        if (!readiness.hasForegroundLocation) {
            primaryPermsLauncher.launch(BlePermissions.requiredLocationPermissions())
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bgLocationAttempts++
            if (bgLocationAttempts <= 2) {
                runCatching {
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }.onFailure {
                    genericSettingsLauncher.launch(BlePermissions.createAppDetailsIntent(context))
                }
            } else {
                // On Android 11+, if the system ignores repeated prompts, open App Permissions directly
                genericSettingsLauncher.launch(BlePermissions.createAppDetailsIntent(context))
            }
        } else {
            onRefreshReadiness()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ios.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ios.blue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BluetoothSearching,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "STEP 1 OF 2 • DEVICE READINESS",
                                style = MaterialTheme.typography.labelSmall,
                                color = ios.blue
                            )
                            Text(
                                text = stringResource(R.string.permissions_gate_title),
                                style = MaterialTheme.typography.headlineLarge,
                                color = ios.labelPrimary
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.permissions_gate_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ios.labelSecondary
                    )
                }

                // One-tap Grant All Runtime Permissions helper
                if (!readiness.hasAllBluetoothPermissions || !readiness.hasForegroundLocation) {
                    Button(
                        onClick = {
                            primaryPermsLauncher.launch(BlePermissions.requiredPrimaryPermissions())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("grant_required_permissions_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ios.blue.copy(alpha = 0.14f),
                            contentColor = ios.blue
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                    ) {
                        Text(
                            text = "Request Bluetooth & Location Together",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Required Readiness Group
                IosGroupedSection(
                    header = "Required Before Contact Setup",
                    footer = "Bluetooth ON, Nearby Devices, and Location are mandatory so holding two phones together detects proximity and plays the Name Drop sweep."
                ) {
                    IosActionRow(
                        title = "Bluetooth Power",
                        subtitle = if (readiness.isBluetoothEnabled) {
                            "Bluetooth radio is ON"
                        } else {
                            "Tap to turn Bluetooth ON"
                        },
                        icon = Icons.Rounded.Bluetooth,
                        badgeColor = ios.blue,
                        trailingText = if (readiness.isBluetoothEnabled) "ON" else "Turn On",
                        trailingStatusColor = if (readiness.isBluetoothEnabled) ios.green else ios.red,
                        showChevron = !readiness.isBluetoothEnabled,
                        testTag = "perm_bluetooth_power",
                        onClick = { requestBluetoothEnable() }
                    )
                    IosHairlineDivider()

                    IosActionRow(
                        title = "Bluetooth Nearby Permissions",
                        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "Scan, Connect & Advertise for BLE handshakes"
                        } else {
                            "Included on Android ${Build.VERSION.RELEASE}"
                        },
                        icon = Icons.Rounded.BluetoothSearching,
                        badgeColor = IosIndigo,
                        trailingText = if (readiness.hasAllBluetoothPermissions) "Granted" else "Allow",
                        trailingStatusColor = if (readiness.hasAllBluetoothPermissions) ios.green else ios.red,
                        showChevron = !readiness.hasAllBluetoothPermissions,
                        testTag = "perm_bluetooth_runtime",
                        onClick = { requestBluetoothPermissions() }
                    )
                    IosHairlineDivider()

                    IosActionRow(
                        title = "Location Permission (BLE Scanning)",
                        subtitle = if (readiness.hasFineLocation) {
                            "Precise location granted for BLE scan & country check"
                        } else {
                            "Required by Android OS to discover nearby BLE devices"
                        },
                        icon = Icons.Rounded.LocationOn,
                        badgeColor = ios.green,
                        trailingText = if (readiness.hasForegroundLocation) "Granted" else "Allow",
                        trailingStatusColor = if (readiness.hasForegroundLocation) ios.green else ios.red,
                        showChevron = !readiness.hasForegroundLocation,
                        testTag = "perm_foreground_location",
                        onClick = { requestForegroundLocation() }
                    )
                    IosHairlineDivider()

                    IosActionRow(
                        title = "Device Location Toggle",
                        subtitle = if (readiness.isLocationServicesEnabled) {
                            "System Location Services are ON"
                        } else {
                            "Turn Location ON in System Settings so scans see nearby phones"
                        },
                        icon = Icons.Rounded.GpsFixed,
                        badgeColor = IosTeal,
                        trailingText = if (readiness.isLocationServicesEnabled) "ON" else "Enable",
                        trailingStatusColor = if (readiness.isLocationServicesEnabled) ios.green else ios.red,
                        showChevron = !readiness.isLocationServicesEnabled,
                        testTag = "perm_location_toggle",
                        onClick = {
                            runCatching {
                                genericSettingsLauncher.launch(BlePermissions.createLocationSettingsIntent())
                            }
                        }
                    )
                }

                // Guided Background & Overlay Section
                IosGroupedSection(
                    header = "Background & Popup Reliability (${readiness.backgroundReadyCount}/4)",
                    footer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        "Android 11+ requires granting Location above first, then choosing 'Allow all the time' for Background Location. If a prompt does not appear, tap again to open App Settings."
                    } else {
                        "Enables low-power background BLE scanning and pop-up Contact Cards over other apps."
                    }
                ) {
                    IosActionRow(
                        title = "Background Location",
                        subtitle = when {
                            readiness.hasBackgroundLocation -> "Allowed all the time for background BLE"
                            !readiness.hasForegroundLocation -> "Grant Location Permission above first"
                            else -> "Tap to select 'Allow all the time' for background discovery"
                        },
                        icon = Icons.Rounded.MyLocation,
                        badgeColor = IosPurple,
                        trailingText = if (readiness.hasBackgroundLocation) "All Time" else "Set Up",
                        trailingStatusColor = if (readiness.hasBackgroundLocation) ios.green else ios.orange,
                        showChevron = !readiness.hasBackgroundLocation,
                        testTag = "perm_background_location",
                        onClick = { requestBackgroundLocation() }
                    )
                    IosHairlineDivider()

                    IosActionRow(
                        title = "Background Battery Exemption",
                        subtitle = if (readiness.isIgnoringBatteryOptimizations) {
                            "Unrestricted background scanning enabled"
                        } else {
                            "Prevent Android from sleeping the BLE proximity service"
                        },
                        icon = Icons.Rounded.BatterySaver,
                        badgeColor = IosOrange,
                        trailingText = if (readiness.isIgnoringBatteryOptimizations) "Unrestricted" else "Allow",
                        trailingStatusColor = if (readiness.isIgnoringBatteryOptimizations) ios.green else ios.orange,
                        showChevron = !readiness.isIgnoringBatteryOptimizations,
                        testTag = "perm_battery_optimization",
                        onClick = {
                            runCatching {
                                genericSettingsLauncher.launch(
                                    BlePermissions.createBatteryOptimizationIntent(context)
                                )
                            }.onFailure {
                                runCatching {
                                    genericSettingsLauncher.launch(
                                        BlePermissions.createBatterySettingsFallbackIntent()
                                    )
                                }
                            }
                        }
                    )
                    IosHairlineDivider()

                    IosActionRow(
                        title = "Display Over Other Apps",
                        subtitle = if (readiness.canDrawOverlays) {
                            "Contact Cards can pop up while using other apps"
                        } else {
                            "Optional — without this, Name Drop works while open on screen"
                        },
                        icon = Icons.Rounded.Layers,
                        badgeColor = ios.blue,
                        trailingText = if (readiness.canDrawOverlays) "Allowed" else "Enable",
                        trailingStatusColor = if (readiness.canDrawOverlays) ios.green else ios.labelSecondary,
                        showChevron = !readiness.canDrawOverlays,
                        testTag = "perm_overlay",
                        onClick = {
                            runCatching {
                                genericSettingsLauncher.launch(
                                    BlePermissions.createOverlaySettingsIntent(context)
                                )
                            }
                        }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        IosHairlineDivider()
                        IosActionRow(
                            title = "Notifications",
                            subtitle = "Keeps the foreground BLE service reliable",
                            icon = Icons.Rounded.NotificationsActive,
                            badgeColor = ios.red,
                            trailingText = if (readiness.hasNotificationPermission) "Allowed" else "Allow",
                            trailingStatusColor = if (readiness.hasNotificationPermission) ios.green else ios.orange,
                            showChevron = !readiness.hasNotificationPermission,
                            testTag = "perm_notifications",
                            onClick = {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        )
                    }

                    IosHairlineDivider()
                    IosActionRow(
                        title = "Contacts Auto-Save",
                        subtitle = if (readiness.hasContactsPermissions) {
                            "Automatically saves received & swapped numbers to your Phone Book"
                        } else {
                            "Recommended — saves shared phone numbers directly to Contacts"
                        },
                        icon = Icons.Rounded.Contacts,
                        badgeColor = ios.green,
                        trailingText = if (readiness.hasContactsPermissions) "Allowed" else "Allow",
                        trailingStatusColor = if (readiness.hasContactsPermissions) ios.green else ios.orange,
                        showChevron = !readiness.hasContactsPermissions,
                        testTag = "perm_contacts_autosave",
                        onClick = {
                            contactsPermsLauncher.launch(BlePermissions.requiredContactsPermissions())
                        }
                    )
                }

                // Huawei / EMUI warning card if detected
                AnimatedVisibility(visible = readiness.isHuaweiDevice) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ios.orange.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = ios.orange
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Huawei EMUI Background Tip",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ios.labelPrimary
                                )
                                Text(
                                    text = "On ${readiness.deviceModelLabel}, open App Info → Battery → App launch, switch to Manage manually, and close & reopen Name Drop after granting background permissions.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ios.labelSecondary
                                )
                                TextButton(
                                    onClick = {
                                        genericSettingsLauncher.launch(
                                            BlePermissions.createAppDetailsIntent(context)
                                        )
                                    },
                                    modifier = Modifier.testTag("open_app_info_huawei")
                                ) {
                                    Text("Open App Info Settings", color = ios.blue)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Bottom Sticky Continue Gate
            Surface(
                color = ios.card,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onContinueToContactSetup,
                        enabled = readiness.canProceedToContactSetup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .testTag("continue_to_contact_setup_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ios.blue,
                            contentColor = Color.White,
                            disabledContainerColor = ios.secondaryFill,
                            disabledContentColor = ios.labelSecondary
                        )
                    ) {
                        if (readiness.canProceedToContactSetup) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (readiness.canProceedToContactSetup) {
                                stringResource(R.string.permissions_continue_button)
                            } else {
                                "Enable Bluetooth & Location First"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (readiness.canProceedToContactSetup) {
                                "Required permissions active — ready for Contact Setup"
                            } else {
                                "All 4 items in Required section must be green"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (readiness.canProceedToContactSetup) ios.green else ios.labelSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onRefreshReadiness,
                            modifier = Modifier.testTag("refresh_permissions_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Re-check permissions",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Re-check")
                        }
                    }

                    TextButton(
                        onClick = onShowBluetoothPromptAgain,
                        modifier = Modifier.testTag("cant_use_bluetooth_button")
                    ) {
                        Text(
                            text = if (!readiness.isBluetoothSupported) {
                                "No Bluetooth Hardware Detected — Enter Test Mode"
                            } else {
                                "Device can't use Bluetooth? Switch to Test Mode Only"
                            },
                            color = ios.blue,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    if (showBluetoothPrompt) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    imageVector = Icons.Rounded.BluetoothSearching,
                    contentDescription = null,
                    tint = ios.blue,
                    modifier = Modifier.size(34.dp)
                )
            },
            title = {
                Text(
                    text = "Can your device use Bluetooth?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Tap Yes to enable all required Bluetooth, Location, and overlay permissions for real phone-to-phone Name Drops.\n\nTap No if this device cannot use Bluetooth — you will go straight into the app with Test Mode unlocked."
                )
            },
            confirmButton = {
                Button(
                    onClick = onBluetoothPromptYes,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ios.blue),
                    modifier = Modifier.testTag("bt_prompt_yes_button")
                ) {
                    Text("Yes", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onBluetoothPromptNo,
                    modifier = Modifier.testTag("bt_prompt_no_button")
                ) {
                    Text("No (Test Mode Only)", color = ios.orange, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}
