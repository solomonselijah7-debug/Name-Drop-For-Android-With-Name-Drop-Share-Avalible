package com.example.ai

import android.util.Log
import com.example.data.DebugLogger
import com.example.model.CountryProfile
import com.example.model.DailyUpdateEntry
import com.example.model.PermissionReadinessState
import com.example.model.SelfTestReport
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "DailyUpdateEngine"

class DailyUpdateEngine {

    private val updateSchema = Schema.obj(
        properties = mapOf(
            "featureTip" to Schema.string(description = "A concise 1-2 sentence daily tip or feature highlight tailored for Name Drop BLE users in the user's country."),
            "proximityAdvice" to Schema.string(description = "Short Bluetooth proximity or RSSI readiness advice."),
            "selfTestHeadline" to Schema.string(description = "Short status headline from the daily background readiness check."),
            "selfTestGuidance" to Schema.string(description = "Actionable guidance on whether the user needs to close and reopen Name Drop or adjust background/overlay settings.")
        )
    )

    private val model by lazy {
        val config = generationConfig {
            responseMimeType = "application/json"
            responseSchema = updateSchema
        }
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config
        )
    }

    suspend fun fetchGeminiDailyIntelligence(
        editionEntry: DailyUpdateEntry,
        country: CountryProfile,
        readiness: PermissionReadinessState,
        localSelfTest: SelfTestReport
    ): Result<Pair<String, SelfTestReport>> {
        return try {
            val prompt = """
                You are the in-app Gemini Intelligence engine for Name Drop (a pure Bluetooth Low Energy contact card and photo sharing Android app).
                User Country: ${country.countryName} (${country.countryCode}), Timezone: ${country.zoneId}, Dial Prefix: ${country.dialPrefix}.
                Active 12:00 PM Local Edition Date: ${editionEntry.dateKey} (${editionEntry.title}).
                Is Glow Up Edition (Nov 25 red outer-edge contact card): ${editionEntry.hasRedEdgeGlowUp}.
                Device Model: ${readiness.deviceModelLabel}, Is Huawei/Honor: ${readiness.isHuaweiDevice}, Huawei Mate 20 Lite: ${localSelfTest.isHuaweiMate20Lite}.
                Bluetooth Enabled: ${readiness.isBluetoothEnabled}, BT Perms: ${readiness.hasAllBluetoothPermissions}, Foreground Location: ${readiness.hasForegroundLocation}, Location Services Toggle: ${readiness.isLocationServicesEnabled}, Background Location: ${readiness.hasBackgroundLocation}, Battery Optimization Exempt: ${readiness.isIgnoringBatteryOptimizations}, Display Over Other Apps: ${readiness.canDrawOverlays}.

                Generate:
                1) featureTip: concise localized daily tip referencing their country or today's edition.
                2) proximityAdvice: concise BLE proximity tip.
                3) selfTestHeadline: clear status headline.
                4) selfTestGuidance: if background location, battery exemption, or Huawei background execution is restricted, explicitly instruct the user to close and reopen Name Drop after enabling background permissions (mentioning Huawei Mate 20 Lite / EMUI App Launch if applicable). If Display Over Other Apps is off, note foreground-only mode applies.
            """.trimIndent()

            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Gemini returned empty text.")
            val json = JSONObject(rawJson)

            val featureTip = json.getString("featureTip")
            val selfTestHeadline = json.getString("selfTestHeadline")
            val selfTestGuidance = json.getString("selfTestGuidance")

            DebugLogger.log("Gemini", "Generated localized update for ${country.countryName} (${editionEntry.dateKey})")

            val updatedReport = localSelfTest.copy(
                headline = selfTestHeadline,
                message = selfTestGuidance
            )
            Result.success(featureTip to updatedReport)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini update timed out", e)
            DebugLogger.log("Gemini", "Timeout after 60s", "ERROR")
            Result.failure(Exception("Gemini Intelligence timed out after 60 seconds. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini update failed", e)
            DebugLogger.log("Gemini", "AI Logic error: ${e.localizedMessage}", "ERROR")
            Result.failure(e)
        }
    }
}
