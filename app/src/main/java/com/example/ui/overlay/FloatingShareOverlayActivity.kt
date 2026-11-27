package com.example.ui.overlay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.NameDropApp
import com.example.ai.GeminiDiagnosticWatcher
import com.example.ble.BlePermissions
import com.example.ble.DeviceDiagnostics
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosRed
import com.example.ui.theme.NameDropTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Translucent floating popup activity for Gallery / external app "NameDrop Share".
 *
 * Guarantees immediate visible UI on frame 0 whenever tapped from any Android / EMUI Gallery:
 * - Extracts URIs from EXTRA_STREAM, ClipData, and Intent.data.
 * - Never closes silently when a URI is missing/unreadable — displays a visible floating error card.
 * - Verifies TYPE_APPLICATION_OVERLAY attach + visible bounds before closing this translucent host,
 *   and on Huawei/Honor EMUI keeps this translucent floating sheet anchored so EMUI Dropzone cannot
 *   hide the popup when the Activity exits.
 */
class FloatingShareOverlayActivity : ComponentActivity() {

    private data class ExtractedShareUris(
        val uris: List<Uri>,
        val clipDataCount: Int
    )

    private var stagingJob: Job? = null
    private var showActivityFallbackCard by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NameDropTheme {
                val state by FloatingShareOverlayCoordinator.uiState.collectAsStateWithLifecycle()
                if (showActivityFallbackCard && state.isActive) {
                    BackHandler {
                        FloatingShareOverlayCoordinator.dismissSession(applicationContext)
                    }
                    FloatingShareCardContent(
                        state = state,
                        onBackdropTap = {
                            if (FloatingShareOverlayCoordinator.shouldAllowBackdropDismiss(state)) {
                                FloatingShareOverlayCoordinator.dismissSession(applicationContext)
                            }
                        },
                        onCancel = {
                            FloatingShareOverlayCoordinator.dismissSession(applicationContext)
                        },
                        onReceiveOnly = {
                            FloatingShareOverlayCoordinator.triggerReceiveOnlyWaiting(applicationContext)
                        },
                        onShareOrSetup = {
                            if (state.needsBluetoothSetup || (state.isError && state.itemCount == 0)) {
                                launchFullApp()
                            } else {
                                FloatingShareOverlayCoordinator.triggerShareNow(applicationContext)
                            }
                        },
                        onOpenApp = {
                            launchFullApp()
                        }
                    )
                }
            }
        }

        processShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processShareIntent(intent)
    }

    private fun launchFullApp() {
        FloatingShareOverlayCoordinator.dismissSession(applicationContext)
        startActivity(
            Intent(this@FloatingShareOverlayActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun processShareIntent(intent: Intent?) {
        val extracted = extractUris(intent)
        val bleSnapshot = NameDropApp.bleManager.state.value

        GeminiDiagnosticWatcher.onShareIntentReceived(
            context = applicationContext,
            action = intent?.action,
            mimeType = intent?.type,
            uriCount = extracted.uris.size,
            clipDataCount = extracted.clipDataCount,
            bleState = bleSnapshot
        )

        // Guarantee visible floating popup immediately on frame 0
        showActivityFallbackCard = true
        FloatingShareOverlayCoordinator.setDismissCallback {
            finishNoAnim()
        }

        if (extracted.uris.isEmpty()) {
            FloatingShareOverlayCoordinator.showShareErrorState(
                context = applicationContext,
                message = "No readable photo or video received",
                peerLine = "Tap Open App or try sharing from Gallery again"
            )
            return
        }

        val mimeTypes = listOfNotNull(intent?.type)
        val isMultiple = intent?.action == Intent.ACTION_SEND_MULTIPLE
        val myGen = FloatingShareOverlayCoordinator.beginStagingImmediate(
            context = applicationContext,
            uris = extracted.uris,
            hintMime = mimeTypes.firstOrNull(),
            isMultiple = isMultiple
        )

        stagingJob?.cancel()
        stagingJob = lifecycleScope.launch {
            val staged = FloatingShareOverlayCoordinator.stageAndStartSession(
                context = this@FloatingShareOverlayActivity,
                uris = extracted.uris,
                mimeTypes = mimeTypes,
                isMultiple = isMultiple,
                expectedGeneration = myGen
            )

            val latestBleSnapshot = NameDropApp.bleManager.state.value
            val canDraw = BlePermissions.canDrawOverlays(this@FloatingShareOverlayActivity)
            val isHuawei = DeviceDiagnostics.isHuaweiOrHonor()

            if (staged && canDraw && !isHuawei) {
                val overlayResult = FloatingShareOverlayWindow.show(applicationContext)
                if (overlayResult.isSuccess) {
                    delay(260L)
                    val attachedVisible = FloatingShareOverlayWindow.isAttachedAndVisible()
                    GeminiDiagnosticWatcher.onOverlayAttachVerified(
                        context = applicationContext,
                        launchMode = "TYPE_APPLICATION_OVERLAY_SHARE",
                        attached = attachedVisible,
                        hasVisibleBounds = attachedVisible,
                        detail = if (attachedVisible) {
                            "System floating share overlay verified attached & visible"
                        } else {
                            "TYPE_APPLICATION_OVERLAY attach/bounds check failed; keeping floating dialog"
                        },
                        bleState = latestBleSnapshot
                    )
                    if (attachedVisible && FloatingShareOverlayCoordinator.uiState.value.isActive) {
                        FloatingShareOverlayCoordinator.setDismissCallback(null)
                        showActivityFallbackCard = false
                        finishNoAnim()
                        return@launch
                    } else {
                        FloatingShareOverlayWindow.dismiss(applicationContext)
                        FloatingShareOverlayCoordinator.setFallbackNotice(
                            "System overlay did not render on ${Build.MANUFACTURER} ${Build.MODEL}; using floating popup."
                        )
                    }
                } else {
                    val err = overlayResult.exceptionOrNull()?.message ?: "WindowManager overlay rejected by OEM"
                    GeminiDiagnosticWatcher.onOverlayAttachVerified(
                        context = applicationContext,
                        launchMode = "TYPE_APPLICATION_OVERLAY_SHARE",
                        attached = false,
                        hasVisibleBounds = false,
                        detail = "System overlay failed ($err); keeping floating dialog fallback",
                        bleState = latestBleSnapshot
                    )
                    FloatingShareOverlayCoordinator.setFallbackNotice(
                        "System overlay blocked on ${Build.MANUFACTURER} ${Build.MODEL}; using floating popup."
                    )
                }
            } else {
                val mode = when {
                    isHuawei && canDraw -> "HUAWEI_EMUI_TRANSLUCENT_FLOATING_SHEET"
                    else -> "TRANSLUCENT_FLOATING_ACTIVITY"
                }
                val detail = when {
                    !staged -> "Media staging error — keeping visible floating error card"
                    isHuawei -> "Anchored translucent floating sheet on ${Build.MANUFACTURER} ${Build.MODEL} to prevent EMUI Dropzone hiding"
                    else -> "Display Over Other Apps disabled — showing floating dialog over current screen"
                }
                GeminiDiagnosticWatcher.onOverlayAttachVerified(
                    context = applicationContext,
                    launchMode = mode,
                    attached = staged,
                    hasVisibleBounds = staged,
                    detail = detail,
                    bleState = latestBleSnapshot
                )
            }

            showActivityFallbackCard = true
            FloatingShareOverlayCoordinator.setDismissCallback {
                finishNoAnim()
            }
        }
    }

    private fun extractUris(intent: Intent?): ExtractedShareUris {
        if (intent == null) return ExtractedShareUris(emptyList(), 0)
        val collected = LinkedHashSet<Uri>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { collected.add(it) }
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.forEach {
                collected.add(it)
            }
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { collected.add(it) }
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach {
                collected.add(it)
            }
        }

        val clipData = intent.clipData
        val clipCount = clipData?.itemCount ?: 0
        if (clipData != null) {
            for (idx in 0 until clipCount) {
                clipData.getItemAt(idx)?.uri?.let { collected.add(it) }
            }
        }

        intent.data?.let { collected.add(it) }

        return ExtractedShareUris(
            uris = collected.toList(),
            clipDataCount = clipCount
        )
    }

    @Suppress("DEPRECATION")
    private fun finishNoAnim() {
        if (isFinishing || isDestroyed) return
        finish()
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun FloatingShareCardContent(
    state: OverlayShareUiState,
    onBackdropTap: () -> Unit,
    onCancel: () -> Unit,
    onReceiveOnly: () -> Unit,
    onShareOrSetup: () -> Unit,
    onOpenApp: () -> Unit
) {
    val isDone = state.isSharedComplete || state.isReceiveCompleted
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBackdropTap
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            shadowElevation = 18.dp,
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = if (isDone) IosGreen.copy(alpha = 0.16f) else IosBlue.copy(alpha = 0.14f)
                            )
                            drawCircle(
                                color = IosBlue.copy(alpha = 0.2f),
                                style = Stroke(width = 4.dp.toPx())
                            )
                            if (state.isSharing || state.isArmed || state.isReceiveOnlyWaiting || isDone) {
                                drawArc(
                                    color = if (isDone) IosGreen else IosBlue,
                                    startAngle = -90f,
                                    sweepAngle = if (isDone) 360f else (state.progress * 360f).coerceAtLeast(28f),
                                    useCenter = false,
                                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }
                        Icon(
                            imageVector = when {
                                isDone -> Icons.Rounded.CheckCircle
                                state.isReceiveOnlyWaiting -> Icons.Rounded.HourglassTop
                                state.isSharing -> Icons.Rounded.AutoAwesome
                                else -> Icons.Rounded.BluetoothSearching
                            },
                            contentDescription = null,
                            tint = if (isDone) IosGreen else IosBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827)
                        )
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                isDone -> IosGreen
                                state.isError -> IosRed
                                else -> IosBlue
                            }
                        )
                    }

                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("floating_share_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close share popup",
                            tint = Color(0xFF6C6C70)
                        )
                    }
                }

                state.fallbackNotice?.let { notice ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF7ED),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF9A3412),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                state.previewBitmap?.let { bmp ->
                    val imageBmp = remember(bmp) { bmp.asImageBitmap() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = imageBmp,
                            contentDescription = "Shared media preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (state.isVideo) {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircle,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                }

                Text(
                    text = state.peerLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onReceiveOnly,
                        enabled = !state.isSharing && !state.isSharedComplete,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("floating_share_receive_only_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                state.isReceiveCompleted -> Color(0xFFDCFCE7)
                                state.isReceiveOnlyWaiting -> Color(0xFFDBEAFE)
                                else -> Color(0xFFE8F2FF)
                            },
                            contentColor = if (state.isReceiveCompleted) Color(0xFF15803D) else IosBlue
                        )
                    ) {
                        Text(
                            text = FloatingShareOverlayCoordinator.resolveReceiveActionLabel(state),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onShareOrSetup,
                        enabled = !state.isSharing && !state.isSharedComplete && !state.isStaging,
                        modifier = Modifier
                            .weight(1.25f)
                            .heightIn(min = 48.dp)
                            .testTag("floating_share_action_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isSharedComplete) IosGreen else IosBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = FloatingShareOverlayCoordinator.resolveShareActionLabel(state),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("floating_share_cancel_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF2F2F7),
                            contentColor = Color(0xFF111827)
                        )
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
                    }

                    if (state.canOpenFullApp || state.isError || state.needsBluetoothSetup || state.isReceiveCompleted) {
                        Button(
                            onClick = onOpenApp,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("floating_share_open_app_button"),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE5E7EB),
                                contentColor = Color(0xFF111827)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open App", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
