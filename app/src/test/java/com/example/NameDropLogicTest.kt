package com.example

import com.example.ai.YearlyUpdateSchedule
import com.example.model.GlowPreviewMode
import com.example.model.GlowThemeStyle
import com.example.model.PermissionReadinessState
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
}
