package com.example.ble

import android.os.Build
import com.example.model.PermissionReadinessState
import com.example.model.SelfTestReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeviceDiagnostics {

    fun isHuaweiMate20Lite(): Boolean {
        val mfg = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        return (mfg.contains("huawei", ignoreCase = true) ||
            mfg.contains("honor", ignoreCase = true)) &&
            (model.contains("SNE-", ignoreCase = true) ||
                model.contains("Mate 20", ignoreCase = true) ||
                hardware.contains("kirin710", ignoreCase = true))
    }

    fun isHuaweiOrHonor(): Boolean {
        val mfg = Build.MANUFACTURER.orEmpty()
        return mfg.contains("huawei", ignoreCase = true) ||
            mfg.contains("honor", ignoreCase = true)
    }

    fun evaluateLocalSelfTest(readiness: PermissionReadinessState): SelfTestReport {
        val now = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        val huaweiMate20 = isHuaweiMate20Lite()
        val issues = mutableListOf<String>()

        if (!readiness.isBluetoothEnabled) {
            issues.add("Bluetooth is currently turned OFF.")
        }
        if (!readiness.hasAllBluetoothPermissions || !readiness.hasForegroundLocation) {
            issues.add("Bluetooth or Location permissions are missing.")
        }
        if (!readiness.isLocationServicesEnabled) {
            issues.add("Device Location toggle is OFF, which blocks BLE scan results.")
        }
        if (!readiness.hasBackgroundLocation) {
            issues.add("Background Location is not set to 'Allow all the time'.")
        }
        if (!readiness.isIgnoringBatteryOptimizations) {
            issues.add("Battery optimization is still restricting background service uptime.")
        }
        if (!readiness.canDrawOverlays) {
            issues.add("Display Over Other Apps is off (foreground-only mode active).")
        }

        val needsReopen = !readiness.isIgnoringBatteryOptimizations ||
            !readiness.hasBackgroundLocation ||
            (isHuaweiOrHonor() && !readiness.canDrawOverlays)

        val headline = when {
            !readiness.canProceedToContactSetup -> "Action Needed: Enable Bluetooth & Location"
            huaweiMate20 && needsReopen -> "Huawei Mate 20 Lite Background Check"
            needsReopen -> "Close & Reopen Recommended After Background Setup"
            !readiness.canDrawOverlays -> "Foreground-Only Mode (Overlay Off)"
            else -> "All Systems Ready for Background & Foreground"
        }

        val detail = if (issues.isEmpty()) {
            "Bluetooth, Location, Background Location, Battery Exemption, and Display Over Other Apps are all active."
        } else {
            val huaweiNote = if (isHuaweiOrHonor()) {
                " On Huawei EMUI (including Mate 20 Lite), set App Launch to Manage Manually and close/reopen Name Drop so background scanning locks in."
            } else {
                " After enabling background settings, close and reopen Name Drop to refresh the foreground service."
            }
            issues.joinToString(" ") + huaweiNote
        }

        return SelfTestReport(
            checkedAtText = now,
            requiresReopenWarning = needsReopen || !readiness.canProceedToContactSetup,
            headline = headline,
            message = detail,
            isHuaweiMate20Lite = huaweiMate20
        )
    }
}
