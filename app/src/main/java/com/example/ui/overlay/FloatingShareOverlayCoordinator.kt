package com.example.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.NameDropApp
import com.example.ai.GeminiDiagnosticWatcher
import com.example.ble.BlePermissions
import com.example.ble.BleProximityService
import com.example.ble.BluetoothLeManager
import com.example.data.DebugLogger
import com.example.data.MediaStorageHelper
import com.example.model.SweepTarget
import com.example.model.TransferPhase
import com.example.model.WatcherHealthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OverlayShareUiState(
    val isActive: Boolean = false,
    val isStaging: Boolean = false,
    val title: String = "NameDrop Share",
    val statusMessage: String = "Hold this phone against another phone to share photo",
    val peerLine: String = "Scanning via Bluetooth proximity...",
    val progress: Float = 0f,
    val isArmed: Boolean = false,
    val isReceiveOnlyWaiting: Boolean = false,
    val isReceiveCompleted: Boolean = false,
    val isSharing: Boolean = false,
    val isSharedComplete: Boolean = false,
    val isError: Boolean = false,
    val isVideo: Boolean = false,
    val isMultiple: Boolean = false,
    val itemCount: Int = 1,
    val previewBitmap: Bitmap? = null,
    val fallbackNotice: String? = null,
    val needsBluetoothSetup: Boolean = false,
    val canOpenFullApp: Boolean = false
)

/**
 * Shared coordinator for Gallery / external app sharing via a compact floating overlay box
 * (never pulls the user into the full Name Drop screen unless they tap Open App).
 *
 * Includes generation tokens, frame-0 staging state, Receive Only ("Waiting..."), and
 * double-dismiss guards so tapping NameDrop Share from Gallery ALWAYS produces an immediate,
 * stable popup.
 */
object FloatingShareOverlayCoordinator {

    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private val _uiState = MutableStateFlow(OverlayShareUiState())
    val uiState: StateFlow<OverlayShareUiState> = _uiState.asStateFlow()

    private var stagedPayload: MediaStorageHelper.ReadMediaPayload? = null
    private var senderDisplayName: String = "My Phone"
    private var sessionActive: Boolean = false
    private var transferInFlight: Boolean = false
    private var sessionGeneration: Long = 0L
    private var isDismissing: Boolean = false
    private var lastProgressAtMs: Long = 0L
    private var collectorJob: Job? = null
    private var autoDismissJob: Job? = null
    private var onDismissRequestCallback: (() -> Unit)? = null

    fun resolveShareActionLabel(state: OverlayShareUiState): String = when {
        state.needsBluetoothSetup -> "Enable Bluetooth"
        state.isSharedComplete -> "Shared ✓"
        state.isSharing -> "Sharing..."
        state.isArmed -> "Hold Phones Close..."
        state.isError && state.itemCount == 0 -> "Open App"
        state.isError -> "Retry Share"
        else -> "Share"
    }

    fun resolveReceiveActionLabel(state: OverlayShareUiState): String = when {
        state.isReceiveCompleted -> "Received ✓"
        state.isReceiveOnlyWaiting -> "Waiting..."
        else -> "Receive Only"
    }

    fun shouldAllowBackdropDismiss(state: OverlayShareUiState): Boolean =
        !state.isActive || state.isSharedComplete || state.isReceiveCompleted

    fun armWaitingState(state: OverlayShareUiState): OverlayShareUiState {
        val kindWord = if (state.isVideo) "video" else "photo"
        return state.copy(
            isArmed = true,
            isReceiveOnlyWaiting = false,
            isReceiveCompleted = false,
            isError = false,
            isSharing = false,
            statusMessage = "Hold this phone against another phone to share $kindWord",
            peerLine = "Bluetooth active — hold phones together to send"
        )
    }

    fun armReceiveOnlyWaitingState(state: OverlayShareUiState): OverlayShareUiState {
        return state.copy(
            isArmed = false,
            isReceiveOnlyWaiting = true,
            isReceiveCompleted = false,
            isError = false,
            isSharing = false,
            isSharedComplete = false,
            progress = 0f,
            statusMessage = "Waiting...",
            peerLine = "Receive Only active — hold near another phone to receive"
        )
    }

    fun buildUnreadableShareErrorState(
        title: String = "NameDrop Share",
        message: String,
        peerLine: String
    ): OverlayShareUiState {
        return OverlayShareUiState(
            isActive = true,
            isStaging = false,
            title = title,
            statusMessage = message,
            peerLine = peerLine,
            isError = true,
            itemCount = 0,
            canOpenFullApp = true
        )
    }

