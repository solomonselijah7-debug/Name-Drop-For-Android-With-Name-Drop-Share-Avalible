package com.example

import com.example.ai.GeminiDiagnosticWatcher
import com.example.ai.YearlyUpdateSchedule
import com.example.data.ContactBookRepository
import com.example.data.WhatsNewCatalog
import com.example.model.BleProximityState
import com.example.model.ContactCard
import com.example.model.GlowPreviewMode
import com.example.model.GlowThemeStyle
import com.example.model.PermissionReadinessState
import com.example.model.SavedContactEntry
import com.example.model.SweepTarget
import com.example.model.TransferPhase
import com.example.model.WatcherHealthStatus
import com.example.model.WatcherTelemetry
import com.example.ui.overlay.FloatingShareOverlayCoordinator
import com.example.ui.overlay.OverlayShareUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class NameDropLogicTest {

    @Test
    fun `permissions gate blocks until bluetooth on, ble perms, location perm, and location toggle enabled`() {
        val btOff = PermissionReadinessState(
            isBluetoothSupported = true,
            isBluetoothEnabled = false,
            hasBluetoothScan = true,
            hasBluetoothConnect = true,
            hasBluetoothAdvertise = true,
            hasFineLocation = true,
            isLocationServicesEnabled = true
        )
        assertFalse(btOff.canProceedToContactSetup)

        val locToggleOff = btOff.copy(isBluetoothEnabled = true, isLocationServicesEnabled = false)
        assertFalse(locToggleOff.canProceedToContactSetup)

        val missingScanPerm = btOff.copy(isBluetoothEnabled = true, hasBluetoothScan = false)
        assertFalse(missingScanPerm.canProceedToContactSetup)

        val allRequiredReady = btOff.copy(isBluetoothEnabled = true)
        assertTrue(allRequiredReady.canProceedToContactSetup)
    }

    @Test
    fun `local date glow schedule maps Nov 25 Red, Nov 26 Green, and Nov 27 Purple Surrounded Glow offline`() {
        assertEquals(GlowThemeStyle.RED_EDGE, YearlyUpdateSchedule.getGlowThemeForDateKey("2026-11-25"))
        assertEquals(GlowThemeStyle.GREEN_GLOW, YearlyUpdateSchedule.getGlowThemeForDateKey("2026-11-26"))
        assertEquals(GlowThemeStyle.PURPLE_SURROUNDED, YearlyUpdateSchedule.getGlowThemeForDateKey("2026-11-27"))
        assertEquals(GlowThemeStyle.NONE, YearlyUpdateSchedule.getGlowThemeForDateKey("2026-11-28"))

        val ptTz = TimeZone.getTimeZone("America/Los_Angeles")
        val nov26Morning = Calendar.getInstance(ptTz).apply {
            set(2026, Calendar.NOVEMBER, 26, 6, 30, 0)
        }.timeInMillis
        assertEquals(
            GlowThemeStyle.GREEN_GLOW,
            YearlyUpdateSchedule.resolveActiveGlowTheme(GlowPreviewMode.AUTO_LOCAL_DATE, "America/Los_Angeles", nov26Morning)
        )

        val nov27Midnight = Calendar.getInstance(ptTz).apply {
            set(2026, Calendar.NOVEMBER, 27, 0, 5, 0)
        }.timeInMillis
        assertEquals(
            GlowThemeStyle.PURPLE_SURROUNDED,
            YearlyUpdateSchedule.resolveActiveGlowTheme(GlowPreviewMode.AUTO_LOCAL_DATE, "America/Los_Angeles", nov27Midnight)
        )

        assertEquals(
            GlowThemeStyle.PURPLE_SURROUNDED,
            YearlyUpdateSchedule.resolveActiveGlowTheme(GlowPreviewMode.FORCE_NOV_27_PURPLE, "America/Los_Angeles", nov26Morning)
        )
        assertEquals(
            GlowThemeStyle.NONE,
            YearlyUpdateSchedule.resolveActiveGlowTheme(GlowPreviewMode.OFF, "America/Los_Angeles", nov26Morning)
        )
    }

    @Test
    fun `12pm local time gate governs daily editions while local date unlocks glow at midnight`() {
        val zaTz = TimeZone.getTimeZone("Africa/Johannesburg")
        val beforeNoonNonGlow = Calendar.getInstance(zaTz).apply {
            set(2026, Calendar.NOVEMBER, 20, 11, 59, 0)
        }.timeInMillis
        val entryBeforeNoon = YearlyUpdateSchedule.getEffectiveTodayEntry(
            zoneId = "Africa/Johannesburg",
            nowMillis = beforeNoonNonGlow
        )
        assertEquals("2026-11-19", entryBeforeNoon.dateKey)
        assertFalse(entryBeforeNoon.hasRedEdgeGlowUp)

        val atNoonNonGlow = Calendar.getInstance(zaTz).apply {
            set(2026, Calendar.NOVEMBER, 20, 12, 0, 1)
        }.timeInMillis
        val entryAtNoon = YearlyUpdateSchedule.getEffectiveTodayEntry(
            zoneId = "Africa/Johannesburg",
            nowMillis = atNoonNonGlow
        )
        assertEquals("2026-11-20", entryAtNoon.dateKey)

        val nov25Morning = Calendar.getInstance(zaTz).apply {
            set(2026, Calendar.NOVEMBER, 25, 6, 0, 0)
        }.timeInMillis
        val entryNov25 = YearlyUpdateSchedule.getEffectiveTodayEntry(
            zoneId = "Africa/Johannesburg",
            nowMillis = nov25Morning
        )
        assertEquals("2026-11-25", entryNov25.dateKey)
        assertTrue(entryNov25.hasRedEdgeGlowUp)
    }

    @Test
    fun `full 365 day schedule covers Jan 1 through Dec 31`() {
        val all2026 = YearlyUpdateSchedule.getFullYearEntries(2026)
        assertEquals(365, all2026.size)
        assertEquals("2026-01-01", all2026.first().dateKey)
        assertEquals("2026-12-31", all2026.last().dateKey)
    }

    @Test
    fun `gemini diagnostic watcher sanitizes sensitive text and identifies stall root causes`() {
        val raw = "Peer +1 415 555 0198 email alex@example.com key AIzaSyDummySecretKey1234567890"
        val sanitized = GeminiDiagnosticWatcher.sanitizeText(raw)
        assertFalse(sanitized.contains("415"))
        assertFalse(sanitized.contains("alex@example.com"))
        assertFalse(sanitized.contains("AIzaSy"))
        assertTrue(sanitized.contains("[REDACTED_PHONE]"))
        assertTrue(sanitized.contains("[REDACTED_EMAIL]"))
        assertTrue(sanitized.contains("[REDACTED_API_KEY]"))

        val causes = GeminiDiagnosticWatcher.diagnoseSuspectedRootCauses(
            readiness = PermissionReadinessState(
                isBluetoothSupported = true,
                isBluetoothEnabled = true,
                hasBluetoothScan = true,
                hasBluetoothConnect = true,
                hasBluetoothAdvertise = true,
                hasFineLocation = true,
                isLocationServicesEnabled = true,
                hasBackgroundLocation = false,
                canDrawOverlays = false
            ),
            bleState = BleProximityState(
                isNameDropEnabled = true,
                isScanning = false,
                isAdvertising = true,
                rssiThreshold = -48
            ),
            telemetry = WatcherTelemetry(
                foregroundServiceRunning = false,
                lastScanFailedCode = 2
            ),
            transferPhase = TransferPhase.HOLD_TO_SHARE,
            sweepTarget = SweepTarget.NONE,
            isTestOnlyMode = false
        )
        assertTrue(causes.any { it.contains("SYSTEM_ALERT_WINDOW") })
        assertTrue(causes.any { it.contains("BleProximityService foreground service is NOT running") })
        assertTrue(causes.any { it.contains("onScanFailed(errorCode=2)") })

        val prompt = GeminiDiagnosticWatcher.buildAiFixPrompt(
            triggerReason = "No trigger after 4s",
            status = WatcherHealthStatus.STALL_DETECTED,
            readiness = PermissionReadinessState(isBluetoothSupported = true, isBluetoothEnabled = true),
            bleState = BleProximityState(isNameDropEnabled = true),
            telemetry = WatcherTelemetry(foregroundServiceRunning = false),
            transferPhase = TransferPhase.IDLE,
            sweepTarget = SweepTarget.NONE,
            isTestOnlyMode = false,
            suspectedCauses = causes,
            sanitizedLogs = "[12:00:00.000] [ERROR] BLE: ScanCallback.onScanFailed(2)"
        )
        assertTrue(prompt.contains("## Failure Summary"))
        assertTrue(prompt.contains("## Suspected Root Causes"))
        assertTrue(prompt.contains("No trigger after 4s"))
    }

    @Test
    fun `whats new shows on fresh install and updates, but stays hidden on reopen`() {
        val install1 = WhatsNewCatalog.buildInstallSignature(1L, "1.0", 1_700_000_000_000L)
        val sameReopen = WhatsNewCatalog.buildInstallSignature(1L, "1.0", 1_700_000_000_000L)
        val updatedApkTime = WhatsNewCatalog.buildInstallSignature(1L, "1.0", 1_700_000_500_000L)
        val updatedVersion = WhatsNewCatalog.buildInstallSignature(2L, "1.1", 1_700_000_500_000L)

        // Fresh install (no saved signature yet) -> shows
        assertTrue(WhatsNewCatalog.shouldShowWhatsNew("", install1))
        // App closed & reopened without update -> does not show again
        assertFalse(WhatsNewCatalog.shouldShowWhatsNew(install1, sameReopen))
        // App updated/reinstalled -> shows again
        assertTrue(WhatsNewCatalog.shouldShowWhatsNew(install1, updatedApkTime))
        assertTrue(WhatsNewCatalog.shouldShowWhatsNew(install1, updatedVersion))
        // Highlights exist
        assertTrue(WhatsNewCatalog.highlights.isNotEmpty())
    }

    @Test
    fun `contact book repository normalizes phone numbers and builds deduplication keys`() {
        assertEquals(
            "14155550194",
            ContactBookRepository.normalizePhoneForCompare("+1 (415) 555-0194")
        )
        val cardWithPhone = ContactCard(
            displayName = "Alex Mercer",
            phoneNumber = "+1 (415) 555-0194",
            email = "alex@example.com"
        )
        val cardWithSameDigits = ContactCard(
            displayName = "Alex M.",
            phoneNumber = "14155550194"
        )
        assertEquals(
            ContactBookRepository.deduplicationKey(cardWithPhone),
            ContactBookRepository.deduplicationKey(cardWithSameDigits)
        )
        assertEquals("Contact saved", ContactBookRepository.formatSavedSummary(true))
        assertTrue(ContactBookRepository.formatSavedSummary(false).contains("Contact saved locally"))
    }

    @Test
    fun `floating share overlay stays anchored through ready, armed, sharing, and shared phases`() {
        val initial = OverlayShareUiState(isActive = true, isVideo = true)
        assertEquals("Share", FloatingShareOverlayCoordinator.resolveShareActionLabel(initial))
        assertFalse(FloatingShareOverlayCoordinator.shouldAllowBackdropDismiss(initial))

        val armed = FloatingShareOverlayCoordinator.armWaitingState(initial)
        assertTrue(armed.isArmed)
        assertFalse(armed.isError)
        assertEquals("Hold Phones Close...", FloatingShareOverlayCoordinator.resolveShareActionLabel(armed))
        assertFalse(FloatingShareOverlayCoordinator.shouldAllowBackdropDismiss(armed))

        val sharing = armed.copy(isSharing = true, progress = 0.5f, statusMessage = "Sharing")
        assertEquals("Sharing...", FloatingShareOverlayCoordinator.resolveShareActionLabel(sharing))
        assertFalse(FloatingShareOverlayCoordinator.shouldAllowBackdropDismiss(sharing))

        val shared = sharing.copy(isSharing = false, isArmed = false, isSharedComplete = true, progress = 1f)
        assertEquals("Shared ✓", FloatingShareOverlayCoordinator.resolveShareActionLabel(shared))
        assertTrue(FloatingShareOverlayCoordinator.shouldAllowBackdropDismiss(shared))

        val receiveWaiting = FloatingShareOverlayCoordinator.armReceiveOnlyWaitingState(initial)
        assertTrue(receiveWaiting.isReceiveOnlyWaiting)
        assertFalse(receiveWaiting.isArmed)
        assertEquals("Waiting...", receiveWaiting.statusMessage)
        assertEquals("Waiting...", FloatingShareOverlayCoordinator.resolveReceiveActionLabel(receiveWaiting))
        assertFalse(FloatingShareOverlayCoordinator.shouldAllowBackdropDismiss(receiveWaiting))

        val unreadableError = FloatingShareOverlayCoordinator.buildUnreadableShareErrorState(
            message = "No readable photo or video received",
            peerLine = "Tap Open App or try sharing from Gallery again"
        )
        assertTrue(unreadableError.isActive)
        assertTrue(unreadableError.isError)
        assertTrue(unreadableError.canOpenFullApp)
        assertEquals("Open App", FloatingShareOverlayCoordinator.resolveShareActionLabel(unreadableError))
    }
}
