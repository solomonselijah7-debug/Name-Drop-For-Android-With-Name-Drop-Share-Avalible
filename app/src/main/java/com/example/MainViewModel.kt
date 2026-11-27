package com.example

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiDiagnosticWatcher
import com.example.ai.YearlyUpdateSchedule
import com.example.audio.ShareSoundPlayer
import com.example.ble.BlePermissions
import com.example.ble.BleProximityService
import com.example.ble.BluetoothLeManager
import com.example.ble.DeviceDiagnostics
import com.example.ble.IncomingAlertManager
import com.example.data.ContactBookRepository
import com.example.data.CountryLocationVerifier
import com.example.data.DebugLogger
import com.example.data.MediaStorageHelper
import com.example.data.WhatsNewCatalog
import com.example.model.BleProximityState
import com.example.model.CardSwapState
import com.example.model.ContactCard
import com.example.model.CountryProfile
import com.example.model.DailyUpdateEntry
import com.example.model.DiagnosticSnapshot
import com.example.model.DropBadgePhase
import com.example.model.DropBadgeState
import com.example.model.ExternalShareSession
import com.example.model.GeminiDiagnosisResult
import com.example.model.GlowPreviewMode
import com.example.model.GlowThemeStyle
import com.example.model.IncomingContactOffer
import com.example.model.IncomingPhotoOffer
import com.example.model.NavTab
import com.example.model.OnboardingStage
import com.example.model.PermissionReadinessState
import com.example.model.PhotoTransferState
import com.example.model.SavedContactEntry
import com.example.model.SelfTestReport
import com.example.model.SweepTarget
import com.example.model.TransferPhase
import com.example.model.WatcherHealthStatus
import com.example.model.WatcherTelemetry
import com.example.ui.overlay.FloatingShareOverlayCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val prefs = NameDropApp.prefsRepository
    private val ble = NameDropApp.bleManager
    private val soundPlayer = NameDropApp.soundPlayer
    private val aiEngine = NameDropApp.dailyUpdateEngine

    val isAndroid8Unsupported: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.P

    private val _selectedTab = MutableStateFlow(NavTab.NAME_DROP)
    val selectedTab: StateFlow<NavTab> = _selectedTab.asStateFlow()

    private val _onboardingStage = MutableStateFlow(OnboardingStage.PERMISSIONS_GATE)
    val onboardingStage: StateFlow<OnboardingStage> = _onboardingStage.asStateFlow()

    private val _isTestOnlyMode = MutableStateFlow(false)
    val isTestOnlyMode: StateFlow<Boolean> = _isTestOnlyMode.asStateFlow()

    private val _showBluetoothPrompt = MutableStateFlow(false)
    val showBluetoothPrompt: StateFlow<Boolean> = _showBluetoothPrompt.asStateFlow()

    private val _readiness = MutableStateFlow(BlePermissions.evaluateReadiness(app))
    val readiness: StateFlow<PermissionReadinessState> = _readiness.asStateFlow()

    val bleState: StateFlow<BleProximityState> = ble.state

    private val _myContactCard = MutableStateFlow(ContactCard())
    val myContactCard: StateFlow<ContactCard> = _myContactCard.asStateFlow()

    private val _countryProfile = MutableStateFlow(CountryProfile())
    val countryProfile: StateFlow<CountryProfile> = _countryProfile.asStateFlow()

    private val _isVerifyingCountry = MutableStateFlow(false)
    val isVerifyingCountry: StateFlow<Boolean> = _isVerifyingCountry.asStateFlow()

    private val _showContactCustomizer = MutableStateFlow(false)
    val showContactCustomizer: StateFlow<Boolean> = _showContactCustomizer.asStateFlow()

    private val _isShareModeEnabled = MutableStateFlow(false)
    val isShareModeEnabled: StateFlow<Boolean> = _isShareModeEnabled.asStateFlow()

    private val _selectedPhotoUri = MutableStateFlow<Uri?>(null)
    val selectedPhotoUri: StateFlow<Uri?> = _selectedPhotoUri.asStateFlow()

    private val _sweepTarget = MutableStateFlow(SweepTarget.NONE)
    val sweepTarget: StateFlow<SweepTarget> = _sweepTarget.asStateFlow()

    private val _incomingContact = MutableStateFlow<IncomingContactOffer?>(null)
    val incomingContact: StateFlow<IncomingContactOffer?> = _incomingContact.asStateFlow()

    private val _incomingPhoto = MutableStateFlow<IncomingPhotoOffer?>(null)
    val incomingPhoto: StateFlow<IncomingPhotoOffer?> = _incomingPhoto.asStateFlow()

    private val _photoTransferState = MutableStateFlow(PhotoTransferState())
    val photoTransferState: StateFlow<PhotoTransferState> = _photoTransferState.asStateFlow()

    private val _dropBadgeState = MutableStateFlow(DropBadgeState())
    val dropBadgeState: StateFlow<DropBadgeState> = _dropBadgeState.asStateFlow()

    private val _externalShareSession = MutableStateFlow<ExternalShareSession?>(null)
    val externalShareSession: StateFlow<ExternalShareSession?> = _externalShareSession.asStateFlow()

    private val _cardSwapState = MutableStateFlow(CardSwapState())
    val cardSwapState: StateFlow<CardSwapState> = _cardSwapState.asStateFlow()

    private val _todayUpdate = MutableStateFlow(YearlyUpdateSchedule.getEffectiveTodayEntry())
    val todayUpdate: StateFlow<DailyUpdateEntry> = _todayUpdate.asStateFlow()

    private val _glowPreviewMode = MutableStateFlow(GlowPreviewMode.AUTO_LOCAL_DATE)
    val glowPreviewMode: StateFlow<GlowPreviewMode> = _glowPreviewMode.asStateFlow()

    private val _activeGlowTheme = MutableStateFlow(
        YearlyUpdateSchedule.resolveActiveGlowTheme(GlowPreviewMode.AUTO_LOCAL_DATE)
    )
    val activeGlowTheme: StateFlow<GlowThemeStyle> = _activeGlowTheme.asStateFlow()

    private val _previewGlowOverride = MutableStateFlow<GlowThemeStyle?>(null)
    val previewGlowOverride: StateFlow<GlowThemeStyle?> = _previewGlowOverride.asStateFlow()

    private val _selfTestReport = MutableStateFlow(
        DeviceDiagnostics.evaluateLocalSelfTest(BlePermissions.evaluateReadiness(app))
    )
    val selfTestReport: StateFlow<SelfTestReport> = _selfTestReport.asStateFlow()

    private val _geminiLoading = MutableStateFlow(false)
    val geminiLoading: StateFlow<Boolean> = _geminiLoading.asStateFlow()

    private val _geminiError = MutableStateFlow<String?>(null)
    val geminiError: StateFlow<String?> = _geminiError.asStateFlow()

    val watcherTelemetry: StateFlow<WatcherTelemetry> = GeminiDiagnosticWatcher.telemetry
    val watcherHealthStatus: StateFlow<WatcherHealthStatus> = GeminiDiagnosticWatcher.healthStatus
    val diagnosticSnapshot: StateFlow<DiagnosticSnapshot?> = GeminiDiagnosticWatcher.latestSnapshot
    val savedContacts: StateFlow<List<SavedContactEntry>> = ContactBookRepository.savedContacts

    private val _userGeminiApiKey = MutableStateFlow("")
    val userGeminiApiKey: StateFlow<String> = _userGeminiApiKey.asStateFlow()

    private val _geminiDiagnosisResult = MutableStateFlow(GeminiDiagnosisResult())
    val geminiDiagnosisResult: StateFlow<GeminiDiagnosisResult> = _geminiDiagnosisResult.asStateFlow()

    private val _showWhatsNewSheet = MutableStateFlow(false)
    val showWhatsNewSheet: StateFlow<Boolean> = _showWhatsNewSheet.asStateFlow()

    private val _whatsNewIsNewInstallOrUpdate = MutableStateFlow(false)
    val whatsNewIsNewInstallOrUpdate: StateFlow<Boolean> = _whatsNewIsNewInstallOrUpdate.asStateFlow()

    val currentInstallVersionLabel: String = WhatsNewCatalog.getVersionDisplayLabel()

    private val _preferencesInitialized = MutableStateFlow(false)
    private val currentInstallSignature: String by lazy { WhatsNewCatalog.getCurrentInstallSignature(app) }
    private var hasEvaluatedInstallWhatsNew: Boolean = false

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private var isAppInForeground: Boolean = true
    private var hasCompletedContactSetupOnce: Boolean = false
    private var hasCompletedPermissionsOnce: Boolean = false
    private var pendingContactAfterSweep: IncomingContactOffer? = null
    private var pendingPhotoAfterSweep: IncomingPhotoOffer? = null
    private var pendingMatchedPeerName: String? = null
    private var pendingMatchedPeerRssi: Int? = null
    private var pendingShareIsGroup: Boolean = false
    private var dropBadgeHideJob: Job? = null
    private var activeGattTransferInProgress: Boolean = false
    private var pendingSwapOffer: IncomingContactOffer? = null

    init {
        GeminiDiagnosticWatcher.attachContext(app)
        ble.registerRadioReceiver()
        viewModelScope.launch {
            ContactBookRepository.loadSavedContacts(app)
        }
        observePreferences()
        observeBleEvents()
        refreshReadiness()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            combine(
                prefs.onboardingCompleted,
                prefs.contactSetupCompleted,
                prefs.contactCard,
                prefs.countryProfile,
                prefs.rssiThreshold,
                prefs.glowPreviewMode,
                prefs.bluetoothPromptAnswered,
                prefs.testOnlyDeviceMode
            ) { values ->
                values
            }.collect { values ->
                val permsDone = values[0] as Boolean
                val contactDone = values[1] as Boolean
                val card = values[2] as ContactCard
                val country = values[3] as CountryProfile
                val threshold = values[4] as Int
                val glowMode = values[5] as GlowPreviewMode
                val btPromptAnswered = values[6] as Boolean
                val testOnlyMode = values[7] as Boolean

                hasCompletedPermissionsOnce = permsDone
                hasCompletedContactSetupOnce = contactDone
                _myContactCard.value = card
                _countryProfile.value = country
                _glowPreviewMode.value = glowMode
                _isTestOnlyMode.value = testOnlyMode
                ble.updateContactCard(card)
                ble.updateRssiThreshold(threshold)

                val currentReady = BlePermissions.evaluateReadiness(app)
                _readiness.value = currentReady

                if (testOnlyMode) {
                    _showBluetoothPrompt.value = false
                    _onboardingStage.value = OnboardingStage.MAIN_APP
                    _selectedTab.value = NavTab.TEST
                } else if (!permsDone || (!currentReady.canProceedToContactSetup && !contactDone)) {
                    _onboardingStage.value = OnboardingStage.PERMISSIONS_GATE
                    _showBluetoothPrompt.value = !btPromptAnswered
                } else if (!contactDone) {
                    _showBluetoothPrompt.value = false
                    _onboardingStage.value = OnboardingStage.CONTACT_SETUP
                } else {
                    _showBluetoothPrompt.value = false
                    _onboardingStage.value = OnboardingStage.MAIN_APP
                }

                refreshEffectiveDailyUpdate(country, glowMode)
                syncBleServiceState()
                if (!_preferencesInitialized.value) {
                    _preferencesInitialized.value = true
                }
            }
        }

        viewModelScope.launch {
            prefs.nameDropEnabled.collect { enabled ->
                val overlayShareActive = FloatingShareOverlayCoordinator.uiState.value.isActive ||
                    _externalShareSession.value?.isActive == true
                ble.setModes(
                    nameDropEnabled = if (overlayShareActive) true else enabled,
                    shareEnabled = _isShareModeEnabled.value || overlayShareActive
                )
                syncBleServiceState()
            }
        }

        viewModelScope.launch {
            prefs.userGeminiApiKey.collect { key ->
                _userGeminiApiKey.value = key
            }
        }

        viewModelScope.launch {
            combine(
                prefs.lastSeenWhatsNewSignature,
                _preferencesInitialized,
                _showBluetoothPrompt
            ) { savedSignature, prefsReady, btPromptVisible ->
                Triple(savedSignature, prefsReady, btPromptVisible)
            }.collect { (savedSignature, prefsReady, btPromptVisible) ->
                if (!prefsReady || btPromptVisible || hasEvaluatedInstallWhatsNew) return@collect
                hasEvaluatedInstallWhatsNew = true
                if (WhatsNewCatalog.shouldShowWhatsNew(savedSignature, currentInstallSignature)) {
                    _whatsNewIsNewInstallOrUpdate.value = true
                    _showWhatsNewSheet.value = true
                    prefs.saveLastSeenWhatsNewSignature(currentInstallSignature)
                }
            }
        }
    }

    private fun observeBleEvents() {
        viewModelScope.launch {
            ble.events.collect { event ->
                when (event) {
                    is BluetoothLeManager.BleEvent.RadioStateChanged -> {
                        refreshReadiness()
                        syncBleServiceState()
                    }

                    is BluetoothLeManager.BleEvent.ProximityMatched -> {
                        if (_isTestOnlyMode.value) return@collect

                        // Case 0: Floating gallery share overlay owns this proximity event
                        if (FloatingShareOverlayCoordinator.uiState.value.isActive) {
                            return@collect
                        }

                        // Case 1: External Gallery Share prompt is waiting for a nearby phone
                        if (_externalShareSession.value?.isActive == true) {
                            executeExternalShareTransfer(event.deviceName)
                            return@collect
                        }

                        // Case 2: User tapped Share inside Share tab & is holding phone against another phone
                        if (_photoTransferState.value.phase == TransferPhase.HOLD_TO_SHARE) {
                            executePendingMediaShare(
                                isGroupShare = pendingShareIsGroup,
                                detectedPeerName = event.deviceName
                            )
                            return@collect
                        }

                        // Case 3: App is closed/backgrounded -> Launch full-screen overlay/activity immediately
                        if (!isAppInForeground) {
                            pendingMatchedPeerName = event.deviceName
                            pendingMatchedPeerRssi = event.rssi
                            IncomingAlertManager.triggerBackgroundProximityLaunch(
                                context = app,
                                peerName = event.deviceName,
                                rssi = event.rssi,
                                kind = "contact"
                            )
                            return@collect
                        }

                        // Case 4: Strict active card lock -> ignore if animation or card already active
                        if (isCardOrSweepLocked()) return@collect

                        pendingMatchedPeerName = event.deviceName
                        pendingMatchedPeerRssi = event.rssi
                        triggerPerimeterSweep(SweepTarget.CONTACT_CARD_EXCHANGE)
                    }

                    is BluetoothLeManager.BleEvent.ContactReceived -> {
                        val savedPortrait = event.photoBytes?.let {
                            MediaStorageHelper.saveReceivedContactPhoto(app, it, prefix = "received_")
                        }
                        val savedBackdrop = event.backgroundPhotoBytes?.let {
                            MediaStorageHelper.saveReceivedContactPhoto(app, it, prefix = "received_bg_")
                        }
                        val hydratedCard = event.contact.copy(
                            photoPath = savedPortrait?.first,
                            photoSha256 = savedPortrait?.second ?: event.contact.photoSha256,
                            backgroundPhotoPath = savedBackdrop?.first,
                            backgroundPhotoSha256 = savedBackdrop?.second ?: event.contact.backgroundPhotoSha256
                        )
                        val baseOffer = IncomingContactOffer(
                            deviceAddress = event.deviceAddress,
                            deviceName = event.deviceName,
                            contact = hydratedCard,
                            rssi = event.rssi,
                            photoBytes = event.photoBytes,
                            backgroundPhotoBytes = event.backgroundPhotoBytes,
                            isReceiveOnlyMode = bleState.value.isReceiveListening
                        )

                        // Auto-save received Contact Card (including phone/call numbers & photo)
                        val sourceLabel = if (bleState.value.isReceiveListening) "Receive Only" else "Received"
                        val offer = autoSaveOfferToContacts(baseOffer, sourceLabel)

                        // If this phone already tapped Share and was waiting for peer, perform Mutual Swap Arc!
                        if (bleState.value.isAwaitingMutualShare) {
                            ble.setModes(
                                nameDropEnabled = bleState.value.isNameDropEnabled,
                                shareEnabled = _isShareModeEnabled.value,
                                awaitingMutualShare = false
                            )
                            startMutualCardSwap(offer)
                            return@collect
                        }

                        if (!isAppInForeground) {
                            pendingContactAfterSweep = offer
                            soundPlayer.playSweepSyncedChime()
                            IncomingAlertManager.showBackgroundContactPopup(
                                context = app,
                                contact = hydratedCard,
                                rssi = event.rssi,
                                savedStatusText = offer.autoSaveStatusText ?: "Contact saved",
                                onAcceptOpenApp = { _incomingContact.value = offer },
                                onDismiss = { ble.setProximityTriggerLocked(false, resetCooldown = true) }
                            )
                        } else if (_sweepTarget.value != SweepTarget.NONE) {
                            pendingContactAfterSweep = offer
                        } else if (_incomingContact.value != null) {
                            // Update active displayed card cleanly with real peer data instead of replaying animation
                            _incomingContact.value = offer
                            _statusToast.value = offer.autoSaveStatusText ?: "Contact saved"
                        } else {
                            pendingContactAfterSweep = offer
                            triggerPerimeterSweep(SweepTarget.CONTACT_CARD_EXCHANGE)
                        }
                    }

                    is BluetoothLeManager.BleEvent.MutualShareHandshakeReceived -> {
                        val savedPortrait = event.photoBytes?.let {
                            MediaStorageHelper.saveReceivedContactPhoto(app, it, prefix = "received_")
                        }
                        val savedBackdrop = event.backgroundPhotoBytes?.let {
                            MediaStorageHelper.saveReceivedContactPhoto(app, it, prefix = "received_bg_")
                        }
                        val hydratedCard = event.contact.copy(
                            photoPath = savedPortrait?.first,
                            photoSha256 = savedPortrait?.second ?: event.contact.photoSha256,
                            backgroundPhotoPath = savedBackdrop?.first,
                            backgroundPhotoSha256 = savedBackdrop?.second ?: event.contact.backgroundPhotoSha256
                        )
                        val baseOffer = IncomingContactOffer(
                            deviceAddress = event.deviceAddress,
                            deviceName = event.deviceName,
                            contact = hydratedCard,
                            rssi = event.rssi,
                            photoBytes = event.photoBytes,
                            backgroundPhotoBytes = event.backgroundPhotoBytes,
                            isSwappedExchange = true
                        )
                        val offer = autoSaveOfferToContacts(baseOffer, "Mutual Swap")
                        if (bleState.value.isAwaitingMutualShare || _incomingContact.value != null) {
                            ble.setModes(
                                nameDropEnabled = bleState.value.isNameDropEnabled,
                                shareEnabled = _isShareModeEnabled.value,
                                awaitingMutualShare = false
                            )
                            startMutualCardSwap(offer)
                        } else {
                            pendingContactAfterSweep = offer
                            if (_sweepTarget.value == SweepTarget.NONE) {
                                triggerPerimeterSweep(SweepTarget.CONTACT_CARD_EXCHANGE)
                            }
                        }
                    }

                    is BluetoothLeManager.BleEvent.PhotoOfferReceived -> {
                        val offer = IncomingPhotoOffer(
                            transferId = "ble_${System.currentTimeMillis()}",
                            senderName = event.senderName,
                            fileName = event.fileName,
                            mimeType = event.mimeType,
                            isVideo = event.isVideo,
                            fileSizeBytes = event.photoBytes.size.toLong(),
                            isGroupShare = event.isGroupShare,
                            photoBytes = event.photoBytes
                        )
                        // Stop listening spinner when offer arrives
                        if (bleState.value.isReceiveListening) {
                            ble.setModes(
                                nameDropEnabled = bleState.value.isNameDropEnabled,
                                shareEnabled = _isShareModeEnabled.value,
                                receiveListening = false
                            )
                        }
                        if (!isAppInForeground) {
                            IncomingAlertManager.showBackgroundMediaAlert(
                                context = app,
                                senderName = event.senderName,
                                isVideo = event.isVideo,
                                isGroup = event.isGroupShare
                            )
                            _incomingPhoto.value = offer
                        } else if (_sweepTarget.value != SweepTarget.NONE) {
                            pendingPhotoAfterSweep = offer
                        } else {
                            pendingPhotoAfterSweep = offer
                            triggerPerimeterSweep(
                                if (event.isGroupShare) SweepTarget.GROUP_SHARE_INIT else SweepTarget.SHARE_HUB_UNLOCK
                            )
                        }
                    }

                    is BluetoothLeManager.BleEvent.TransferProgress -> {
                        if (FloatingShareOverlayCoordinator.uiState.value.isActive) return@collect
                        if (activeGattTransferInProgress) {
                            val clamped = event.progress.coerceIn(0.05f, 0.96f)
                            _photoTransferState.value = _photoTransferState.value.copy(
                                progress = clamped
                            )
                            _dropBadgeState.value = _dropBadgeState.value.copy(
                                phase = DropBadgePhase.SHARING,
                                progress = clamped,
                                title = "Sharing"
                            )
                            _externalShareSession.value?.let { ext ->
                                _externalShareSession.value = ext.copy(
                                    isSharing = true,
                                    progress = clamped,
                                    statusMessage = "Sharing"
                                )
                            }
                        }
                    }

                    is BluetoothLeManager.BleEvent.TransferCompleted -> {
                        if (FloatingShareOverlayCoordinator.uiState.value.isActive) return@collect
                        if (activeGattTransferInProgress) {
                            activeGattTransferInProgress = false
                            completeOutgoingDropBadge(event.peerName, event.success)
                        }
                    }
                }
            }
        }
    }

    private fun isCardOrSweepLocked(): Boolean =
        _sweepTarget.value != SweepTarget.NONE ||
            _incomingContact.value != null ||
            _incomingPhoto.value != null ||
            _cardSwapState.value.isSwapping

    private fun updateProximityLock() {
        ble.setProximityTriggerLocked(locked = isCardOrSweepLocked())
    }

    // ── Bluetooth Capability Yes / No Prompt & Test-Only Mode ─────────────────────

    fun answerBluetoothPromptYes() {
        viewModelScope.launch {
            prefs.setBluetoothPromptChoice(answered = true, testOnlyMode = false)
            _showBluetoothPrompt.value = false
            _isTestOnlyMode.value = false
        }
    }

    fun answerBluetoothPromptNo() {
        viewModelScope.launch {
            prefs.setBluetoothPromptChoice(answered = true, testOnlyMode = true)
            _showBluetoothPrompt.value = false
            _isTestOnlyMode.value = true
            _onboardingStage.value = OnboardingStage.MAIN_APP
            _selectedTab.value = NavTab.TEST
            BleProximityService.stopService(app)
            ble.stopAll()
            _statusToast.value = "Test-Only Mode unlocked (Bluetooth skipped)"
        }
    }

    fun showBluetoothPromptDialog() {
        _showBluetoothPrompt.value = true
    }

    fun switchFromTestModeToBluetoothSetup() {
        viewModelScope.launch {
            prefs.setBluetoothPromptChoice(answered = true, testOnlyMode = false)
            _isTestOnlyMode.value = false
            _onboardingStage.value = OnboardingStage.PERMISSIONS_GATE
            refreshReadiness()
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            refreshReadiness()
            ble.refreshBondedDevices()
            IncomingAlertManager.dismissOverlay(app)
        } else if (bleState.value.isNameDropEnabled && !_isTestOnlyMode.value) {
            if (!_readiness.value.canDrawOverlays || !_readiness.value.hasBackgroundLocation) {
                GeminiDiagnosticWatcher.captureFailureSnapshot(
                    context = app,
                    triggerReason = "App backgrounded with incomplete background/overlay setup",
                    status = WatcherHealthStatus.ISSUE_DETECTED,
                    bleState = bleState.value,
                    transferPhase = _photoTransferState.value.phase,
                    sweepTarget = _sweepTarget.value,
                    isTestOnlyMode = _isTestOnlyMode.value
                )
            }
        }
        syncBleServiceState()
    }

    fun refreshReadiness() {
        val evaluated = BlePermissions.evaluateReadiness(app)
        _readiness.value = evaluated
        _selfTestReport.value = DeviceDiagnostics.evaluateLocalSelfTest(evaluated)
        DebugLogger.log(
            "Perms",
            "BT_ON=${evaluated.isBluetoothEnabled}, BT_PERMS=${evaluated.hasAllBluetoothPermissions}, " +
                "LOC=${evaluated.hasForegroundLocation}, LOC_SVC=${evaluated.isLocationServicesEnabled}, " +
                "BG_LOC=${evaluated.hasBackgroundLocation}, OVERLAY=${evaluated.canDrawOverlays}, " +
                "BATTERY_EXEMPT=${evaluated.isIgnoringBatteryOptimizations}"
        )
    }

    fun proceedFromPermissionsGate() {
        val current = BlePermissions.evaluateReadiness(app)
        _readiness.value = current
        if (!current.canProceedToContactSetup) {
            _statusToast.value = "Please enable Bluetooth, Bluetooth Permissions, and Location first."
            return
        }
        viewModelScope.launch {
            prefs.setOnboardingCompleted(true)
            _onboardingStage.value = if (hasCompletedContactSetupOnce) {
                OnboardingStage.MAIN_APP
            } else {
                OnboardingStage.CONTACT_SETUP
            }
        }
    }

    fun openPermissionsGateFromSettings() {
        _onboardingStage.value = OnboardingStage.PERMISSIONS_GATE
    }

    fun openCountryContactSetupFromSettings() {
        _onboardingStage.value = OnboardingStage.CONTACT_SETUP
    }

    fun selectTab(tab: NavTab) {
        if (_isTestOnlyMode.value && tab != NavTab.TEST) {
            _selectedTab.value = NavTab.TEST
            _statusToast.value = "Test-Only Mode: Only Test Mode is unlocked. Tap 'Enable Bluetooth' to switch."
            return
        }
        _selectedTab.value = tab
    }

    fun toggleNameDrop(enabled: Boolean) {
        val currentReady = BlePermissions.evaluateReadiness(app)
        _readiness.value = currentReady
        if (enabled && !currentReady.canProceedToContactSetup) {
            _statusToast.value = "Enable Bluetooth and Location permissions before turning on Name Drop."
            _onboardingStage.value = OnboardingStage.PERMISSIONS_GATE
            return
        }
        viewModelScope.launch {
            prefs.setNameDropEnabled(enabled)
            if (enabled) {
                _statusToast.value = "Name Drop active at ${bleState.value.rssiThreshold} dBm (background ready)"
            }
        }
    }

    fun toggleShareMode(enabled: Boolean, playSweep: Boolean = true) {
        val currentReady = BlePermissions.evaluateReadiness(app)
        _readiness.value = currentReady
        if (enabled && !currentReady.canProceedToContactSetup) {
            _statusToast.value = "Enable Bluetooth and Location permissions first."
            _onboardingStage.value = OnboardingStage.PERMISSIONS_GATE
            return
        }
        if (enabled) {
            _isShareModeEnabled.value = true
            ble.setModes(nameDropEnabled = bleState.value.isNameDropEnabled, shareEnabled = true)
            syncBleServiceState()
            if (playSweep) {
                triggerPerimeterSweep(SweepTarget.SHARE_HUB_UNLOCK)
            }
        } else {
            _isShareModeEnabled.value = false
            ble.setModes(
                nameDropEnabled = bleState.value.isNameDropEnabled,
                shareEnabled = false,
                receiveListening = false
            )
            syncBleServiceState()
        }
    }

    /**
     * Repairs the Receive button: immediately enters active listening/advertising mode
     * displaying "Waiting for nearby device..." with live BLE discovery feedback.
     */
    fun startReceiveListening() {
        val currentReady = BlePermissions.evaluateReadiness(app)
        _readiness.value = currentReady
        if (!currentReady.canProceedToContactSetup) {
            _statusToast.value = "Enable Bluetooth and Location permissions first."
            _onboardingStage.value = OnboardingStage.PERMISSIONS_GATE
            return
        }
        _isShareModeEnabled.value = true
        ble.clearCooldownForImmediateShare()
        ble.setModes(
            nameDropEnabled = bleState.value.isNameDropEnabled,
            shareEnabled = true,
            receiveListening = true
        )
        _photoTransferState.value = PhotoTransferState(
            phase = TransferPhase.WAITING_NEARBY,
            statusText = "Waiting for nearby device..."
        )
        syncBleServiceState()
        ble.ensureLowLatencyScan()
    }

    fun stopReceiveListening() {
        ble.setModes(
            nameDropEnabled = bleState.value.isNameDropEnabled,
            shareEnabled = _isShareModeEnabled.value,
            receiveListening = false
        )
        if (_photoTransferState.value.phase == TransferPhase.WAITING_NEARBY) {
            _photoTransferState.value = PhotoTransferState()
        }
    }

    /**
     * Persistent BLE Foreground Service sync:
     * Keeps scanning & advertising alive in background whenever Name Drop / Share / Receive is active,
     * so holding two phones together works even when the app is closed or backgrounded.
     */
    private fun syncBleServiceState() {
        if (_isTestOnlyMode.value) {
            BleProximityService.stopService(app)
            ble.stopAll()
            return
        }
        val wantActive = bleState.value.isNameDropEnabled ||
            _isShareModeEnabled.value ||
            bleState.value.isShareEnabled ||
            bleState.value.isReceiveListening ||
            _photoTransferState.value.phase != TransferPhase.IDLE ||
            _externalShareSession.value?.isActive == true ||
            FloatingShareOverlayCoordinator.uiState.value.isActive
        val currentReady = BlePermissions.evaluateReadiness(app)
        if (!wantActive || !currentReady.canProceedToContactSetup) {
            BleProximityService.stopService(app)
            ble.stopAll()
            return
        }
        ble.startProximity(lowPowerBackground = !isAppInForeground)
        BleProximityService.startService(app, lowPower = !isAppInForeground)
    }

    fun restartBluetoothScan() {
        val currentReady = BlePermissions.evaluateReadiness(app)
        _readiness.value = currentReady
        if (!currentReady.canProceedToContactSetup) {
            _statusToast.value = "Enable Bluetooth and Location first."
            _onboardingStage.value = OnboardingStage.PERMISSIONS_GATE
            return
        }
        viewModelScope.launch {
            if (!bleState.value.isNameDropEnabled) {
                prefs.setNameDropEnabled(true)
            }
            ble.restartScanNow()
            _statusToast.value = "Refreshing BLE & paired device scan..."
        }
    }

    fun updateRssiThreshold(newThreshold: Int) {
        viewModelScope.launch {
            prefs.setRssiThreshold(newThreshold)
            ble.updateRssiThreshold(newThreshold)
            DebugLogger.log("BLE", "RSSI proximity threshold updated to $newThreshold dBm")
        }
    }

    fun setGlowPreviewMode(mode: GlowPreviewMode) {
        _previewGlowOverride.value = null
        viewModelScope.launch {
            prefs.setGlowPreviewMode(mode)
        }
    }

    fun verifyCountryFromDevice(currentPhone: String, onPhoneUpdated: (String) -> Unit) {
        viewModelScope.launch {
            _isVerifyingCountry.value = true
            val profile = CountryLocationVerifier.verifyCountryFromDevice(app)
            prefs.saveCountryProfile(profile)
            _countryProfile.value = profile
            val formatted = CountryLocationVerifier.applyDialPrefix(currentPhone, profile.dialPrefix)
            onPhoneUpdated(formatted)
            refreshEffectiveDailyUpdate(profile, _glowPreviewMode.value)
            _isVerifyingCountry.value = false
            _statusToast.value = "Verified ${profile.countryName} (${profile.dialPrefix})"
        }
    }

    fun saveInitialContactSetup(
        displayName: String,
        phoneNumber: String,
        selectedPhotoUri: Uri?,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            var photoPath = _myContactCard.value.photoPath
            var photoSha = _myContactCard.value.photoSha256
            if (selectedPhotoUri != null) {
                MediaStorageHelper.copyContactPhotoToInternal(app, selectedPhotoUri)?.let {
                    photoPath = it.first
                    photoSha = it.second
                }
            }
            val updated = _myContactCard.value.copy(
                displayName = displayName.trim(),
                phoneNumber = phoneNumber.trim(),
                photoPath = photoPath,
                photoSha256 = photoSha
            )
            prefs.saveContactCard(updated)
            prefs.setContactSetupCompleted(true)
            prefs.setNameDropEnabled(true)
            _myContactCard.value = updated
            ble.updateContactCard(updated)
            ble.setModes(nameDropEnabled = true, shareEnabled = _isShareModeEnabled.value)
            _onboardingStage.value = OnboardingStage.MAIN_APP
            syncBleServiceState()
            onDone()
        }
    }

    fun openContactCustomizer() {
        _showContactCustomizer.value = true
    }

    fun closeContactCustomizer() {
        _showContactCustomizer.value = false
    }

    fun saveCustomizedContact(card: ContactCard, newPhotoUri: Uri?, newBgPhotoUri: Uri?) {
        viewModelScope.launch {
            var photoPath = card.photoPath
            var photoSha = card.photoSha256
            if (newPhotoUri != null) {
                MediaStorageHelper.copyContactPhotoToInternal(app, newPhotoUri)?.let {
                    photoPath = it.first
                    photoSha = it.second
                }
            }
            var bgPath = card.backgroundPhotoPath
            var bgSha = card.backgroundPhotoSha256
            if (newBgPhotoUri != null) {
                MediaStorageHelper.copyBackgroundPhotoToInternal(app, newBgPhotoUri)?.let {
                    bgPath = it.first
                    bgSha = it.second
                }
            }
            val finalCard = card.copy(
                photoPath = photoPath,
                photoSha256 = photoSha,
                backgroundPhotoPath = bgPath,
                backgroundPhotoSha256 = bgSha
            )
            prefs.saveContactCard(finalCard)
            _myContactCard.value = finalCard
            ble.updateContactCard(finalCard)
            _showContactCustomizer.value = false
            _statusToast.value = "Profile & Full-Screen Background saved"
        }
    }

    fun selectPhotoToShare(uri: Uri?) {
        _selectedPhotoUri.value = uri
    }

    fun startPhotoShare(isGroupShare: Boolean) {
        val uri = _selectedPhotoUri.value
        if (uri == null) {
            _statusToast.value = "Select a photo or video first"
            return
        }
        if (activeGattTransferInProgress || _photoTransferState.value.phase == TransferPhase.TRANSFERRING) {
            return
        }
        val currentReady = BlePermissions.evaluateReadiness(app)
        if (!currentReady.canProceedToContactSetup) {
            _statusToast.value = "Enable Bluetooth and Location permissions first."
            _onboardingStage.value = OnboardingStage.PERMISSIONS_GATE
            return
        }
        pendingShareIsGroup = isGroupShare
        _isShareModeEnabled.value = true
        ble.clearCooldownForImmediateShare()
        ble.setModes(
            nameDropEnabled = bleState.value.isNameDropEnabled,
            shareEnabled = true,
            receiveListening = false
        )
        syncBleServiceState()

        val isVid = MediaStorageHelper.isVideoUri(app, uri)
        val kindWord = if (isVid) "video" else "photo"

        val bestPeer = ble.getBestPeerSnapshot()
        if (bestPeer != null && bestPeer.second >= bleState.value.rssiThreshold) {
            executePendingMediaShare(isGroupShare, bestPeer.first)
        } else {
            _photoTransferState.value = PhotoTransferState(
                phase = TransferPhase.HOLD_TO_SHARE,
                isVideo = isVid,
                isGroupShare = isGroupShare,
                statusText = "Hold this phone against another phone to share $kindWord"
            )
            ble.ensureLowLatencyScan()
        }
    }

    private fun executePendingMediaShare(isGroupShare: Boolean, detectedPeerName: String?) {
        val uri = _selectedPhotoUri.value ?: return
        viewModelScope.launch {
            val payload = MediaStorageHelper.readMediaPayload(app, uri)
            if (payload == null) {
                _photoTransferState.value = PhotoTransferState(
                    phase = TransferPhase.ERROR,
                    statusText = "Could not read selected media"
                )
                return@launch
            }

            _photoTransferState.value = PhotoTransferState(
                phase = TransferPhase.SWEEPING,
                isVideo = payload.isVideo,
                mimeType = payload.mimeType,
                isGroupShare = isGroupShare,
                peerDeviceName = detectedPeerName,
                statusText = "Detected ${detectedPeerName ?: "nearby phone"} — Starting transfer..."
            )
            triggerPerimeterSweep(
                if (isGroupShare) SweepTarget.GROUP_SHARE_INIT else SweepTarget.SHARE_HUB_UNLOCK
            )
            delay(ShareSoundPlayer.CHIME_DURATION_MS + 60L)

            val senderName = _myContactCard.value.displayName.ifBlank { "My Phone" }
            activeGattTransferInProgress = true
            _dropBadgeState.value = DropBadgeState(
                phase = DropBadgePhase.SHARING,
                progress = 0.12f,
                title = "Sharing",
                subtitle = detectedPeerName ?: payload.fileName
            )
            _photoTransferState.value = PhotoTransferState(
                phase = TransferPhase.TRANSFERRING,
                progress = 0.12f,
                isVideo = payload.isVideo,
                mimeType = payload.mimeType,
                isGroupShare = isGroupShare,
                peerDeviceName = detectedPeerName,
                statusText = "Sending to ${detectedPeerName ?: "nearby device"}..."
            )

            val (sentCount, targetName) = ble.sendMediaToNearbyPeers(
                senderName = senderName,
                fileName = payload.fileName,
                mediaBytes = payload.bytes,
                mimeType = payload.mimeType,
                isVideo = payload.isVideo,
                isGroupShare = isGroupShare
            )
            if (sentCount == 0) {
                activeGattTransferInProgress = false
                GeminiDiagnosticWatcher.cancelHandshakeWatchdog()
                GeminiDiagnosticWatcher.captureFailureSnapshot(
                    context = app,
                    triggerReason = "Media share stalled: no nearby BLE peer found to send ${payload.fileName}",
                    status = WatcherHealthStatus.STALL_DETECTED,
                    bleState = bleState.value,
                    transferPhase = TransferPhase.HOLD_TO_SHARE,
                    sweepTarget = _sweepTarget.value,
                    isTestOnlyMode = _isTestOnlyMode.value
                )
                _dropBadgeState.value = DropBadgeState()
                _photoTransferState.value = PhotoTransferState(
                    phase = TransferPhase.HOLD_TO_SHARE,
                    isVideo = payload.isVideo,
                    isGroupShare = isGroupShare,
                    statusText = "Hold this phone against another phone to share ${if (payload.isVideo) "video" else "photo"}"
                )
            } else {
                GeminiDiagnosticWatcher.startHandshakeWatchdog(
                    context = app,
                    label = payload.fileName,
                    bleStateProvider = { bleState.value },
                    transferPhaseProvider = { _photoTransferState.value.phase },
                    sweepTargetProvider = { _sweepTarget.value },
                    isTestOnlyProvider = { _isTestOnlyMode.value }
                )
                // Fallback progress completion if GATT write callback finishes fast or slow
                delay(1800L)
                if (activeGattTransferInProgress) {
                    activeGattTransferInProgress = false
                    completeOutgoingDropBadge(targetName ?: detectedPeerName ?: "device", true)
                }
            }
        }
    }

    private fun completeOutgoingDropBadge(peerName: String, success: Boolean) {
        viewModelScope.launch {
            GeminiDiagnosticWatcher.cancelHandshakeWatchdog()
            if (success) {
                GeminiDiagnosticWatcher.onHandshakeCompleted(true, null)
                soundPlayer.playCompletionPing()
                _dropBadgeState.value = DropBadgeState(
                    phase = DropBadgePhase.SHARED,
                    progress = 1f,
                    title = "Shared",
                    subtitle = "Sent to $peerName"
                )
                _photoTransferState.value = _photoTransferState.value.copy(
                    phase = TransferPhase.SAVED,
                    progress = 1f,
                    peerDeviceName = peerName,
                    statusText = "Shared with $peerName"
                )
                _externalShareSession.value?.let { ext ->
                    _externalShareSession.value = ext.copy(
                        isArmed = false,
                        isSharing = false,
                        progress = 1f,
                        isSharedComplete = true,
                        statusMessage = "Shared with $peerName"
                    )
                }
                delay(2000L)
                _dropBadgeState.value = DropBadgeState()
                if (_externalShareSession.value?.isSharedComplete == true) {
                    _externalShareSession.value = null
                }
            } else {
                GeminiDiagnosticWatcher.captureFailureSnapshot(
                    context = app,
                    triggerReason = "BLE transfer interrupted with $peerName",
                    status = WatcherHealthStatus.ISSUE_DETECTED,
                    bleState = bleState.value,
                    transferPhase = TransferPhase.ERROR,
                    sweepTarget = _sweepTarget.value,
                    isTestOnlyMode = _isTestOnlyMode.value
                )
                _dropBadgeState.value = DropBadgeState()
                _photoTransferState.value = _photoTransferState.value.copy(
                    phase = TransferPhase.ERROR,
                    statusText = "Transfer interrupted — hold phones closer and try again"
                )
                _externalShareSession.value?.let { ext ->
                    _externalShareSession.value = ext.copy(
                        isArmed = true,
                        isSharing = false,
                        progress = 0f,
                        statusMessage = "Hold phones closer to retry share"
                    )
                }
            }
            updateProximityLock()
        }
    }

    // ── System Share Sheet ("NameDrop Share") Handler ─────────────────────────────

    fun handleExternalShareIntent(uris: List<Uri>, mimeTypes: List<String>, isMultiple: Boolean) {
        if (uris.isEmpty()) return
        val firstMime = mimeTypes.firstOrNull().orEmpty()
        val isVideo = firstMime.startsWith("video/") || MediaStorageHelper.isVideoUri(app, uris.first(), firstMime)
        val kindWord = if (isVideo) "video" else "photo"
        _externalShareSession.value = ExternalShareSession(
            uris = uris,
            mimeTypes = mimeTypes,
            isVideo = isVideo,
            isMultiple = isMultiple,
            isArmed = false,
            isSharing = false,
            progress = 0f,
            isSharedComplete = false,
            statusMessage = "Hold this phone against another phone to share $kindWord"
        )
        _selectedPhotoUri.value = uris.first()
        _isShareModeEnabled.value = true
        ble.clearCooldownForImmediateShare()
        ble.setModes(nameDropEnabled = true, shareEnabled = true, receiveListening = false)
        ble.ensureLowLatencyScan()
        syncBleServiceState()

        val best = ble.getBestPeerSnapshot()
        if (best != null && best.second >= bleState.value.rssiThreshold) {
            executeExternalShareTransfer(best.first)
        }
    }

    fun triggerExternalShareNow() {
        val session = _externalShareSession.value ?: return
        if (session.isSharing || session.isSharedComplete) return
        val kindWord = if (session.isVideo) "video" else "photo"
        _externalShareSession.value = session.copy(
            isArmed = true,
            statusMessage = "Hold this phone against another phone to share $kindWord"
        )
        val best = ble.getBestPeerSnapshot()
        if (best == null && !ble.hasDiscoveredTarget()) {
            ble.ensureLowLatencyScan()
            return
        }
        executeExternalShareTransfer(best?.first ?: "Nearby Phone")
    }

    private fun executeExternalShareTransfer(peerName: String) {
        val session = _externalShareSession.value ?: return
        if (activeGattTransferInProgress || session.isSharedComplete) return
        viewModelScope.launch {
            val firstUri = session.uris.firstOrNull() ?: return@launch
            val payload = MediaStorageHelper.readMediaPayload(
                context = app,
                uri = firstUri,
                hintMime = session.mimeTypes.firstOrNull()
            )
            if (payload == null) {
                _statusToast.value = "Could not read shared media"
                _externalShareSession.value = null
                return@launch
            }

            activeGattTransferInProgress = true
            _externalShareSession.value = _externalShareSession.value?.copy(
                isArmed = true,
                isSharing = true,
                progress = 0.15f,
                statusMessage = "Sharing with $peerName..."
            )
            _dropBadgeState.value = DropBadgeState(
                phase = DropBadgePhase.SHARING,
                progress = 0.15f,
                title = "Sharing",
                subtitle = peerName
            )
            val senderName = _myContactCard.value.displayName.ifBlank { "My Phone" }
            val (sentCount, targetLabel) = ble.sendMediaToNearbyPeers(
                senderName = senderName,
                fileName = payload.fileName,
                mediaBytes = payload.bytes,
                mimeType = payload.mimeType,
                isVideo = payload.isVideo,
                isGroupShare = session.isMultiple
            )
            if (sentCount == 0) {
                activeGattTransferInProgress = false
                _dropBadgeState.value = DropBadgeState()
                val kindWord = if (payload.isVideo) "video" else "photo"
                _externalShareSession.value = _externalShareSession.value?.copy(
                    isArmed = true,
                    isSharing = false,
                    progress = 0f,
                    statusMessage = "Hold this phone against another phone to share $kindWord"
                )
                ble.ensureLowLatencyScan()
            } else {
                delay(2400L)
                if (activeGattTransferInProgress) {
                    activeGattTransferInProgress = false
                    completeOutgoingDropBadge(targetLabel ?: peerName, true)
                }
            }
        }
    }

    fun cancelExternalShareSession() {
        _externalShareSession.value = null
        activeGattTransferInProgress = false
        _dropBadgeState.value = DropBadgeState()
        updateProximityLock()
    }

    // ── Background Launch Handler ─────────────────────────────────────────────────

    fun handleBackgroundProximityLaunch(peerName: String?, rssi: Int?, kind: String?) {
        if (_isTestOnlyMode.value) return
        if (kind == "media" && _incomingPhoto.value != null) {
            triggerPerimeterSweep(SweepTarget.SHARE_HUB_UNLOCK)
            return
        }
        if (isCardOrSweepLocked()) return
        pendingMatchedPeerName = peerName ?: "Nearby Phone"
        pendingMatchedPeerRssi = rssi ?: bleState.value.rssiThreshold
        triggerPerimeterSweep(SweepTarget.BACKGROUND_PROXIMITY_TRIGGER)
    }

    // ── Incoming Media Accept / Decline ───────────────────────────────────────────

    fun acceptIncomingPhoto() {
        val offer = _incomingPhoto.value ?: return
        viewModelScope.launch {
            _dropBadgeState.value = DropBadgeState(
                phase = DropBadgePhase.SHARING,
                progress = 0.3f,
                title = "Receiving",
                subtitle = "From ${offer.senderName}"
            )
            _photoTransferState.value = PhotoTransferState(
                phase = TransferPhase.TRANSFERRING,
                progress = 0.30f,
                isVideo = offer.isVideo,
                mimeType = offer.mimeType,
                isGroupShare = offer.isGroupShare,
                peerDeviceName = offer.senderName,
                statusText = "Receiving from ${offer.senderName}..."
            )
            val bytes = offer.photoBytes ?: offer.previewUri?.let {
                MediaStorageHelper.readUriBytes(app, it)
            }
            if (bytes == null) {
                _dropBadgeState.value = DropBadgeState()
                _photoTransferState.value = PhotoTransferState(
                    phase = TransferPhase.ERROR,
                    statusText = "Media payload missing"
                )
                _incomingPhoto.value = null
                updateProximityLock()
                return@launch
            }
            delay(320L)
            _dropBadgeState.value = _dropBadgeState.value.copy(progress = 0.78f)
            _photoTransferState.value = _photoTransferState.value.copy(progress = 0.78f)

            val savedUri = MediaStorageHelper.saveMediaToGallery(
                context = app,
                mediaBytes = bytes,
                displayName = offer.fileName,
                mimeType = offer.mimeType,
                isVideo = offer.isVideo
            )
            delay(250L)
            if (savedUri != null) {
                soundPlayer.playCompletionPing()
                _dropBadgeState.value = DropBadgeState(
                    phase = DropBadgePhase.SHARED,
                    progress = 1f,
                    title = "Shared",
                    subtitle = "Saved from ${offer.senderName}"
                )
                _photoTransferState.value = PhotoTransferState(
                    phase = TransferPhase.SAVED,
                    progress = 1f,
                    savedGalleryUri = savedUri,
                    isVideo = offer.isVideo,
                    mimeType = offer.mimeType,
                    isGroupShare = offer.isGroupShare,
                    peerDeviceName = offer.senderName,
                    statusText = "Received from ${offer.senderName} • Saved to Gallery"
                )
            } else {
                _dropBadgeState.value = DropBadgeState()
                _photoTransferState.value = PhotoTransferState(
                    phase = TransferPhase.ERROR,
                    statusText = "Could not save media to Gallery"
                )
            }
            _incomingPhoto.value = null
            updateProximityLock()
            delay(1600L)
            _dropBadgeState.value = DropBadgeState()
        }
    }

    fun declineIncomingPhoto() {
        _incomingPhoto.value = null
        _photoTransferState.value = PhotoTransferState(
            phase = TransferPhase.DECLINED,
            statusText = "Transfer declined"
        )
        updateProximityLock()
    }

    fun resetPhotoTransferState() {
        _photoTransferState.value = PhotoTransferState()
        updateProximityLock()
    }

    // ── Contact Card Actions (Receive Only, Share / Mutual Swap, Dismiss) ────────

    fun shareMyContactNow(): Boolean {
        val currentOffer = _incomingContact.value
        ble.clearCooldownForImmediateShare()
        val pushedToPeer = ble.sendContactToNearestPeer(_myContactCard.value, asMutualHandshake = true)

        if (currentOffer != null && !currentOffer.isTestSimulation && currentOffer.deviceAddress != "NEARBY-BLE") {
            // Peer already shared their card -> trigger iOS Swap Arc immediately!
            startMutualCardSwap(currentOffer.copy(isSwappedExchange = true))
            return true
        }

        // Always keep mutual swap armed cleanly so the Share button stays anchored instead of vanishing
        ble.setModes(
            nameDropEnabled = true,
            shareEnabled = _isShareModeEnabled.value,
            awaitingMutualShare = true
        )
        if (!pushedToPeer) {
            ble.ensureLowLatencyScan()
        }
        _statusToast.value = if (pushedToPeer) {
            "Sent your card — waiting for peer to tap Share to swap"
        } else {
            "Ready to share — hold this phone against another phone"
        }
        return pushedToPeer
    }

    fun receiveOnlyIncomingContact() {
        ble.setModes(
            nameDropEnabled = bleState.value.isNameDropEnabled,
            shareEnabled = _isShareModeEnabled.value,
            receiveListening = true,
            awaitingMutualShare = false
        )
        val current = _incomingContact.value
        if (current != null) {
            viewModelScope.launch {
                val savedOffer = autoSaveOfferToContacts(
                    current.copy(isReceiveOnlyMode = true),
                    sourceLabel = "Receive Only"
                )
                _incomingContact.value = savedOffer
                _statusToast.value = savedOffer.autoSaveStatusText ?: "Contact saved"
            }
        } else {
            _statusToast.value = "Receive Only active — ready to accept without sending yours"
        }
    }

    fun saveIncomingContactToBook(onFallbackInsertIntent: (() -> Unit)? = null) {
        val current = _incomingContact.value ?: return
        viewModelScope.launch {
            val source = when {
                current.isSwappedExchange -> "Mutual Swap"
                current.isReceiveOnlyMode -> "Receive Only"
                else -> "Saved"
            }
            val updated = autoSaveOfferToContacts(current, source)
            _incomingContact.value = updated
            _statusToast.value = updated.autoSaveStatusText ?: "Contact saved"
            if (!updated.savedToSystemContacts) {
                onFallbackInsertIntent?.invoke()
            }
        }
    }

    fun syncSavedContactToSystem(entryId: String) {
        viewModelScope.launch {
            refreshReadiness()
            val res = ContactBookRepository.syncExistingLocalContactToSystem(app, entryId)
            _statusToast.value = res?.statusText ?: "Grant Contacts permission to sync"
        }
    }

    private suspend fun autoSaveOfferToContacts(
        offer: IncomingContactOffer,
        sourceLabel: String
    ): IncomingContactOffer {
        val res = ContactBookRepository.autoSaveContactCard(
            context = app,
            card = offer.contact,
            photoBytes = offer.photoBytes,
            sourceLabel = sourceLabel
        ) ?: return offer
        return offer.copy(
            contact = res.entry.card,
            isAutoSavedToContacts = true,
            savedToSystemContacts = res.savedToSystemContacts,
            autoSaveStatusText = res.statusText
        )
    }

    fun dismissIncomingContact() {
        _incomingContact.value = null
        _previewGlowOverride.value = null
        ble.setModes(
            nameDropEnabled = bleState.value.isNameDropEnabled,
            shareEnabled = _isShareModeEnabled.value,
            awaitingMutualShare = false
        )
        ble.setProximityTriggerLocked(locked = false, resetCooldown = true)
    }

    private fun startMutualCardSwap(peerOffer: IncomingContactOffer) {
        pendingSwapOffer = peerOffer.copy(isSwappedExchange = true)
        _incomingContact.value = null
        _cardSwapState.value = CardSwapState(
            isSwapping = true,
            myCard = _myContactCard.value,
            peerCard = peerOffer.contact,
            peerPhotoBytes = peerOffer.photoBytes,
            peerBgPhotoBytes = peerOffer.backgroundPhotoBytes,
            peerDeviceName = peerOffer.deviceName,
            peerRssi = peerOffer.rssi
        )
        updateProximityLock()
    }

    fun onMutualSwapAnimationFinished() {
        val completedOffer = pendingSwapOffer
        pendingSwapOffer = null
        _cardSwapState.value = CardSwapState()
        if (completedOffer != null) {
            viewModelScope.launch {
                val savedOffer = autoSaveOfferToContacts(completedOffer, "Mutual Swap")
                _incomingContact.value = savedOffer
                _statusToast.value = "Contacts Swapped • ${savedOffer.autoSaveStatusText ?: "Contact saved"}"
                updateProximityLock()
            }
        } else {
            _incomingContact.value = null
            _statusToast.value = "Contacts Swapped!"
            updateProximityLock()
        }
    }

    // ── 6-Stage Slow-Motion Audio-Locked Sweep Sequence ──────────────────────────

    fun triggerPerimeterSweep(target: SweepTarget) {
        if (_sweepTarget.value != SweepTarget.NONE) return
        _sweepTarget.value = target
        updateProximityLock()
        soundPlayer.playSweepSyncedChime()
        if (soundPlayer.isMediaVolumeMuted()) {
            _statusToast.value = "Media volume is muted — turn up Media volume to hear the chime"
        }
        DebugLogger.log("Anim", "Started audio-locked 6-stage camera-cutout sweep ($target)")
    }

    fun getChimePositionMs(): Long = soundPlayer.getCurrentPositionMs()

    fun onPerimeterSweepFinished() {
        val target = _sweepTarget.value
        _sweepTarget.value = SweepTarget.NONE
        when (target) {
            SweepTarget.CONTACT_CARD_EXCHANGE,
            SweepTarget.BACKGROUND_PROXIMITY_TRIGGER -> {
                val matchedName = pendingMatchedPeerName
                val matchedRssi = pendingMatchedPeerRssi
                pendingMatchedPeerName = null
                pendingMatchedPeerRssi = null

                val offer = pendingContactAfterSweep ?: IncomingContactOffer(
                    deviceAddress = "NEARBY-BLE",
                    deviceName = matchedName ?: "Nearby Phone",
                    contact = _myContactCard.value.let {
                        if (it.isConfigured) it else ContactCard(
                            displayName = "Alex Mercer",
                            phoneNumber = "+1 415 555 0194",
                            email = "alex@namedrop.app",
                            company = "Studio Design",
                            fullScreenBackgroundEnabled = true
                        )
                    },
                    rssi = matchedRssi ?: bleState.value.smoothedRssi ?: bleState.value.rssiThreshold,
                    isTestSimulation = matchedName == null
                )
                pendingContactAfterSweep = null
                _incomingContact.value = offer
                if (offer.isAutoSavedToContacts) {
                    _statusToast.value = offer.autoSaveStatusText ?: "Contact saved"
                }
            }

            SweepTarget.SHARE_HUB_UNLOCK,
            SweepTarget.GROUP_SHARE_INIT -> {
                val photoOffer = pendingPhotoAfterSweep
                if (photoOffer != null) {
                    pendingPhotoAfterSweep = null
                    _incomingPhoto.value = photoOffer
                }
            }

            SweepTarget.TEST_GLOW_UP_PREVIEW -> {
                val testCard = _myContactCard.value.let {
                    if (it.isConfigured) it else ContactCard(
                        displayName = "Glow Preview",
                        phoneNumber = "+1 415 555 0140",
                        fullScreenBackgroundEnabled = true
                    )
                }
                _incomingContact.value = IncomingContactOffer(
                    deviceAddress = "GLOW-PREVIEW",
                    deviceName = "${(_previewGlowOverride.value ?: _activeGlowTheme.value).label} Preview",
                    contact = testCard,
                    rssi = -42,
                    isTestSimulation = true
                )
            }

            SweepTarget.CONTACT_CARD_SWAP,
            SweepTarget.EXTERNAL_SHARE_DROP,
            SweepTarget.TEST_PERIMETER_ONLY,
            SweepTarget.NONE -> Unit
        }
        updateProximityLock()
    }

    // ── Test Lab Triggers (Available in Full & Test-Only Modes) ──────────────────

    fun runTestContactExchange() {
        val card = _myContactCard.value.let {
            if (it.isConfigured) it else ContactCard(
                displayName = "Sample Contact",
                phoneNumber = "${_countryProfile.value.dialPrefix} 555 0198",
                email = "hello@namedrop.app",
                fullScreenBackgroundEnabled = true
            )
        }
        val photoBytes = MediaStorageHelper.readContactPhotoBytesForBle(card.photoPath)
        val bgBytes = if (card.fullScreenBackgroundEnabled) {
            MediaStorageHelper.readContactPhotoBytesForBle(card.backgroundPhotoPath, maxBytes = 64_000)
        } else {
            null
        }
        pendingContactAfterSweep = IncomingContactOffer(
            deviceAddress = "TEST-EXACT-SYNC",
            deviceName = "NameDrop Card Preview",
            contact = card,
            rssi = bleState.value.rssiThreshold,
            photoBytes = photoBytes,
            backgroundPhotoBytes = bgBytes,
            isTestSimulation = true
        )
        triggerPerimeterSweep(SweepTarget.CONTACT_CARD_EXCHANGE)
    }

    fun runTestMutualCardSwap() {
        val myCard = _myContactCard.value.let {
            if (it.isConfigured) it else ContactCard(
                displayName = "My Profile",
                phoneNumber = "${_countryProfile.value.dialPrefix} 555 0101"
            )
        }
        val peerCard = ContactCard(
            displayName = "Jordan Rivera",
            phoneNumber = "+1 415 555 0182",
            email = "jordan@example.com",
            company = "NameDrop Studio",
            backgroundColorHex = 0xFF0B3B69,
            fullScreenBackgroundEnabled = true,
            glowAccent = _activeGlowTheme.value
        )
        val swapOffer = IncomingContactOffer(
            deviceAddress = "TEST-SWAP",
            deviceName = "Jordan's Phone",
            contact = peerCard,
            rssi = -41,
            isTestSimulation = false,
            isSwappedExchange = true
        )
        pendingSwapOffer = swapOffer
        _cardSwapState.value = CardSwapState(
            isSwapping = true,
            myCard = myCard,
            peerCard = peerCard,
            peerDeviceName = "Jordan's Phone",
            peerRssi = -41
        )
        soundPlayer.playSweepSyncedChime()
        updateProximityLock()
    }

    fun runTestPhotoShare(isGroupShare: Boolean) {
        val uri = _selectedPhotoUri.value
        viewModelScope.launch {
            val payload = if (uri != null) {
                MediaStorageHelper.readMediaPayload(app, uri)
            } else {
                MediaStorageHelper.ReadMediaPayload(
                    bytes = createTestSampleJpegBytes(),
                    fileName = if (isGroupShare) "GroupDrop_Test.jpg" else "NameDrop_Test.jpg",
                    mimeType = "image/jpeg",
                    isVideo = false
                )
            }
            if (payload != null) {
                pendingPhotoAfterSweep = IncomingPhotoOffer(
                    transferId = "test_${System.currentTimeMillis()}",
                    senderName = _myContactCard.value.displayName.ifBlank { "Test Sender" },
                    fileName = payload.fileName,
                    mimeType = payload.mimeType,
                    isVideo = payload.isVideo,
                    fileSizeBytes = payload.bytes.size.toLong(),
                    isGroupShare = isGroupShare,
                    photoBytes = payload.bytes,
                    isTestSimulation = true
                )
                triggerPerimeterSweep(
                    if (isGroupShare) SweepTarget.GROUP_SHARE_INIT else SweepTarget.SHARE_HUB_UNLOCK
                )
            }
        }
    }

    fun runTestDropBadgeProgress() {
        dropBadgeHideJob?.cancel()
        dropBadgeHideJob = viewModelScope.launch {
            for (step in 1..10) {
                _dropBadgeState.value = DropBadgeState(
                    phase = DropBadgePhase.SHARING,
                    progress = step / 10f,
                    title = "Sharing",
                    subtitle = "Nearby Phone • ${step * 10}%"
                )
                delay(180L)
            }
            soundPlayer.playCompletionPing()
            _dropBadgeState.value = DropBadgeState(
                phase = DropBadgePhase.SHARED,
                progress = 1f,
                title = "Shared",
                subtitle = "Transfer Complete"
            )
            delay(1800L)
            _dropBadgeState.value = DropBadgeState()
        }
    }

    fun previewGlowTheme(style: GlowThemeStyle) {
        _previewGlowOverride.value = style
        triggerPerimeterSweep(SweepTarget.TEST_GLOW_UP_PREVIEW)
    }

    fun jumpToCalendarEntry(year: Int, month1Based: Int, day: Int) {
        val entry = YearlyUpdateSchedule.getEntryForDate(year, month1Based, day)
        _todayUpdate.value = entry
        if (entry.glowTheme != GlowThemeStyle.NONE) {
            _previewGlowOverride.value = entry.glowTheme
        }
    }

    fun runGeminiDailyRefreshAndSelfTest() {
        viewModelScope.launch {
            _geminiLoading.value = true
            _geminiError.value = null
            refreshReadiness()
            val localReport = DeviceDiagnostics.evaluateLocalSelfTest(_readiness.value)
            _selfTestReport.value = localReport

            val currentEdition = YearlyUpdateSchedule.getEffectiveTodayEntry(
                zoneId = _countryProfile.value.zoneId
            )
            val result = aiEngine.fetchGeminiDailyIntelligence(
                editionEntry = currentEdition,
                country = _countryProfile.value,
                readiness = _readiness.value,
                localSelfTest = localReport
            )
            result.onSuccess { (tip, aiReport) ->
                val hydrated = currentEdition.copy(
                    featureHighlight = tip,
                    isFromGemini = true
                )
                _todayUpdate.value = hydrated
                _selfTestReport.value = aiReport
                prefs.saveDailyUpdateCache(currentEdition.dateKey, tip)
            }.onFailure { err ->
                _geminiError.value = err.localizedMessage ?: "Gemini request failed."
            }
            _geminiLoading.value = false
        }
    }

    private fun refreshEffectiveDailyUpdate(country: CountryProfile, previewMode: GlowPreviewMode) {
        val entry = YearlyUpdateSchedule.getEffectiveTodayEntry(zoneId = country.zoneId)
        _todayUpdate.value = entry
        _activeGlowTheme.value = YearlyUpdateSchedule.resolveActiveGlowTheme(
            previewMode = previewMode,
            zoneId = country.zoneId
        )
    }

    // ── Gemini Intelligence Watcher & Optional API Key Diagnosis ────────────────

    fun runProximityWatchdogCheck() {
        refreshReadiness()
        syncBleServiceState()
        if (!_isTestOnlyMode.value && bleState.value.isNameDropEnabled) {
            ble.restartScanNow()
        }
        GeminiDiagnosticWatcher.startProximityCheckWatchdog(
            context = app,
            triggerLabel = "Manual Test Proximity Now (4s)",
            bleStateProvider = { bleState.value },
            transferPhaseProvider = { _photoTransferState.value.phase },
            sweepTargetProvider = { _sweepTarget.value },
            isTestOnlyProvider = { _isTestOnlyMode.value },
            timeoutSeconds = 4
        )
        _statusToast.value = "Watching proximity & background service for 4 seconds..."
    }

    fun captureDiagnosticSnapshotNow() {
        refreshReadiness()
        GeminiDiagnosticWatcher.captureFailureSnapshot(
            context = app,
            triggerReason = "Manual diagnostic snapshot captured by user",
            status = if (_readiness.value.isFullyBackgroundCapable && bleState.value.isScanning) {
                WatcherHealthStatus.HEALTHY
            } else {
                WatcherHealthStatus.ISSUE_DETECTED
            },
            bleState = bleState.value,
            transferPhase = _photoTransferState.value.phase,
            sweepTarget = _sweepTarget.value,
            isTestOnlyMode = _isTestOnlyMode.value
        )
        _statusToast.value = "Diagnostic AI Fix Prompt ready to copy"
    }

    fun saveUserGeminiApiKey(key: String) {
        viewModelScope.launch {
            prefs.saveUserGeminiApiKey(key)
            _geminiDiagnosisResult.value = _geminiDiagnosisResult.value.copy(errorMessage = null)
            _statusToast.value = if (key.isBlank()) "Gemini API key cleared" else "Gemini API key saved locally"
        }
    }

    fun clearUserGeminiApiKey() {
        saveUserGeminiApiKey("")
        _geminiDiagnosisResult.value = GeminiDiagnosisResult()
    }

    fun askGeminiToDiagnose() {
        val apiKey = _userGeminiApiKey.value.trim()
        if (apiKey.isEmpty()) {
            _geminiDiagnosisResult.value = GeminiDiagnosisResult(
                errorMessage = "Optional: Enter your Gemini API key above to use AI diagnosis."
            )
            return
        }
        val snapshot = GeminiDiagnosticWatcher.latestSnapshot.value
            ?: GeminiDiagnosticWatcher.captureFailureSnapshot(
                context = app,
                triggerReason = "Gemini API Diagnostic Requested",
                status = WatcherHealthStatus.WATCHING,
                bleState = bleState.value,
                transferPhase = _photoTransferState.value.phase,
                sweepTarget = _sweepTarget.value,
                isTestOnlyMode = _isTestOnlyMode.value
            )

        viewModelScope.launch {
            _geminiDiagnosisResult.value = GeminiDiagnosisResult(isAnalyzing = true)
            val result = GeminiDiagnosticWatcher.analyzeWithUserApiKey(apiKey, snapshot)
            result.onSuccess { diagnosis ->
                _geminiDiagnosisResult.value = diagnosis
            }.onFailure { err ->
                _geminiDiagnosisResult.value = GeminiDiagnosisResult(
                    isAnalyzing = false,
                    errorMessage = err.localizedMessage ?: "Failed contacting Gemini API."
                )
            }
        }
    }

    fun openWhatsNew() {
        _whatsNewIsNewInstallOrUpdate.value = false
        _showWhatsNewSheet.value = true
    }

    fun dismissWhatsNew() {
        _showWhatsNewSheet.value = false
        viewModelScope.launch {
            prefs.saveLastSeenWhatsNewSignature(currentInstallSignature)
        }
    }

    fun consumeStatusToast() {
        _statusToast.value = null
    }

    private fun createTestSampleJpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#0A84FF"))
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 48f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("Name Drop Test", 320f, 320f, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    override fun onCleared() {
        super.onCleared()
        soundPlayer.stop()
        ble.unregisterRadioReceiver()
    }
}
