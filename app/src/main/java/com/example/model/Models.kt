package com.example.model

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class NavTab(val label: String) {
    NAME_DROP("Name Drop"),
    SHARE("Share"),
    TEST("Test"),
    UPDATES("Updates"),
    SETTINGS("Settings"),
    TUTORIAL("Tutorial")
}

enum class OnboardingStage {
    PERMISSIONS_GATE,
    CONTACT_SETUP,
    MAIN_APP
}

enum class PosterFontStyle(val label: String) {
    ROUNDED("Rounded"),
    CLASSIC("Classic"),
    BOLD("Heavy"),
    MONO("Mono")
}

enum class GlowThemeStyle(val label: String) {
    NONE("Standard"),
    RED_EDGE("Red Glow"),
    GREEN_GLOW("Green Glow"),
    PURPLE_SURROUNDED("Purple Surrounded Glow")
}

enum class GlowPreviewMode(val label: String) {
    AUTO_LOCAL_DATE("Auto (Device Date)"),
    FORCE_NOV_25_RED("Nov 25 • Red Glow"),
    FORCE_NOV_26_GREEN("Nov 26 • Green Glow"),
    FORCE_NOV_27_PURPLE("Nov 27 • Purple Surrounded Glow"),
    OFF("Off")
}

@Immutable
data class ContactCard(
    val displayName: String = "",
    val phoneNumber: String = "",
    val email: String = "",
    val company: String = "",
    val handle: String = "",
    val notes: String = "",
    val backgroundColorHex: Long = 0xFF1E293B,
    val nameColorHex: Long = 0xFFFFFFFF,
    val fontStyle: PosterFontStyle = PosterFontStyle.BOLD,
    val photoPath: String? = null,
    val photoSha256: String? = null,
    val backgroundPhotoPath: String? = null,
    val backgroundPhotoSha256: String? = null,
    val fullScreenBackgroundEnabled: Boolean = true,
    val glowAccent: GlowThemeStyle = GlowThemeStyle.NONE
) {
    val isConfigured: Boolean
        get() = displayName.isNotBlank() && phoneNumber.isNotBlank()
}

@Immutable
data class PermissionReadinessState(
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val hasBluetoothScan: Boolean = false,
    val hasBluetoothConnect: Boolean = false,
    val hasBluetoothAdvertise: Boolean = false,
    val hasFineLocation: Boolean = false,
    val hasCoarseLocation: Boolean = false,
    val isLocationServicesEnabled: Boolean = false,
    val hasBackgroundLocation: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val isHuaweiDevice: Boolean = false,
    val deviceModelLabel: String = ""
) {
    val hasAllBluetoothPermissions: Boolean
        get() = hasBluetoothScan && hasBluetoothConnect && hasBluetoothAdvertise

    val hasForegroundLocation: Boolean
        get() = hasFineLocation || hasCoarseLocation

    val canProceedToContactSetup: Boolean
        get() = isBluetoothSupported &&
            isBluetoothEnabled &&
            hasAllBluetoothPermissions &&
            hasForegroundLocation &&
            isLocationServicesEnabled

    val backgroundReadyCount: Int
        get() = listOf(
            hasBackgroundLocation,
            isIgnoringBatteryOptimizations,
            canDrawOverlays,
            hasNotificationPermission
        ).count { it }

    val isFullyBackgroundCapable: Boolean
        get() = canProceedToContactSetup && hasBackgroundLocation && canDrawOverlays
}

@Immutable
data class BleProximityState(
    val isNameDropEnabled: Boolean = false,
    val isShareEnabled: Boolean = false,
    val isReceiveListening: Boolean = false,
    val isAwaitingMutualShare: Boolean = false,
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val nearestDeviceName: String? = null,
    val nearestRssi: Int? = null,
    val smoothedRssi: Int? = null,
    val rssiThreshold: Int = -48,
    val statusMessage: String = "Ready",
    val activeConnections: Int = 0,
    val bondedDeviceCount: Int = 0,
    val pairedDeviceNames: List<String> = emptyList(),
    val nearbySignalCount: Int = 0,
    val matchedPeerIsNameDropApp: Boolean = false,
    val lastScanError: String? = null
)

