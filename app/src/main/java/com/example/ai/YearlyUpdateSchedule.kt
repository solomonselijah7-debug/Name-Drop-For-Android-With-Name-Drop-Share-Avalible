package com.example.ai

import com.example.model.DailyUpdateEntry
import com.example.model.GlowPreviewMode
import com.example.model.GlowThemeStyle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object YearlyUpdateSchedule {

    data class CountryClockStatus(
        val label: String,
        val zoneId: String,
        val localTimeText: String,
        val isPastNoon: Boolean,
        val nextNoonCountdown: String
    )

    private val milestoneThemes = listOf(
        "01-01" to ("New Year Contact Refresh" to "Start the year with an updated Contact Poster and fresh photo."),
        "01-15" to ("Winter Studio Portrait" to "Balanced contrast framing for indoor BLE swaps."),
        "02-14" to ("Quick Share Duo Drop" to "Hold phones edge-to-edge for instant two-way card exchange."),
        "03-01" to ("Spring Clean Contacts" to "Review optional handle and company fields before networking."),
        "03-21" to ("Equinox Signal Tune" to "Check your -48 dBm proximity slider for case thickness."),
        "04-01" to ("Group Drop Ready" to "Send event photos & videos to nearby friends in one sweep."),
        "04-22" to ("Low-Power Eco Scan" to "Background BLE uses minimal ~0.1% battery footprint."),
        "05-10" to ("Exact Photo Sync Check" to "SHA-256 verification preserves your exact Contact Poster image."),
        "06-01" to ("Mid-Year Contact Audit" to "Refresh your phone number and country prefix for travel."),
        "06-21" to ("Summer Group Share" to "Share sunny gallery shots & clips directly to Camera Roll."),
        "07-04" to ("Firework Media Drop" to "Fast single-tap Accept saves full-resolution shots immediately."),
        "07-18" to ("Community Connect Day" to "Great day for testing multi-device Group Share in range."),
        "08-08" to ("Background Guard Check" to "Verify Battery Optimization exemption stays enabled."),
        "08-25" to ("EMUI & OEM Reliability" to "Confirm App Launch manual control on Huawei devices."),
        "09-01" to ("Spring / Autumn Sync" to "Update your Contact Poster palette for a clean seasonal look."),
        "09-24" to ("Heritage Photo Archive" to "Save shared family photos straight into Pictures/NameDrop."),
        "10-10" to ("Crisp Typography Drop" to "Experiment with Heavy vs Rounded Contact Poster display fonts."),
        "10-31" to ("Dark Mode Poster Night" to "Full OLED dark mode paired with #0A84FF system accents."),
        "11-11" to ("11:11 Precision Chime" to "Audio-synced 3-2-1 perimeter sweep hits right on the chime."),
        "11-24" to ("Pre-Glow Readiness" to "Make sure Bluetooth, Location, and Background Location are green before tomorrow's Glow Up."),
        "11-25" to ("Glow Up Edition: Red Glow" to "Your Contact Card lights up along its outer edges in crisp Red Glow while keeping your photo and text sharp."),
        "11-26" to ("Glow Up Edition: Green Glow" to "Local date schedule unlocks Green Glow around your full-screen Name Drop card and sweep highlights."),
        "11-27" to ("Glow Up Edition: Purple Surrounded Glow" to "Purple Surrounded Glow wraps the entire Name Drop card perimeter in rich royal purple."),
        "12-01" to ("Holiday Group Drop" to "Share holiday gatherings with nearby devices in one step."),
        "12-25" to ("Festive Media Swap" to "Instant BLE photo and video delivery without cables or cloud accounts."),
        "12-31" to ("Year-End Contact Recap" to "Polish your Contact Poster for the upcoming year.")
    ).toMap()

    private val rotatingDailyTitles = listOf(
        "Signal Clarity Spotlight" to "Keep phone tops aligned within 10 cm for strongest RSSI lock.",
        "Poster Contrast Polish" to "Pair high-contrast name text with your custom full-screen backdrop.",
        "Zero-NFC Freedom" to "Pure BLE discovery works reliably on phones without NFC hardware.",
        "Gallery Direct Save" to "Accepted photos and videos go straight into your device Gallery with zero extra steps.",
        "Privacy First Sharing" to "Receive Only mode lets you accept a card without sending yours back.",
        "Smart Threshold Tuning" to "Adjust RSSI between -30 and -70 dBm in Settings for thick cases.",
        "Instant Card Preview" to "Tap the (i) info pill on any received Contact Card to view notes and email.",
        "Overlay Pop-Up Speed" to "Display Over Other Apps lets incoming Contact Cards pop up even when closed.",
        "Foreground Safety Mode" to "Even without overlay permission, Name Drop works whenever the app is open.",
        "Group Share Coordination" to "Wait for the sweep finish before nearby devices prompt Accept.",
        "Haptic Chime Sync" to "Visual sweep, tactile haptics, and chime converge together at bottom center.",
        "Country Prefix Check" to "Country verification keeps your international dialing prefix accurate.",
        "Clean GATT Handshake" to "Every transfer closes GATT sockets cleanly to keep Bluetooth responsive.",
        "Developer Diagnostics" to "Inspect live RSSI and scan events anytime inside Settings → Debug Log.",
        "Poster Font Refresh" to "Try Classic or Mono font styles for a distinctive Contact Poster identity.",
        "Dual-Phone Bench Test" to "Use the Test tab to verify sweep timing and sound before meeting friends.",
        "Battery-Friendly Scanning" to "Foreground service switches to low-power BLE automatically when backgrounded.",
        "Exact Image Integrity" to "Every Contact Card photo is checksum-verified before rendering.",
        "Travel Prefix Helper" to "Re-verify your country in Settings whenever roaming abroad.",
        "Smooth Sheet Dismissal" to "Swipe or tap Done after saving a contact to return to listening mode.",
        "Clean Light & Dark UI" to "System-matched iOS surfaces adapt seamlessly day or night.",
        "Quick Contact Notes" to "Add your social handle or company so new connections remember where you met.",
        "High-TX Advertising" to "Active Name Drop mode broadcasts high-visibility BLE packets for fast locks.",
        "Location Toggle Guard" to "Remember Android requires Location Services ON for BLE scan results."
    )

    /**
     * Pure local YYYY-MM-DD date-based glow schedule (no cloud required).
     * Maps local calendar dates directly so Nov 25 = Red Glow, Nov 26 = Green Glow,
     * Nov 27 = Purple Surrounded Glow starting at 00:00 on that local date.
     */
    fun getLocalDateKey(
        zoneId: String = TimeZone.getDefault().id,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val tz = TimeZone.getTimeZone(zoneId)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun getGlowThemeForDateKey(dateKey: String): GlowThemeStyle {
        val monthDay = if (dateKey.length >= 10) dateKey.substring(5, 10) else dateKey
        return when (monthDay) {
            "11-25" -> GlowThemeStyle.RED_EDGE
            "11-26" -> GlowThemeStyle.GREEN_GLOW
            "11-27" -> GlowThemeStyle.PURPLE_SURROUNDED
            else -> GlowThemeStyle.NONE
        }
    }

    fun resolveActiveGlowTheme(
        previewMode: GlowPreviewMode,
        zoneId: String = TimeZone.getDefault().id,
        nowMillis: Long = System.currentTimeMillis()
    ): GlowThemeStyle {
        return when (previewMode) {
            GlowPreviewMode.AUTO_LOCAL_DATE -> getGlowThemeForDateKey(getLocalDateKey(zoneId, nowMillis))
            GlowPreviewMode.FORCE_NOV_25_RED -> GlowThemeStyle.RED_EDGE
            GlowPreviewMode.FORCE_NOV_26_GREEN -> GlowThemeStyle.GREEN_GLOW
            GlowPreviewMode.FORCE_NOV_27_PURPLE -> GlowThemeStyle.PURPLE_SURROUNDED
            GlowPreviewMode.OFF -> GlowThemeStyle.NONE
        }
    }

    /**
     * Computes the effective edition calendar (YYYY-MM-DD) respecting the 12:00 PM
     * local-time release gate for daily news/tip editions across every country/timezone.
     */
    fun getEffectiveEditionCalendar(
        zoneId: String = TimeZone.getDefault().id,
        nowMillis: Long = System.currentTimeMillis()
    ): Calendar {
        val tz = TimeZone.getTimeZone(zoneId)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        if (hourOfDay < 12) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal
    }

    fun getEntryForDate(
        year: Int,
        month1Based: Int,
        dayOfMonth: Int,
        geminiOverlayTip: String? = null
    ): DailyUpdateEntry {
        val dateKey = String.format(Locale.US, "%04d-%02d-%02d", year, month1Based, dayOfMonth)
        val monthDay = String.format(Locale.US, "%02d-%02d", month1Based, dayOfMonth)

        val cal = Calendar.getInstance(Locale.US).apply {
            set(year, month1Based - 1, dayOfMonth)
        }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        val milestone = milestoneThemes[monthDay]
        val rotation = rotatingDailyTitles[(dayOfYear - 1).mod(rotatingDailyTitles.size)]
        val glowTheme = getGlowThemeForDateKey(dateKey)
        val isNov25GlowUp = glowTheme == GlowThemeStyle.RED_EDGE

        val title = milestone?.first ?: rotation.first
        val subtitle = milestone?.second ?: rotation.second
        val category = when {
            glowTheme != GlowThemeStyle.NONE -> "Glow Schedule • ${glowTheme.label}"
            milestone != null -> "Calendar Milestone"
            else -> "Daily Edition #$dayOfYear"
        }

        val featureHighlight = when (glowTheme) {
            GlowThemeStyle.RED_EDGE -> "Contact Card lights up along its outer edges in crisp Red Glow while keeping interior text & portrait sharp."
            GlowThemeStyle.GREEN_GLOW -> "November 26 activates crisp Green Glow across your full-screen Name Drop card and sweep aura."
            GlowThemeStyle.PURPLE_SURROUNDED -> "November 27 unlocks Purple Surrounded Glow wrapping the entire Contact Card perimeter."
            GlowThemeStyle.NONE -> geminiOverlayTip?.takeIf { it.isNotBlank() } ?: rotation.second
        }

        return DailyUpdateEntry(
            dateKey = dateKey,
            monthDayKey = monthDay,
            title = title,
            subtitle = subtitle,
            category = category,
            featureHighlight = featureHighlight,
            glowTheme = glowTheme,
            hasRedEdgeGlowUp = isNov25GlowUp,
            proximityTip = "Active RSSI trigger target: -48 dBm balanced (adjustable -30 to -70 dBm).",
            isFromGemini = !geminiOverlayTip.isNullOrBlank(),
            unlockedAtLocalNoonText = "12:00 PM Local"
        )
    }

    /**
     * Returns today's entry respecting the local YYYY-MM-DD glow schedule immediately on date change,
     * while preserving 12:00 PM local-time gating for non-glow daily editions.
     */
    fun getEffectiveTodayEntry(
        zoneId: String = TimeZone.getDefault().id,
        nowMillis: Long = System.currentTimeMillis(),
        geminiTip: String? = null
    ): DailyUpdateEntry {
        val cal = getEffectiveEditionCalendar(zoneId, nowMillis)
        val editionEntry = getEntryForDate(
            year = cal.get(Calendar.YEAR),
            month1Based = cal.get(Calendar.MONTH) + 1,
            dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
            geminiOverlayTip = geminiTip
        )
        val localDateKey = getLocalDateKey(zoneId, nowMillis)
        val localGlow = getGlowThemeForDateKey(localDateKey)
        return if (localGlow != GlowThemeStyle.NONE && editionEntry.glowTheme == GlowThemeStyle.NONE) {
            val parts = localDateKey.split("-")
            getEntryForDate(
                year = parts[0].toInt(),
                month1Based = parts[1].toInt(),
                dayOfMonth = parts[2].toInt(),
                geminiOverlayTip = geminiTip
            )
        } else {
            editionEntry
        }
    }

    fun getFullYearEntries(year: Int = Calendar.getInstance().get(Calendar.YEAR)): List<DailyUpdateEntry> {
        val list = ArrayList<DailyUpdateEntry>(366)
        val cal = Calendar.getInstance(Locale.US).apply {
            set(year, Calendar.JANUARY, 1, 12, 0, 0)
        }
        while (cal.get(Calendar.YEAR) == year) {
            list.add(
                getEntryForDate(
                    year = cal.get(Calendar.YEAR),
                    month1Based = cal.get(Calendar.MONTH) + 1,
                    dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
                )
            )
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return list
    }

    fun getWorldClockStatuses(nowMillis: Long = System.currentTimeMillis()): List<CountryClockStatus> {
        val zones = listOf(
            "Device Local" to TimeZone.getDefault().id,
            "South Africa (SAST)" to "Africa/Johannesburg",
            "US Eastern (ET)" to "America/New_York",
            "US Pacific (PT)" to "America/Los_Angeles",
            "United Kingdom (UK)" to "Europe/London"
        )
        return zones.map { (label, zoneId) ->
            val tz = TimeZone.getTimeZone(zoneId)
            val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }
            val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US).apply { timeZone = tz }
            val timeStr = fmt.format(Date(nowMillis))
            val isPastNoon = cal.get(Calendar.HOUR_OF_DAY) >= 12

            val nextNoon = Calendar.getInstance(tz).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val diffMin = ((nextNoon.timeInMillis - nowMillis) / 60_000L).coerceAtLeast(0L)
            val hrs = diffMin / 60L
            val mins = diffMin % 60L
            val countdown = "${hrs}h ${mins}m until 12:00 PM drop"

            CountryClockStatus(
                label = label,
                zoneId = zoneId,
                localTimeText = timeStr,
                isPastNoon = isPastNoon,
                nextNoonCountdown = countdown
            )
        }
    }
}
