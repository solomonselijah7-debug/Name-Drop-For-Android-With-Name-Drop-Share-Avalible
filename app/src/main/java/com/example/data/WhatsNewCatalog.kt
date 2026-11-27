package com.example.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import com.example.BuildConfig

@Immutable
data class WhatsNewFeatureItem(
    val id: String,
    val title: String,
    val description: String,
    val badgeText: String
)

object WhatsNewCatalog {

    val highlights: List<WhatsNewFeatureItem> = listOf(
        WhatsNewFeatureItem(
            id = "gemini_watcher",
            title = "Gemini Intelligence Watcher",
            description = "Monitors BLE scans, background service health, overlay permissions, and handshakes in real time, generating a copyable AI Fix Prompt if proximity stalls.",
            badgeText = "New"
        ),
        WhatsNewFeatureItem(
            id = "camera_star_sweep",
            title = "Camera Bar Star & Edge Sweep",
            description = "Blue Gemini star drops from the camera cutout, sparkles and bursts with the Apple Pay chime, reforms upward, and sweeps screen edges in sync with the Gemini Live sound.",
            badgeText = "Animation"
        ),
        WhatsNewFeatureItem(
            id = "drop_progress_badge",
            title = "Drop Pop-Down & Progress Ring",
            description = "Top drop badge smoothly tracks Bluetooth photo, video, and contact transfers with a circular Sharing ring before confirming Shared.",
            badgeText = "Share"
        ),
        WhatsNewFeatureItem(
            id = "background_namedrop",
            title = "Background Trigger & Test Mode",
            description = "Persistent BLE proximity service launches Name Drop even when closed, and devices without Bluetooth can explore every flow in Test Mode.",
            badgeText = "Core"
        )
    )

    fun buildInstallSignature(
        versionCode: Long,
        versionName: String,
        lastUpdateTimeMs: Long
    ): String {
        val cleanName = versionName.trim().ifEmpty { "1.0" }
        return "$versionCode|$cleanName|$lastUpdateTimeMs"
    }

    fun shouldShowWhatsNew(savedSignature: String, currentSignature: String): Boolean {
        if (currentSignature.isBlank()) return false
        return savedSignature.trim() != currentSignature.trim()
    }

    fun getCurrentInstallSignature(context: Context): String {
        return runCatching {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
            val name = pkgInfo.versionName ?: BuildConfig.VERSION_NAME
            buildInstallSignature(
                versionCode = code,
                versionName = name,
                lastUpdateTimeMs = pkgInfo.lastUpdateTime
            )
        }.getOrElse {
            buildInstallSignature(
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                versionName = BuildConfig.VERSION_NAME,
                lastUpdateTimeMs = 0L
            )
        }
    }

    fun getVersionDisplayLabel(): String {
        return "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
    }
}
