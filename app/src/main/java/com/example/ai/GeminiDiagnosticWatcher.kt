package com.example.ai

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import com.example.ble.BlePermissions
import com.example.ble.DeviceDiagnostics
import com.example.data.DebugLogger
import com.example.model.BleProximityState
import com.example.model.DiagnosticSnapshot
import com.example.model.GeminiDiagnosisResult
import com.example.model.PermissionReadinessState
import com.example.model.SweepTarget
import com.example.model.TransferPhase
import com.example.model.WatcherHealthStatus
import com.example.model.WatcherTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real-time Gemini Intelligence Watcher & Automatic "AI Fix Prompt" Generator.
 *
 * Monitors:
 * - Foreground Service (`connectedDevice|location`) uptime & failures
 * - BLE scan callbacks (`onScanResult`, `onScanFailed`) and advertising health
 * - Background/outside-app overlay triggers (`SYSTEM_ALERT_WINDOW`)
 * - Active card cooldown lock & GATT transfer/handshake stalls
 * - 4-second silent-failure watchdog when proximity checks or handshakes stall
 *
 * All snapshots and AI fix prompts sanitize PII and credentials before export.
 */
object GeminiDiagnosticWatcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _telemetry = MutableStateFlow(WatcherTelemetry())
    val telemetry: StateFlow<WatcherTelemetry> = _telemetry.asStateFlow()

    private val _latestSnapshot = MutableStateFlow<DiagnosticSnapshot?>(null)
    val latestSnapshot: StateFlow<DiagnosticSnapshot?> = _latestSnapshot.asStateFlow()

    private val _healthStatus = MutableStateFlow(WatcherHealthStatus.WATCHING)
    val healthStatus: StateFlow<WatcherHealthStatus> = _healthStatus.asStateFlow()

    private var watchdogJob: Job? = null
    private var handshakeWatchdogJob: Job? = null
    private var latestContext: Context? = null

    fun attachContext(context: Context) {
        if (latestContext == null) {
            latestContext = context.applicationContext
        }
    }

    fun onServiceStarted(lowPowerMode: Boolean) {
        _telemetry.value = _telemetry.value.copy(
            foregroundServiceRunning = true,
            fgsLowPowerMode = lowPowerMode,
            fgsLastError = null
        )
    }

    fun onServiceStopped() {
        _telemetry.value = _telemetry.value.copy(
            foregroundServiceRunning = false,
            fgsLowPowerMode = null
        )
    }

    fun onServiceError(context: Context, message: String, bleState: BleProximityState? = null) {
        attachContext(context)
        _telemetry.value = _telemetry.value.copy(
            foregroundServiceRunning = false,
            fgsLastError = message
        )
        captureFailureSnapshot(
            context = context,
            triggerReason = "Foreground Service Failure: $message",
            status = WatcherHealthStatus.ISSUE_DETECTED,
            bleState = bleState ?: BleProximityState(),
            transferPhase = TransferPhase.IDLE,
            sweepTarget = SweepTarget.NONE,
            isTestOnlyMode = false
        )
    }

    fun onScanStarted() {
        _telemetry.value = _telemetry.value.copy(
            lastScanFailedCode = null,
            lastScanError = null
        )
    }

    fun onScanResultSeen() {
        _telemetry.value = _telemetry.value.copy(
            scanCallbackCount = _telemetry.value.scanCallbackCount + 1L,
            lastScanResultAtMs = System.currentTimeMillis(),
            lastScanFailedCode = null,
            lastScanError = null
        )
    }

    fun onScanFailed(
        context: Context,
        errorCode: Int,
        reason: String,
        bleState: BleProximityState
    ) {
        attachContext(context)
        _telemetry.value = _telemetry.value.copy(
            lastScanFailedCode = errorCode,
            lastScanError = reason
        )
        captureFailureSnapshot(
            context = context,
            triggerReason = "BLE Scan Failed (code=$errorCode): $reason",
            status = WatcherHealthStatus.ISSUE_DETECTED,
            bleState = bleState,
            transferPhase = TransferPhase.IDLE,
            sweepTarget = SweepTarget.NONE,
            isTestOnlyMode = false
        )
    }

    fun onAdvertisingFailed(context: Context, errorCode: Int, bleState: BleProximityState) {
        attachContext(context)
        _telemetry.value = _telemetry.value.copy(
            lastScanError = "BLE advertising failed (code=$errorCode)"
        )
        captureFailureSnapshot(
            context = context,
            triggerReason = "BLE Advertising Failed (code=$errorCode)",
            status = WatcherHealthStatus.ISSUE_DETECTED,
            bleState = bleState,
            transferPhase = TransferPhase.IDLE,
            sweepTarget = SweepTarget.NONE,
            isTestOnlyMode = false
        )
    }

    fun onProximityMatched() {
        watchdogJob?.cancel()
        _telemetry.value = _telemetry.value.copy(
            lastProximityMatchAtMs = System.currentTimeMillis(),
            watchdogRemainingSec = null
        )
        _healthStatus.value = WatcherHealthStatus.HEALTHY
    }

    fun onProximityLockChanged(locked: Boolean) {
        _telemetry.value = _telemetry.value.copy(isCardLockActive = locked)
    }

    fun onOverlayLaunchAttempt() {
        _telemetry.value = _telemetry.value.copy(
            lastOverlayLaunchAtMs = System.currentTimeMillis(),
            lastOverlayError = null
        )
    }

    fun onShareIntentReceived(
        context: Context,
        action: String?,
        mimeType: String?,
        uriCount: Int,
        clipDataCount: Int,
        bleState: BleProximityState = BleProximityState()
    ) {
        attachContext(context)
        val readiness = BlePermissions.evaluateReadiness(context)
        val msg = "ACTION_SEND trigger: action=$action, mime=$mimeType, uris=$uriCount, clipData=$clipDataCount, " +
            "overlayPerm=${readiness.canDrawOverlays}, btOn=${readiness.isBluetoothEnabled}, " +
            "btPerms=${readiness.hasAllBluetoothPermissions}, locOn=${readiness.isLocationServicesEnabled}, " +
            "scanning=${bleState.isScanning}, adv=${bleState.isAdvertising}"
        DebugLogger.log("ShareIntent", msg, if (uriCount > 0) "INFO" else "ERROR")
        _telemetry.value = _telemetry.value.copy(
            lastOverlayLaunchAtMs = System.currentTimeMillis(),
            lastOverlayResult = msg,
            lastOverlayError = if (uriCount == 0) "Share intent contained 0 readable media URIs" else null
        )
        if (uriCount == 0) {
            captureFailureSnapshot(
                context = context,
                triggerReason = "Gallery Share Intent had 0 media URIs (action=$action, mime=$mimeType, clipData=$clipDataCount)",
                status = WatcherHealthStatus.ISSUE_DETECTED,
                bleState = bleState,
                transferPhase = TransferPhase.ERROR,
                sweepTarget = SweepTarget.EXTERNAL_SHARE_DROP,
                isTestOnlyMode = false
            )
        }
    }

    fun onOverlayAttachVerified(
        context: Context,
        launchMode: String,
        attached: Boolean,
        hasVisibleBounds: Boolean,
        detail: String,
        bleState: BleProximityState = BleProximityState()
    ) {
        val ok = attached && hasVisibleBounds
        onOverlayLaunchResult(
            context = context,
            launchMode = launchMode,
            success = ok,
            detail = "attached=$attached, visibleBounds=$hasVisibleBounds — $detail",
            bleState = bleState
        )
    }

    fun onOverlayLaunchResult(
        context: Context,
        launchMode: String,
        success: Boolean,
        detail: String,
        bleState: BleProximityState = BleProximityState()
    ) {
        attachContext(context)
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})"
        val canDraw = BlePermissions.canDrawOverlays(context)
        val resultSummary = "$launchMode on $device [canDrawOverlays=$canDraw, ok=$success]: $detail"
        _telemetry.value = _telemetry.value.copy(
            lastOverlayLaunchAtMs = System.currentTimeMillis(),
            lastOverlayResult = resultSummary,
            lastOverlayError = if (success) null else detail
        )
        DebugLogger.log("Overlay", resultSummary, if (success) "INFO" else "ERROR")
        if (!success) {
            captureFailureSnapshot(
                context = context,
                triggerReason = "Overlay Launch Fallback/Failure ($launchMode): $detail",
                status = WatcherHealthStatus.ISSUE_DETECTED,
                bleState = bleState,
                transferPhase = TransferPhase.IDLE,
                sweepTarget = SweepTarget.NONE,
                isTestOnlyMode = false
            )
        }
    }

    fun onOverlayFailure(context: Context, error: String) {
        attachContext(context)
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})"
        val summary = "Overlay failure on $device [canDrawOverlays=${BlePermissions.canDrawOverlays(context)}]: $error"
        _telemetry.value = _telemetry.value.copy(
            lastOverlayError = error,
            lastOverlayResult = summary
        )
        captureFailureSnapshot(
            context = context,
            triggerReason = "Background Overlay Trigger Blocked: $error",
            status = WatcherHealthStatus.ISSUE_DETECTED,
            bleState = BleProximityState(),
            transferPhase = TransferPhase.IDLE,
            sweepTarget = SweepTarget.NONE,
            isTestOnlyMode = false
        )
    }

    fun onHandshakeStarted() {
        _telemetry.value = _telemetry.value.copy(
            lastHandshakeStartedAtMs = System.currentTimeMillis(),
            lastTransferError = null
        )
    }

    fun onTargetDiscoveryEvaluated(
        targetName: String?,
        address: String?,
        rssi: Int?,
        threshold: Int,
        isNameDropPeer: Boolean,
        qualified: Boolean
    ) {
        val summary = "BLE target check: peer=${targetName ?: "none"} (${address ?: "no-addr"}), " +
            "rssi=${rssi ?: "none"} vs threshold=$threshold, nameDropPeer=$isNameDropPeer, qualified=$qualified"
        DebugLogger.log("Discovery", summary, if (qualified) "INFO" else "WARN")
    }

    fun onTransferHandshakeEvent(
        context: Context,
        stage: String,
        transferId: String,
        targetName: String,
        detail: String,
        bleState: BleProximityState,
        captureSnapshot: Boolean = false,
        status: WatcherHealthStatus = WatcherHealthStatus.ISSUE_DETECTED
    ) {
        attachContext(context)
        val msg = "Handshake[$stage] id=$transferId target=$targetName: $detail"
        DebugLogger.log("Handshake", msg, if (captureSnapshot) "ERROR" else "INFO")
        if (captureSnapshot) {
            _telemetry.value = _telemetry.value.copy(lastTransferError = msg)
            captureFailureSnapshot(
                context = context,
                triggerReason = msg,
                status = status,
                bleState = bleState,
                transferPhase = TransferPhase.ERROR,
                sweepTarget = SweepTarget.NONE,
                isTestOnlyMode = false
            )
        }
    }

    fun onHandshakeCompleted(success: Boolean, errorMessage: String? = null) {
        handshakeWatchdogJob?.cancel()
        _telemetry.value = _telemetry.value.copy(
            lastHandshakeCompletedAtMs = System.currentTimeMillis(),
            lastTransferError = if (success) null else errorMessage ?: "GATT handshake failed"
        )
        if (success) {
            _healthStatus.value = WatcherHealthStatus.HEALTHY
        }
    }

    fun startHandshakeWatchdog(
        context: Context,
        label: String,
        bleStateProvider: () -> BleProximityState,
        transferPhaseProvider: () -> TransferPhase,
        sweepTargetProvider: () -> SweepTarget,
        isTestOnlyProvider: () -> Boolean,
        timeoutMs: Long = 5000L
    ) {
        attachContext(context)
        handshakeWatchdogJob?.cancel()
        onHandshakeStarted()
        val startedAt = System.currentTimeMillis()
        handshakeWatchdogJob = scope.launch {
            delay(timeoutMs)
            val current = _telemetry.value
            if (current.lastHandshakeCompletedAtMs < startedAt) {
                val err = "Transfer/handshake stalled after ${timeoutMs / 1000}s ($label)"
                _telemetry.value = current.copy(lastTransferError = err)
                DebugLogger.log("Watcher", err, "ERROR")
                captureFailureSnapshot(
                    context = context,
                    triggerReason = err,
                    status = WatcherHealthStatus.STALL_DETECTED,
                    bleState = bleStateProvider(),
                    transferPhase = transferPhaseProvider(),
                    sweepTarget = sweepTargetProvider(),
                    isTestOnlyMode = isTestOnlyProvider()
                )
            }
        }
    }

    fun cancelHandshakeWatchdog() {
        handshakeWatchdogJob?.cancel()
    }

    /**
     * Runs a 4-second silent-failure watchdog for manual "Test Proximity Now" or
     * hold-to-share/outside-app detection checks. If no proximity match or scan trigger completes
     * before timeout, automatically captures a sanitized diagnostic snapshot and AI Fix Prompt.
     */
    fun startProximityCheckWatchdog(
        context: Context,
        triggerLabel: String,
        bleStateProvider: () -> BleProximityState,
        transferPhaseProvider: () -> TransferPhase,
        sweepTargetProvider: () -> SweepTarget,
        isTestOnlyProvider: () -> Boolean,
        timeoutSeconds: Int = 4
    ) {
        attachContext(context)
        watchdogJob?.cancel()
        val startedAt = System.currentTimeMillis()
        _healthStatus.value = WatcherHealthStatus.WATCHING
        DebugLogger.log("Watcher", "Started ${timeoutSeconds}s proximity stall watchdog ($triggerLabel)")

        watchdogJob = scope.launch {
            for (sec in timeoutSeconds downTo 1) {
                _telemetry.value = _telemetry.value.copy(watchdogRemainingSec = sec)
                delay(1000L)
                if (_telemetry.value.lastProximityMatchAtMs >= startedAt) {
                    _telemetry.value = _telemetry.value.copy(watchdogRemainingSec = null)
                    _healthStatus.value = WatcherHealthStatus.HEALTHY
                    return@launch
                }
            }
            _telemetry.value = _telemetry.value.copy(watchdogRemainingSec = null)
            val reason = "No Name Drop trigger occurred within ${timeoutSeconds}s ($triggerLabel)"
            DebugLogger.log("Watcher", reason, "ERROR")
            captureFailureSnapshot(
                context = context,
                triggerReason = reason,
                status = WatcherHealthStatus.STALL_DETECTED,
                bleState = bleStateProvider(),
                transferPhase = transferPhaseProvider(),
                sweepTarget = sweepTargetProvider(),
                isTestOnlyMode = isTestOnlyProvider()
            )
        }
    }

    fun captureFailureSnapshot(
        context: Context,
        triggerReason: String,
        status: WatcherHealthStatus,
        bleState: BleProximityState,
        transferPhase: TransferPhase,
        sweepTarget: SweepTarget,
        isTestOnlyMode: Boolean
    ): DiagnosticSnapshot {
        val readiness = BlePermissions.evaluateReadiness(context)
        val telemetryNow = _telemetry.value
        val causes = diagnoseSuspectedRootCauses(
            readiness = readiness,
            bleState = bleState,
            telemetry = telemetryNow,
            transferPhase = transferPhase,
            sweepTarget = sweepTarget,
            isTestOnlyMode = isTestOnlyMode
        )
        val sanitizedLogs = buildSanitizedLogExcerpt()
        val prompt = buildAiFixPrompt(
            triggerReason = triggerReason,
            status = status,
            readiness = readiness,
            bleState = bleState,
            telemetry = telemetryNow,
            transferPhase = transferPhase,
            sweepTarget = sweepTarget,
            isTestOnlyMode = isTestOnlyMode,
            suspectedCauses = causes,
            sanitizedLogs = sanitizedLogs
        )
        val snapshot = DiagnosticSnapshot(
            capturedAt = timeFormat.format(Date()),
            triggerReason = triggerReason,
            healthStatus = status,
            summaryHeadline = causes.firstOrNull() ?: triggerReason,
            suspectedRootCauses = causes,
            aiFixPrompt = prompt,
            sanitizedLogExcerpt = sanitizedLogs
        )
        _healthStatus.value = status
        _latestSnapshot.value = snapshot
        return snapshot
    }

    fun diagnoseSuspectedRootCauses(
        readiness: PermissionReadinessState,
        bleState: BleProximityState,
        telemetry: WatcherTelemetry,
        transferPhase: TransferPhase,
        sweepTarget: SweepTarget,
        isTestOnlyMode: Boolean
    ): List<String> {
        val causes = mutableListOf<String>()
        if (isTestOnlyMode) {
            causes.add("App is in Test-Only Device Mode — real Bluetooth proximity scanning is disabled.")
        }
        if (!readiness.isBluetoothSupported) {
            causes.add("Device reports no Bluetooth LE radio support.")
        } else if (!readiness.isBluetoothEnabled) {
            causes.add("Bluetooth radio is turned OFF.")
        }
        if (!readiness.hasAllBluetoothPermissions) {
            causes.add("Missing runtime Bluetooth permissions (SCAN / CONNECT / ADVERTISE).")
        }
        if (!readiness.hasForegroundLocation) {
            causes.add("Foreground Location permission missing (required by Android BLE scanner).")
        }
        if (!readiness.isLocationServicesEnabled) {
            causes.add("System Location Services toggle is OFF — Android silently drops BLE scan callbacks.")
        }
        if (!readiness.hasBackgroundLocation) {
            causes.add("Background Location is not set to 'Allow all the time' — outside-app detection may stall.")
        }
        if (!readiness.canDrawOverlays) {
            causes.add("Display Over Other Apps (SYSTEM_ALERT_WINDOW) is disabled — background/closed-app pop-up animation cannot launch on top.")
        }
        if (!readiness.isIgnoringBatteryOptimizations) {
            causes.add("Battery optimization is still active — OS/OEM (${Build.MANUFACTURER}) may throttle or kill background BLE service.")
        }
        if (!telemetry.foregroundServiceRunning && bleState.isNameDropEnabled && !isTestOnlyMode) {
            causes.add("BleProximityService foreground service is NOT running even though Name Drop is enabled.")
        }
        telemetry.fgsLastError?.let { causes.add("Foreground service error: $it") }
        telemetry.lastScanFailedCode?.let { code ->
            causes.add("BLE ScanCallback.onScanFailed(errorCode=$code) — scanner throttled or rejected by OS.")
        }
        bleState.lastScanError?.let { causes.add("Last BLE scan error: $it") }
        telemetry.lastOverlayError?.let { causes.add("Overlay launch error: $it") }
        telemetry.lastTransferError?.let { causes.add("Transfer/handshake error: $it") }
        if (telemetry.isCardLockActive || sweepTarget != SweepTarget.NONE) {
            causes.add("Active Card Cooldown Lock is engaged (an open card or sweep overlay is blocking new proximity triggers).")
        }
        if (bleState.isNameDropEnabled && !bleState.isScanning && !isTestOnlyMode) {
            causes.add("BLE scanner is currently inactive despite Name Drop being turned on.")
        }
        if (bleState.isNameDropEnabled && !bleState.isAdvertising && !isTestOnlyMode) {
            causes.add("BLE advertiser is inactive — peer phone may not discover this device.")
        }
        if (causes.isEmpty()) {
            val rssiDesc = bleState.smoothedRssi?.let { "$it dBm" } ?: "none"
            causes.add(
                "No peer Name Drop beacon exceeded threshold (${bleState.rssiThreshold} dBm; strongest seen=$rssiDesc, transferPhase=$transferPhase). Hold phones closer or check peer Name Drop toggle."
            )
        }
        return causes
    }

    fun buildAiFixPrompt(
        triggerReason: String,
        status: WatcherHealthStatus,
        readiness: PermissionReadinessState,
        bleState: BleProximityState,
        telemetry: WatcherTelemetry,
        transferPhase: TransferPhase,
        sweepTarget: SweepTarget,
        isTestOnlyMode: Boolean,
        suspectedCauses: List<String>,
        sanitizedLogs: String
    ): String {
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT}, ${Build.HARDWARE})"
        val causesBlock = suspectedCauses.mapIndexed { idx, c -> "${idx + 1}. $c" }.joinToString("\n")
        return """
            Fix this issue in the Android "Name Drop" Bluetooth LE app (Kotlin / Jetpack Compose, no NFC):

            ## Failure Summary
            - Status: ${status.label}
            - Trigger: $triggerReason
            - App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            - Device / OS: $deviceInfo (Huawei/Honor OEM=${DeviceDiagnostics.isHuaweiOrHonor()})

            ## Sanitized Runtime & Permission State
            - Test-Only Mode: $isTestOnlyMode
            - NameDrop Enabled: ${bleState.isNameDropEnabled}, Share Enabled: ${bleState.isShareEnabled}, Receive Listening: ${bleState.isReceiveListening}
            - Foreground Service Running: ${telemetry.foregroundServiceRunning} (lowPower=${telemetry.fgsLowPowerMode}, fgsErr=${telemetry.fgsLastError ?: "none"})
            - BLE Scanning: ${bleState.isScanning}, Advertising: ${bleState.isAdvertising}, Scan Callbacks Seen: ${telemetry.scanCallbackCount}
            - RSSI Threshold: ${bleState.rssiThreshold} dBm, Nearest RSSI: ${bleState.smoothedRssi ?: bleState.nearestRssi ?: "none"}, Nearby Count: ${bleState.nearbySignalCount}
            - Active Card Lock: ${telemetry.isCardLockActive}, Sweep Target: $sweepTarget, Transfer Phase: $transferPhase
            - Overlay Result: ${telemetry.lastOverlayResult ?: "none"} (lastErr=${telemetry.lastOverlayError ?: "none"})
            - Permissions -> BT_ON=${readiness.isBluetoothEnabled}, BT_PERMS=${readiness.hasAllBluetoothPermissions}, FG_LOC=${readiness.hasForegroundLocation}, LOC_TOGGLE=${readiness.isLocationServicesEnabled}, BG_LOC=${readiness.hasBackgroundLocation}, OVERLAY=${readiness.canDrawOverlays}, CONTACTS=${readiness.hasContactsPermissions}, BATTERY_EXEMPT=${readiness.isIgnoringBatteryOptimizations}, NOTIF=${readiness.hasNotificationPermission}

            ## Suspected Root Causes
            $causesBlock

            ## Recent Sanitized Event Logs
            ```
            ${sanitizedLogs.ifBlank { "(no recent events recorded)" }}
            ```

            Please identify the exact root cause from the diagnostics above and fix the Android code so background proximity detection, overlay triggering, and BLE handshakes succeed reliably.
        """.trimIndent()
    }

    fun buildSanitizedLogExcerpt(maxLines: Int = 25): String {
        return DebugLogger.logs.value
            .takeLast(maxLines)
            .joinToString("\n") { entry ->
                "[${entry.timestamp}] [${entry.level}] ${entry.tag}: ${sanitizeText(entry.message)}"
            }
    }

    fun sanitizeText(raw: String): String {
        return raw
            .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "[REDACTED_API_KEY]")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[REDACTED_EMAIL]")
            .replace(Regex("\\+?\\d[\\d\\s\\-()]{6,}\\d"), "[REDACTED_PHONE]")
    }

    /**
     * Optional user-supplied Gemini API key diagnosis.
     * Sends only the sanitized diagnostic snapshot (never the API key inside logs/prompts)
     * using x-goog-api-key header over HTTPS.
     */
    suspend fun analyzeWithUserApiKey(
        apiKey: String,
        snapshot: DiagnosticSnapshot
    ): Result<GeminiDiagnosisResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a Gemini API key in Settings first."))
        }
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            )
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("x-goog-api-key", cleanKey)
            }

            val instruction = """
                You are an expert Android BLE & background service diagnostician.
                Analyze the following sanitized Name Drop diagnostic snapshot and return ONLY valid JSON with two string fields:
                1. "plainLanguageDiagnosis": 2-4 concise plain-language sentences explaining what went wrong and what the user or developer should fix.
                2. "updatedFixPrompt": a refined, actionable AI coding prompt incorporating the specific root cause found in the telemetry/logs.

                Diagnostic Snapshot:
                ${snapshot.aiFixPrompt}
            """.trimIndent()

            val bodyJson = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", instruction))
                        )
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().put("responseMimeType", "application/json")
                )
            }

            connection.outputStream.use { os ->
                os.write(bodyJson.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val rawResponse = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val errMsg = runCatching {
                    JSONObject(rawResponse).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Gemini API returned HTTP $code"
                return@withContext Result.failure(IllegalStateException(errMsg))
            }

            val candidateText = JSONObject(rawResponse)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()

            if (candidateText.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Gemini API returned an empty response."))
            }

            val parsed = runCatching { JSONObject(candidateText) }.getOrNull()
            val diagnosis = parsed?.optString("plainLanguageDiagnosis")
                ?.takeIf { it.isNotBlank() }
                ?: candidateText
            val updatedPrompt = parsed?.optString("updatedFixPrompt")
                ?.takeIf { it.isNotBlank() }
                ?: "${snapshot.aiFixPrompt}\n\n## Gemini Analysis Note\n$diagnosis"

            Result.success(
                GeminiDiagnosisResult(
                    isAnalyzing = false,
                    plainLanguageDiagnosis = diagnosis,
                    updatedFixPrompt = updatedPrompt,
                    errorMessage = null,
                    analyzedAt = timeFormat.format(Date())
                )
            )
        } catch (e: Exception) {
            DebugLogger.log("Watcher", "Optional Gemini API call failed: ${sanitizeText(e.message ?: "unknown")}", "ERROR")
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