    /**
     * Updates state synchronously before IO media reading so frame-0 popup UI is immediately visible.
     */
    fun beginStagingImmediate(
        context: Context,
        uris: List<Uri>,
        hintMime: String?,
        isMultiple: Boolean
    ): Long {
        val appCtx = context.applicationContext
        val myGen = ++sessionGeneration
        isDismissing = false
        transferInFlight = false
        stagedPayload = null
        autoDismissJob?.cancel()
        collectorJob?.cancel()

        val firstUri = uris.firstOrNull()
        val isVideo = hintMime?.startsWith("video/") == true ||
            (firstUri != null && MediaStorageHelper.isVideoUri(appCtx, firstUri, hintMime))
        val kindWord = if (isVideo) "video" else "photo"

        sessionActive = true
        _uiState.value = OverlayShareUiState(
            isActive = true,
            isStaging = true,
            title = if (isMultiple && uris.size > 1) "NameDrop Share (${uris.size})" else "NameDrop Share",
            statusMessage = "Hold this phone against another phone to share $kindWord",
            peerLine = "Preparing $kindWord & Bluetooth proximity...",
            isVideo = isVideo,
            isMultiple = isMultiple,
            itemCount = uris.size
        )
        return myGen
    }

    fun showShareErrorState(
        context: Context,
        title: String = "NameDrop Share",
        message: String,
        peerLine: String
    ) {
        val appCtx = context.applicationContext
        ++sessionGeneration
        isDismissing = false
        transferInFlight = false
        stagedPayload = null
        autoDismissJob?.cancel()
        collectorJob?.cancel()
        sessionActive = true

        _uiState.value = buildUnreadableShareErrorState(
            title = title,
            message = message,
            peerLine = peerLine
        )

        GeminiDiagnosticWatcher.captureFailureSnapshot(
            context = appCtx,
            triggerReason = "Floating gallery share error: $message",
            status = WatcherHealthStatus.ISSUE_DETECTED,
            bleState = NameDropApp.bleManager.state.value,
            transferPhase = TransferPhase.ERROR,
            sweepTarget = SweepTarget.EXTERNAL_SHARE_DROP,
            isTestOnlyMode = false
        )
    }

    suspend fun stageAndStartSession(
        context: Context,
        uris: List<Uri>,
        mimeTypes: List<String>,
        isMultiple: Boolean,
        expectedGeneration: Long? = null
    ): Boolean {
        if (uris.isEmpty()) {
            showShareErrorState(
                context = context,
                message = "No media selected from Gallery",
                peerLine = "Tap Open App or try sharing from Gallery again"
            )
            return false
        }
        val appCtx = context.applicationContext
        val hintMime = mimeTypes.firstOrNull()
        val myGen = expectedGeneration ?: beginStagingImmediate(
            context = appCtx,
            uris = uris,
            hintMime = hintMime,
            isMultiple = isMultiple
        )
        if (sessionGeneration != myGen) return false

        val firstUri = uris.first()
        val isVideo = hintMime?.startsWith("video/") == true ||
            MediaStorageHelper.isVideoUri(appCtx, firstUri, hintMime)
        val kindWord = if (isVideo) "video" else "photo"

        senderDisplayName = runCatching {
            NameDropApp.prefsRepository.contactCard.first().displayName.ifBlank { "My Phone" }
        }.getOrDefault("My Phone")

        val payload = withContext(Dispatchers.IO) {
            MediaStorageHelper.readMediaPayload(
                context = appCtx,
                uri = firstUri,
                hintMime = hintMime
            )
        }
        if (sessionGeneration != myGen) return false

        if (payload == null) {
            DebugLogger.log("ShareOverlay", "Could not read staged URI from external share intent", "ERROR")
            _uiState.value = OverlayShareUiState(
                isActive = true,
                isStaging = false,
                title = if (isMultiple && uris.size > 1) "NameDrop Share (${uris.size})" else "NameDrop Share",
                statusMessage = "Unable to read shared $kindWord from gallery",
                peerLine = "Grant media access or try sharing again",
                isError = true,
                isVideo = isVideo,
                isMultiple = isMultiple,
                itemCount = uris.size,
                canOpenFullApp = true
            )
            GeminiDiagnosticWatcher.captureFailureSnapshot(
                context = appCtx,
                triggerReason = "Failed to decode shared URI ($firstUri, mime=$hintMime)",
                status = WatcherHealthStatus.ISSUE_DETECTED,
                bleState = NameDropApp.bleManager.state.value,
                transferPhase = TransferPhase.ERROR,
                sweepTarget = SweepTarget.EXTERNAL_SHARE_DROP,
                isTestOnlyMode = false
            )
            return false
        }

        stagedPayload = payload

        val previewBmp = withContext(Dispatchers.Default) {
            runCatching {
                if (payload.isVideo) {
                    MediaStorageHelper.extractVideoThumbnailFromBytes(appCtx, payload.bytes)
                } else {
                    BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size)
                }
            }.getOrNull()
        }