@Immutable
data class IncomingContactOffer(
    val deviceAddress: String,
    val deviceName: String,
    val contact: ContactCard,
    val rssi: Int,
    val photoBytes: ByteArray? = null,
    val backgroundPhotoBytes: ByteArray? = null,
    val isTestSimulation: Boolean = false,
    val isReceiveOnlyMode: Boolean = false,
    val isSwappedExchange: Boolean = false
)

@Immutable
data class IncomingPhotoOffer(
    val transferId: String,
    val senderName: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String = "image/jpeg",
    val isVideo: Boolean = false,
    val isGroupShare: Boolean = false,
    val previewUri: Uri? = null,
    val photoBytes: ByteArray? = null,
    val isTestSimulation: Boolean = false
)

enum class TransferPhase {
    IDLE,
    WAITING_NEARBY,
    HOLD_TO_SHARE,
    SWEEPING,
    WAITING_ACCEPTANCE,
    TRANSFERRING,
    SAVED,
    DECLINED,
    ERROR
}

@Immutable
data class PhotoTransferState(
    val phase: TransferPhase = TransferPhase.IDLE,
    val progress: Float = 0f,
    val statusText: String = "",
    val savedGalleryUri: Uri? = null,
    val isVideo: Boolean = false,
    val mimeType: String = "image/jpeg",
    val isGroupShare: Boolean = false,
    val groupPeerCount: Int = 0,
    val groupAcceptedCount: Int = 0,
    val peerDeviceName: String? = null
)

enum class DropBadgePhase {
    HIDDEN,
    SHARING,
    SHARED
}

@Immutable
data class DropBadgeState(
    val phase: DropBadgePhase = DropBadgePhase.HIDDEN,
    val progress: Float = 0f,
    val title: String = "Sharing",
    val subtitle: String = ""
)

@Immutable
data class ExternalShareSession(
    val uris: List<Uri> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val isVideo: Boolean = false,
    val isMultiple: Boolean = false,
    val statusMessage: String = "Hold this phone against another phone"
) {
    val isActive: Boolean
        get() = uris.isNotEmpty()
}

@Immutable
data class CardSwapState(
    val isSwapping: Boolean = false,
    val myCard: ContactCard = ContactCard(),
    val peerCard: ContactCard = ContactCard(),
    val peerPhotoBytes: ByteArray? = null,
    val peerBgPhotoBytes: ByteArray? = null,
    val peerDeviceName: String = "Nearby Phone",
    val peerRssi: Int = -44
)

enum class SweepTarget {
    NONE,
    CONTACT_CARD_EXCHANGE,
    CONTACT_CARD_SWAP,
    SHARE_HUB_UNLOCK,
    GROUP_SHARE_INIT,
    EXTERNAL_SHARE_DROP,
    BACKGROUND_PROXIMITY_TRIGGER,
    TEST_PERIMETER_ONLY,
    TEST_GLOW_UP_PREVIEW
}

@Immutable
data class CountryProfile(
    val countryName: String = "United States",
    val countryCode: String = "US",
    val dialPrefix: String = "+1",
    val zoneId: String = "America/Los_Angeles",
    val isVerified: Boolean = false
)

@Immutable
data class DailyUpdateEntry(
    val dateKey: String,
    val monthDayKey: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val featureHighlight: String,
    val glowTheme: GlowThemeStyle = GlowThemeStyle.NONE,
    val hasRedEdgeGlowUp: Boolean = false,
    val proximityTip: String = "",
    val isFromGemini: Boolean = false,
    val unlockedAtLocalNoonText: String = "12:00 PM"
)

@Immutable
data class SelfTestReport(
    val checkedAtText: String = "Not checked yet",
    val requiresReopenWarning: Boolean = false,
    val headline: String = "Background Health Ready",
    val message: String = "",
    val isHuaweiMate20Lite: Boolean = false
)

@Immutable
data class DebugLogEntry(
    val id: Long,
    val timestamp: String,
    val tag: String,
    val level: String,
    val message: String
)
