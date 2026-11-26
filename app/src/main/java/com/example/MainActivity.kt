package com.example

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.ai.configureAppCheck
import com.example.ble.IncomingAlertManager
import com.example.data.MediaStorageHelper
import com.example.model.GlowThemeStyle
import com.example.model.NavTab
import com.example.model.OnboardingStage
import com.example.model.SweepTarget
import com.example.ui.components.DropTransferBadgeOverlay
import com.example.ui.components.FloatingPillNavBar
import com.example.ui.components.FullScreenNameDropOverlay
import com.example.ui.components.MutualCardSwapOverlay
import com.example.ui.components.PerimeterSweepOverlay
import com.example.ui.screens.Android8UnsupportedScreen
import com.example.ui.screens.ContactCustomizerSheet
import com.example.ui.screens.CountryVerifySetupSheet
import com.example.ui.screens.NameDropHomeScreen
import com.example.ui.screens.NameDropShareScreen
import com.example.ui.screens.PermissionsSetupScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TestScreen
import com.example.ui.screens.TutorialScreen
import com.example.ui.screens.UpdatesScreen
import com.example.ui.theme.IosBlue
import com.example.ui.theme.LocalIosColors
import com.example.ui.theme.NameDropTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch Intent before Compose UI or ViewModels
        configureAppCheck(this, intent)

        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            NameDropTheme {
                if (viewModel.isAndroid8Unsupported) {
                    Android8UnsupportedScreen()
                    return@NameDropTheme
                }

                val ios = LocalIosColors.current
                val context = LocalContext.current
                val onboardingStage by viewModel.onboardingStage.collectAsStateWithLifecycle()
                val isTestOnlyMode by viewModel.isTestOnlyMode.collectAsStateWithLifecycle()
                val showBluetoothPrompt by viewModel.showBluetoothPrompt.collectAsStateWithLifecycle()
                val readiness by viewModel.readiness.collectAsStateWithLifecycle()
                val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
                val bleState by viewModel.bleState.collectAsStateWithLifecycle()
                val myCard by viewModel.myContactCard.collectAsStateWithLifecycle()
                val country by viewModel.countryProfile.collectAsStateWithLifecycle()
                val isVerifyingCountry by viewModel.isVerifyingCountry.collectAsStateWithLifecycle()
                val showCustomizer by viewModel.showContactCustomizer.collectAsStateWithLifecycle()
                val isShareEnabled by viewModel.isShareModeEnabled.collectAsStateWithLifecycle()
                val selectedPhotoUri by viewModel.selectedPhotoUri.collectAsStateWithLifecycle()
                val transferState by viewModel.photoTransferState.collectAsStateWithLifecycle()
                val sweepTarget by viewModel.sweepTarget.collectAsStateWithLifecycle()
                val incomingContact by viewModel.incomingContact.collectAsStateWithLifecycle()
                val incomingPhoto by viewModel.incomingPhoto.collectAsStateWithLifecycle()
                val dropBadgeState by viewModel.dropBadgeState.collectAsStateWithLifecycle()
                val externalShareSession by viewModel.externalShareSession.collectAsStateWithLifecycle()
                val cardSwapState by viewModel.cardSwapState.collectAsStateWithLifecycle()
                val todayUpdate by viewModel.todayUpdate.collectAsStateWithLifecycle()
                val glowPreviewMode by viewModel.glowPreviewMode.collectAsStateWithLifecycle()
                val activeGlowTheme by viewModel.activeGlowTheme.collectAsStateWithLifecycle()
                val previewGlowOverride by viewModel.previewGlowOverride.collectAsStateWithLifecycle()
                val selfTest by viewModel.selfTestReport.collectAsStateWithLifecycle()
                val geminiLoading by viewModel.geminiLoading.collectAsStateWithLifecycle()
                val geminiError by viewModel.geminiError.collectAsStateWithLifecycle()
                val statusToast by viewModel.statusToast.collectAsStateWithLifecycle()

                val effectiveGlow = previewGlowOverride ?: activeGlowTheme

                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(statusToast) {
                    statusToast?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.consumeStatusToast()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (onboardingStage) {
                        OnboardingStage.PERMISSIONS_GATE -> {
                            PermissionsSetupScreen(
                                readiness = readiness,
                                showBluetoothPrompt = showBluetoothPrompt,
                                onBluetoothPromptYes = viewModel::answerBluetoothPromptYes,
                                onBluetoothPromptNo = viewModel::answerBluetoothPromptNo,
                                onShowBluetoothPromptAgain = viewModel::showBluetoothPromptDialog,
                                onRefreshReadiness = viewModel::refreshReadiness,
                                onContinueToContactSetup = viewModel::proceedFromPermissionsGate
                            )
                        }

                        OnboardingStage.CONTACT_SETUP -> {
                            CountryVerifySetupSheet(
                                initialCard = myCard,
                                countryProfile = country,
                                isVerifyingCountry = isVerifyingCountry,
                                onBackToPermissions = viewModel::openPermissionsGateFromSettings,
                                onVerifyCountry = viewModel::verifyCountryFromDevice,
                                onSaveContactSetup = { name, phone, uri ->
                                    viewModel.saveInitialContactSetup(name, phone, uri) {}
                                }
                            )
                        }

                        OnboardingStage.MAIN_APP -> {
                            Scaffold(
                                containerColor = ios.background,
                                snackbarHost = { SnackbarHost(snackbarHostState) },
                                bottomBar = {
                                    FloatingPillNavBar(
                                        selectedTab = selectedTab,
                                        isTestOnlyMode = isTestOnlyMode,
                                        onSelectTab = viewModel::selectTab
                                    )
                                }
                            ) { innerPadding ->
                                when (selectedTab) {
                                    NavTab.NAME_DROP -> {
                                        NameDropHomeScreen(
                                            readiness = readiness,
                                            bleState = bleState,
                                            myCard = myCard,
                                            activeGlowTheme = activeGlowTheme,
                                            contentPadding = innerPadding,
                                            onToggleNameDrop = viewModel::toggleNameDrop,
                                            onOpenPermissionsGate = viewModel::openPermissionsGateFromSettings,
                                            onRestartBluetoothScan = viewModel::restartBluetoothScan,
                                            onOpenBluetoothSettings = {
                                                runCatching {
                                                    startActivity(com.example.ble.BlePermissions.createBluetoothSettingsIntent())
                                                }
                                            },
                                            onCustomizeCard = viewModel::openContactCustomizer,
                                            onQuickTestSweep = {
                                                viewModel.triggerPerimeterSweep(SweepTarget.CONTACT_CARD_EXCHANGE)
                                            }
                                        )
                                    }

                                    NavTab.SHARE -> {
                                        NameDropShareScreen(
                                            isShareEnabled = isShareEnabled,
                                            bleState = bleState,
                                            selectedPhotoUri = selectedPhotoUri,
                                            transferState = transferState,
                                            contentPadding = innerPadding,
                                            onToggleShare = viewModel::toggleShareMode,
                                            onStartReceiveListening = viewModel::startReceiveListening,
                                            onStopReceiveListening = viewModel::stopReceiveListening,
                                            onSelectPhoto = viewModel::selectPhotoToShare,
                                            onStartSingleShare = { viewModel.startPhotoShare(isGroupShare = false) },
                                            onStartGroupShare = { viewModel.startPhotoShare(isGroupShare = true) },
                                            onResetTransfer = viewModel::resetPhotoTransferState
                                        )
                                    }

                                    NavTab.TEST -> {
                                        TestScreen(
                                            contentPadding = innerPadding,
                                            isTestOnlyMode = isTestOnlyMode,
                                            glowPreviewMode = glowPreviewMode,
                                            activeGlowTheme = effectiveGlow,
                                            onSwitchToBluetoothSetup = viewModel::switchFromTestModeToBluetoothSetup,
                                            onCustomizeCardInTestMode = viewModel::openContactCustomizer,
                                            onGlowPreviewModeChange = viewModel::setGlowPreviewMode,
                                            onTestPerimeterSweep = {
                                                viewModel.triggerPerimeterSweep(SweepTarget.TEST_PERIMETER_ONLY)
                                            },
                                            onTestContactCardSync = viewModel::runTestContactExchange,
                                            onTestMutualCardSwap = viewModel::runTestMutualCardSwap,
                                            onTestSinglePhotoShare = { viewModel.runTestPhotoShare(isGroupShare = false) },
                                            onTestGroupShare = { viewModel.runTestPhotoShare(isGroupShare = true) },
                                            onTestDropBadgeProgress = viewModel::runTestDropBadgeProgress,
                                            onTestGlowTheme = viewModel::previewGlowTheme
                                        )
                                    }

                                    NavTab.UPDATES -> {
                                        UpdatesScreen(
                                            todayUpdate = todayUpdate,
                                            activeGlowTheme = activeGlowTheme,
                                            country = country,
                                            selfTest = selfTest,
                                            readiness = readiness,
                                            bleState = bleState,
                                            isGeminiLoading = geminiLoading,
                                            geminiError = geminiError,
                                            contentPadding = innerPadding,
                                            onRunGeminiUpdateAndSelfTest = viewModel::runGeminiDailyRefreshAndSelfTest,
                                            onPreviewActiveGlow = viewModel::previewGlowTheme,
                                            onSelectCalendarDate = viewModel::jumpToCalendarEntry,
                                            onOpenTestTab = { viewModel.selectTab(NavTab.TEST) },
                                            onOpenSettingsTab = { viewModel.selectTab(NavTab.SETTINGS) },
                                            onOpenPermissionsGate = viewModel::openPermissionsGateFromSettings
                                        )
                                    }

                                    NavTab.SETTINGS -> {
                                        SettingsScreen(
                                            readiness = readiness,
                                            rssiThreshold = bleState.rssiThreshold,
                                            country = country,
                                            glowPreviewMode = glowPreviewMode,
                                            activeGlowTheme = activeGlowTheme,
                                            contentPadding = innerPadding,
                                            onRssiThresholdChange = viewModel::updateRssiThreshold,
                                            onGlowPreviewModeChange = viewModel::setGlowPreviewMode,
                                            onOpenPermissionsGate = viewModel::openPermissionsGateFromSettings,
                                            onOpenCountrySetup = viewModel::openCountryContactSetupFromSettings
                                        )
                                    }

                                    NavTab.TUTORIAL -> {
                                        TutorialScreen(
                                            contentPadding = innerPadding,
                                            onOpenPermissionsGate = viewModel::openPermissionsGateFromSettings
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Profile & Full-Screen Background Profile Customizer Modal
                    if (showCustomizer) {
                        ContactCustomizerSheet(
                            currentCard = myCard,
                            onDismiss = viewModel::closeContactCustomizer,
                            onSave = viewModel::saveCustomizedContact
                        )
                    }

                    // Full-Screen Edge-to-Edge iOS NameDrop Contact Card Overlay (ONLY appears after sweep finishes)
                    if (sweepTarget == SweepTarget.NONE && !cardSwapState.isSwapping) {
                        incomingContact?.let { offer ->
                            FullScreenNameDropOverlay(
                                offer = offer,
                                activeGlowTheme = effectiveGlow,
                                isAwaitingMutualShare = bleState.isAwaitingMutualShare,
                                onReceiveOnly = viewModel::receiveOnlyIncomingContact,
                                onShare = {
                                    viewModel.shareMyContactNow()
                                },
                                onSaveContact = {
                                    runCatching {
                                        startActivity(MediaStorageHelper.buildSaveContactIntent(offer.contact))
                                    }
                                },
                                onDone = viewModel::dismissIncomingContact
                            )
                        }

                        // Incoming Photo / Video Accept / Decline Bottom Sheet
                        incomingPhoto?.let { offer ->
                            ModalBottomSheet(
                                onDismissRequest = viewModel::declineIncomingPhoto,
                                containerColor = Color.White,
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 22.dp, vertical = 12.dp)
                                        .navigationBarsPadding(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val kindTitle = when {
                                        offer.isGroupShare && offer.isVideo -> "Incoming Group Video"
                                        offer.isGroupShare -> "Incoming Group Photo"
                                        offer.isVideo -> "Incoming Video"
                                        else -> "Incoming Photo"
                                    }
                                    Text(
                                        text = kindTitle,
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "From ${offer.senderName} • ${offer.fileName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF6C6C70)
                                    )

                                    offer.photoBytes?.let { bytes ->
                                        val bmp = remember(bytes, offer.isVideo) {
                                            if (offer.isVideo) {
                                                MediaStorageHelper.extractVideoThumbnailFromBytes(context, bytes)
                                                    ?.asImageBitmap()
                                            } else {
                                                android.graphics.BitmapFactory
                                                    .decodeByteArray(bytes, 0, bytes.size)
                                                    ?.asImageBitmap()
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(200.dp)
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(Color.Black),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            bmp?.let {
                                                Image(
                                                    bitmap = it,
                                                    contentDescription = "Incoming media preview",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            if (offer.isVideo) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = Color.Black.copy(alpha = 0.65f)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.PlayCircle,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                        Text("VIDEO", color = Color.White, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            onClick = viewModel::declineIncomingPhoto,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 52.dp)
                                                .testTag("incoming_photo_decline_button"),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFF2F2F7),
                                                contentColor = Color(0xFFFF3B30)
                                            )
                                        ) {
                                            Text("Decline", fontWeight = FontWeight.SemiBold)
                                        }
                                        Button(
                                            onClick = viewModel::acceptIncomingPhoto,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 52.dp)
                                                .testTag("incoming_photo_accept_button"),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = IosBlue,
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Text("Accept", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }

                        // Compact "Hold this phone against another phone" Share Sheet Prompt (from Gallery/Apps)
                        externalShareSession?.let { session ->
                            ModalBottomSheet(
                                onDismissRequest = viewModel::cancelExternalShareSession,
                                containerColor = Color.White,
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 22.dp, vertical = 12.dp)
                                        .navigationBarsPadding(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.BluetoothSearching,
                                            contentDescription = null,
                                            tint = IosBlue,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Text(
                                            text = "NameDrop Share",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }

                                    Text(
                                        text = session.statusMessage,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF1C1C1E)
                                    )

                                    session.uris.firstOrNull()?.let { previewUri ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(170.dp)
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(Color.Black),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (session.isVideo) {
                                                val thumb = remember(previewUri) {
                                                    MediaStorageHelper.extractVideoThumbnail(context, previewUri)
                                                        ?.asImageBitmap()
                                                }
                                                thumb?.let {
                                                    Image(
                                                        bitmap = it,
                                                        contentDescription = "Shared video preview",
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayCircle,
                                                    contentDescription = "Video",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(48.dp)
                                                )
                                            } else {
                                                AsyncImage(
                                                    model = previewUri,
                                                    contentDescription = "Shared photo preview",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = IosBlue,
                                            strokeWidth = 2.5.dp
                                        )
                                        Spacer(modifier = Modifier.size(10.dp))
                                        Text(
                                            text = bleState.nearestDeviceName?.let {
                                                "Nearby: $it (${bleState.smoothedRssi ?: bleState.nearestRssi ?: "--"} dBm)"
                                            } ?: "Scanning via Bluetooth proximity...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color(0xFF6C6C70)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            onClick = viewModel::cancelExternalShareSession,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 50.dp)
                                                .testTag("external_share_cancel_button"),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFF2F2F7),
                                                contentColor = Color.Black
                                            )
                                        ) {
                                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                                        }
                                        Button(
                                            onClick = viewModel::triggerExternalShareNow,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 50.dp)
                                                .testTag("external_share_send_now_button"),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = IosBlue,
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Send,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.size(6.dp))
                                            Text("Share Now", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }

                    // Mutual Share iOS Contact Card Swap Animation
                    MutualCardSwapOverlay(
                        swapState = cardSwapState,
                        onSwapComplete = viewModel::onMutualSwapAnimationFinished
                    )

                    // Slow-Motion 6-Stage Audio-Locked Camera Cutout Sweep Overlay
                    PerimeterSweepOverlay(
                        isVisible = sweepTarget != SweepTarget.NONE,
                        audioPositionProviderMs = viewModel::getChimePositionMs,
                        glowTheme = if (sweepTarget == SweepTarget.TEST_GLOW_UP_PREVIEW) {
                            previewGlowOverride ?: activeGlowTheme
                        } else {
                            GlowThemeStyle.NONE
                        },
                        onSweepComplete = viewModel::onPerimeterSweepFinished
                    )

                    // Top-Center Animated Drop Pop-Down ("Sharing" ring progress -> green "Shared")
                    DropTransferBadgeOverlay(state = dropBadgeState)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    viewModel.handleExternalShareIntent(
                        uris = listOf(uri),
                        mimeTypes = listOfNotNull(intent.type),
                        isMultiple = false
                    )
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    viewModel.handleExternalShareIntent(
                        uris = uris,
                        mimeTypes = listOfNotNull(intent.type),
                        isMultiple = true
                    )
                }
            }

            else -> {
                if (intent.getBooleanExtra(IncomingAlertManager.EXTRA_FROM_BACKGROUND_PROXIMITY, false)) {
                    val peerName = intent.getStringExtra(IncomingAlertManager.EXTRA_BACKGROUND_PEER_NAME)
                    val rssi = intent.getIntExtra(IncomingAlertManager.EXTRA_BACKGROUND_RSSI, -45)
                    val kind = intent.getStringExtra(IncomingAlertManager.EXTRA_BACKGROUND_KIND)
                    viewModel.handleBackgroundProximityLaunch(peerName, rssi, kind)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForegroundChanged(true)
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppForegroundChanged(false)
    }
}
