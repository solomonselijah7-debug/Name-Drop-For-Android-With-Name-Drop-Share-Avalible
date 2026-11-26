package com.example

import com.example.ai.YearlyUpdateSchedule
import com.example.ble.BleChunkProtocol
import com.example.model.ContactCard
import com.example.model.GlowPreviewMode
import com.example.model.GlowThemeStyle
import com.example.model.PermissionReadinessState
import com.example.model.PosterFontStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NameDropUnitTest {

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

        // Settings preview overrides
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
    fun `full 365 day schedule covers Jan 1 through Dec 31`() {
        val all2026 = YearlyUpdateSchedule.getFullYearEntries(2026)
        assertEquals(365, all2026.size)
        assertEquals("2026-01-01", all2026.first().dateKey)
        assertEquals("2026-12-31", all2026.last().dateKey)
    }

    @Test
    fun `ble chunk protocol preserves contact card, portrait, background profile, and glow accent`() {
        val originalPortrait = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val originalBackdrop = byteArrayOf(11, 22, 33, 44, 55, 66)
        val card = ContactCard(
            displayName = "Leah Solomons",
            phoneNumber = "+27 82 555 0101",
            email = "leah@example.com",
            fontStyle = PosterFontStyle.ROUNDED,
            fullScreenBackgroundEnabled = true,
            glowAccent = GlowThemeStyle.GREEN_GLOW
        )
        val encoded = BleChunkProtocol.encodeContactPayload(card, originalPortrait, originalBackdrop)
        val decoded = BleChunkProtocol.decodePayload(encoded)
        assertNotNull(decoded)
        assertTrue(decoded!!.verifiedSha256)
        assertEquals("Leah Solomons", decoded.contact?.displayName)
        assertEquals("+27 82 555 0101", decoded.contact?.phoneNumber)
        assertEquals(true, decoded.contact?.fullScreenBackgroundEnabled)
        assertEquals(GlowThemeStyle.GREEN_GLOW, decoded.contact?.glowAccent)
        assertArrayEquals(originalPortrait, decoded.photoBytes)
        assertArrayEquals(originalBackdrop, decoded.backgroundPhotoBytes)
    }

    @Test
    fun `ble chunk protocol roundtrips video share payload with mimeType and video flag`() {
        val sampleVideoBytes = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 109, 112, 52, 50)
        val encoded = BleChunkProtocol.encodeMediaPayload(
            senderName = "Jordan",
            fileName = "Sunset.mp4",
            mediaBytes = sampleVideoBytes,
            mimeType = "video/mp4",
            isVideo = true,
            isGroupShare = false
        )
        val decoded = BleChunkProtocol.decodePayload(encoded)
        assertNotNull(decoded)
        assertEquals(BleChunkProtocol.TYPE_VIDEO_SHARE, decoded!!.type)
        assertTrue(decoded.isVideo)
        assertEquals("video/mp4", decoded.mimeType)
        assertEquals("Sunset.mp4", decoded.fileName)
        assertEquals("Jordan", decoded.senderName)
        assertArrayEquals(sampleVideoBytes, decoded.photoBytes)
    }
}