        if (sessionGeneration != myGen) return false

        val readiness = BlePermissions.evaluateReadiness(appCtx)
        val mediaKind = if (payload.isVideo) "video" else "photo"
        val needsBt = !readiness.canProceedToContactSetup

        _uiState.value = _uiState.value.copy(
            isActive = true,
            isStaging = false,
            title = if (isMultiple && uris.size > 1) "NameDrop Share (${uris.size})" else "NameDrop Share",
            statusMessage = if (needsBt) {
                "Turn on Bluetooth & Location to share this $mediaKind"
            } else {
                "Hold this phone against another phone to share $mediaKind"
            },
            peerLine = if (needsBt) {
                "Bluetooth or Location permission needed"
            } else {
                "Scanning via Bluetooth proximity..."
            },
            progress = 0f,
            isArmed = false,
            isReceiveOnlyWaiting = false,
            isReceiveCompleted = false,
            isSharing = false,
            isSharedComplete = false,
            isError = false,
            isVideo = payload.isVideo,
            isMultiple = isMultiple,
            itemCount = uris.size,
            previewBitmap = previewBmp,
            needsBluetoothSetup = needsBt,
            canOpenFullApp = needsBt
        )

        if (!needsBt) {
            startBleDiscovery(appCtx, myGen, receiveListening = false)
            val best = NameDropApp.bleManager.getBestPeerSnapshot()
            if (best != null && best.second >= NameDropApp.bleManager.state.value.rssiThreshold) {
                beginTransfer(appCtx, best.first, myGen)
            }
        }
        return true
    }

    fun hasStagedPayload(): Boolean = stagedPayload != null

    fun setFallbackNotice(notice: String?) {
        _uiState.value = _uiState.value.copy(fallbackNotice = notice)
    }

    fun setDismissCallback(callback: (() -> Unit)?) {
        onDismissRequestCallback = callback
    }

    fun triggerShareNow(context: Context) {
        val appCtx = context.applicationContext
        val payload = stagedPayload
        if (payload == null) {
            _uiState.value = _uiState.value.copy(
                isError = true,
                canOpenFullApp = true,
                statusMessage = "Shared media not ready",
                peerLine = "Tap Open App or re-share from Gallery"
            )
            return
        }
        val myGen = sessionGeneration
        val readiness = BlePermissions.evaluateReadiness(appCtx)
        if (!readiness.canProceedToContactSetup) {
            _uiState.value = _uiState.value.copy(
                needsBluetoothSetup = true,
                canOpenFullApp = true,
                statusMessage = "Enable Bluetooth & Location first",
                isError = true
            )
            return
        }

        val ble = NameDropApp.bleManager
        _uiState.value = armWaitingState(_uiState.value)

        val best = ble.getBestPeerSnapshot()
        if (best == null && !ble.hasDiscoveredTarget()) {
            startBleDiscovery(appCtx, myGen, receiveListening = false)
            return
        }
        startBleDiscovery(appCtx, myGen, receiveListening = false)
        beginTransfer(appCtx, best?.first ?: "Nearby Phone", myGen)
    }

    fun triggerReceiveOnlyWaiting(context: Context) {
        val appCtx = context.applicationContext
        val myGen = sessionGeneration
        val readiness = BlePermissions.evaluateReadiness(appCtx)
        if (!readiness.canProceedToContactSetup) {
            _uiState.value = _uiState.value.copy(
                needsBluetoothSetup = true,
                canOpenFullApp = true,
                statusMessage = "Enable Bluetooth & Location first",
                isError = true
            )
            return
        }

        transferInFlight = false
        autoDismissJob?.cancel()
        _uiState.value = armReceiveOnlyWaitingState(_uiState.value)
        startBleDiscovery(appCtx, myGen, receiveListening = true)
    }

    fun dismissSession(context: Context, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != sessionGeneration) return
        if (isDismissing) return
        isDismissing = true
        try {
            sessionGeneration++
            sessionActive = false
            transferInFlight = false
            stagedPayload = null
            autoDismissJob?.cancel()
            autoDismissJob = null
            collectorJob?.cancel()
            collectorJob = null
            FloatingShareOverlayWindow.dismiss(context.applicationContext)
            _uiState.value = OverlayShareUiState(isActive = false)
            val cb = onDismissRequestCallback
            onDismissRequestCallback = null
            cb?.invoke()
        } finally {
            isDismissing = false
        }
    }

    private fun startBleDiscovery(appCtx: Context, myGen: Long, receiveListening: Boolean) {
        val ble = NameDropApp.bleManager
        ble.clearCooldownForImmediateShare()
        ble.setModes(nameDropEnabled = true, shareEnabled = true, receiveListening = receiveListening)
        ble.ensureLowLatencyScan()
        BleProximityService.startService(appCtx, lowPower = false)

        collectorJob?.cancel()
        collectorJob = scope.launch {
            launch {
                ble.state.collect { st ->
                    val current = _uiState.value
                    if (!sessionActive || sessionGeneration != myGen || transferInFlight || current.isSharedComplete || current.isReceiveCompleted) {
                        return@collect
                    }
                    val peerLine = st.nearestDeviceName?.let { name ->
                        val rssi = st.smoothedRssi ?: st.nearestRssi ?: st.rssiThreshold
                        if (current.isReceiveOnlyWaiting) {
                            "Nearby: $name ($rssi dBm) • Waiting for incoming drop..."
                        } else {
                            "Nearby: $name ($rssi dBm)"
                        }
                    } ?: when {
                        current.isReceiveOnlyWaiting -> "Receive Only active — hold near another phone to receive"
                        current.isArmed -> "Bluetooth active — hold phones together to send"
                        else -> "Scanning via Bluetooth proximity..."
                    }
                    _uiState.value = _uiState.value.copy(peerLine = peerLine)
                }
            }
            launch {
                ble.events.collect { event ->
                    if (!sessionActive || sessionGeneration != myGen) return@collect
                    when (event) {
                        is BluetoothLeManager.BleEvent.ProximityMatched -> {
                            val current = _uiState.value
                            if (!transferInFlight && !current.isSharedComplete && !current.isReceiveOnlyWaiting && !current.isReceiveCompleted) {
                                beginTransfer(appCtx, event.deviceName, myGen)
                            }
                        }

                        is BluetoothLeManager.BleEvent.ContactReceived -> {
                            if (_uiState.value.isReceiveOnlyWaiting) {
                                NameDropApp.soundPlayer.playCompletionPing()
                                _uiState.value = _uiState.value.copy(
                                    isReceiveOnlyWaiting = false,
                                    isReceiveCompleted = true,
                                    canOpenFullApp = true,
                                    progress = 1f,
                                    statusMessage = "Received ${event.contact.displayName}",
                                    peerLine = "Contact card saved • Tap Open App to view"
                                )
                            }
                        }

                        is BluetoothLeManager.BleEvent.MutualShareHandshakeReceived -> {
                            if (_uiState.value.isReceiveOnlyWaiting) {
                                NameDropApp.soundPlayer.playCompletionPing()
                                _uiState.value = _uiState.value.copy(
                                    isReceiveOnlyWaiting = false,
                                    isReceiveCompleted = true,
                                    canOpenFullApp = true,
                                    progress = 1f,
                                    statusMessage = "Swapped with ${event.contact.displayName}",
                                    peerLine = "Contact card saved • Tap Open App to view"
                                )
                            }
                        }

                        is BluetoothLeManager.BleEvent.PhotoOfferReceived -> {
                            if (_uiState.value.isReceiveOnlyWaiting) {
                                NameDropApp.soundPlayer.playCompletionPing()
                                val kind = if (event.isVideo) "video" else "photo"
                                _uiState.value = _uiState.value.copy(
                                    isReceiveOnlyWaiting = false,
                                    isReceiveCompleted = true,
                                    canOpenFullApp = true,
                                    progress = 1f,
                                    statusMessage = "Incoming $kind from ${event.senderName}",
                                    peerLine = "Tap Open App to Accept & save"
                                )
                            }
                        }

                        is BluetoothLeManager.BleEvent.TransferProgress -> {
                            if (transferInFlight) {
                                lastProgressAtMs = System.currentTimeMillis()
                                _uiState.value = _uiState.value.copy(
                                    isSharing = true,
                                    isArmed = true,
                                    progress = event.progress.coerceIn(0.08f, 0.96f),
                                    statusMessage = "Sharing"
                                )
                            }
                        }

                        is BluetoothLeManager.BleEvent.TransferCompleted -> {
                            if (transferInFlight) {
                                transferInFlight = false
                                finishTransfer(appCtx, event.peerName, event.success, myGen)
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    private fun beginTransfer(appCtx: Context, peerName: String, myGen: Long) {
        val payload = stagedPayload ?: return
        if (!sessionActive || sessionGeneration != myGen || transferInFlight) return
        transferInFlight = true
        lastProgressAtMs = System.currentTimeMillis()

        _uiState.value = _uiState.value.copy(
            isArmed = true,
            isReceiveOnlyWaiting = false,
            isSharing = true,
            isSharedComplete = false,
            isError = false,
            progress = 0.14f,
            statusMessage = "Sharing",
            peerLine = "Sending to $peerName..."
        )

        scope.launch {
            val (sentCount, targetLabel) = NameDropApp.bleManager.sendMediaToNearbyPeers(
                senderName = senderDisplayName,
                fileName = payload.fileName,
                mediaBytes = payload.bytes,
                mimeType = payload.mimeType,
                isVideo = payload.isVideo,
                isGroupShare = _uiState.value.isMultiple
            )
            if (!sessionActive || sessionGeneration != myGen) {
                transferInFlight = false
                return@launch
            }
            if (sentCount == 0) {
                transferInFlight = false
                val kindWord = if (payload.isVideo) "video" else "photo"
                _uiState.value = _uiState.value.copy(
                    isArmed = true,
                    isSharing = false,
                    progress = 0f,
                    isError = false,
                    statusMessage = "Hold this phone against another phone to share $kindWord",
                    peerLine = "Waiting for nearby phone via Bluetooth..."
                )
                NameDropApp.bleManager.ensureLowLatencyScan()
            } else {
                GeminiDiagnosticWatcher.startHandshakeWatchdog(
                    context = appCtx,
                    label = payload.fileName,
                    bleStateProvider = { NameDropApp.bleManager.state.value },
                    transferPhaseProvider = { TransferPhase.TRANSFERRING },
                    sweepTargetProvider = { SweepTarget.EXTERNAL_SHARE_DROP },
                    isTestOnlyProvider = { false }
                )
                val maxWaitDeadline = System.currentTimeMillis() + 6500L
                while (transferInFlight && sessionActive && sessionGeneration == myGen) {
                    delay(450L)
                    val now = System.currentTimeMillis()
                    if (now >= maxWaitDeadline) break
                    if (now - lastProgressAtMs >= 2400L && _uiState.value.progress <= 0.16f) {
                        // Fast non-progress peer completed handshake silently
                        break
                    }
                }
                if (transferInFlight && sessionActive && sessionGeneration == myGen) {
                    transferInFlight = false
                    finishTransfer(appCtx, targetLabel ?: peerName, true, myGen)
                }
            }
        }
    }

    private fun finishTransfer(appCtx: Context, peerName: String, success: Boolean, myGen: Long) {
        if (sessionGeneration != myGen) return
        GeminiDiagnosticWatcher.cancelHandshakeWatchdog()
        if (success) {
            GeminiDiagnosticWatcher.onHandshakeCompleted(true, null)
            NameDropApp.soundPlayer.playCompletionPing()
            _uiState.value = _uiState.value.copy(
                isArmed = false,
                isSharing = false,
                isSharedComplete = true,
                isError = false,
                progress = 1f,
                statusMessage = "Shared",
                peerLine = "Sent to $peerName"
            )
            autoDismissJob?.cancel()
            autoDismissJob = scope.launch {
                delay(2200L)
                dismissSession(appCtx, expectedGeneration = myGen)
            }
        } else {
            GeminiDiagnosticWatcher.captureFailureSnapshot(
                context = appCtx,
                triggerReason = "Floating gallery share interrupted with $peerName",
                status = WatcherHealthStatus.ISSUE_DETECTED,
                bleState = NameDropApp.bleManager.state.value,
                transferPhase = TransferPhase.ERROR,
                sweepTarget = SweepTarget.EXTERNAL_SHARE_DROP,
                isTestOnlyMode = false
            )
            _uiState.value = _uiState.value.copy(
                isArmed = false,
                isSharing = false,
                isSharedComplete = false,
                isError = true,
                canOpenFullApp = true,
                progress = 0f,
                statusMessage = "Share interrupted",
                peerLine = "Hold phones closer and tap Share to retry"
            )
        }
    }
}
