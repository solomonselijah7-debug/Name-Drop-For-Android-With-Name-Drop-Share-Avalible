package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BleProximityState
import com.example.model.DiagnosticSnapshot
import com.example.model.GeminiDiagnosisResult
import com.example.model.PermissionReadinessState
import com.example.model.WatcherHealthStatus
import com.example.model.WatcherTelemetry
import com.example.ui.theme.LocalIosColors

@Composable
fun GeminiIntelligenceSection(
    readiness: PermissionReadinessState,
    bleState: BleProximityState,
    telemetry: WatcherTelemetry,
    healthStatus: WatcherHealthStatus,
    snapshot: DiagnosticSnapshot?,
    userApiKey: String,
    diagnosisResult: GeminiDiagnosisResult,
    onRunProximityWatchdogCheck: () -> Unit,
    onCaptureDiagnosticSnapshot: () -> Unit,
    onSaveUserGeminiApiKey: (String) -> Unit,
    onClearUserGeminiApiKey: () -> Unit,
    onAskGeminiToDiagnose: () -> Unit
) {
    val ios = LocalIosColors.current
    val context = LocalContext.current

    var copiedPromptNotice by remember(snapshot?.capturedAt) { mutableStateOf(false) }
    var copiedAiPromptNotice by remember(diagnosisResult.analyzedAt) { mutableStateOf(false) }
    var showApiKeyPanel by remember(userApiKey.isNotEmpty()) { mutableStateOf(userApiKey.isNotEmpty()) }
    var keyDraft by remember(userApiKey) { mutableStateOf(userApiKey) }
    var revealKey by remember { mutableStateOf(false) }

    val statusColor = when (healthStatus) {
        WatcherHealthStatus.HEALTHY -> ios.green
        WatcherHealthStatus.WATCHING -> ios.blue
        WatcherHealthStatus.STALL_DETECTED -> ios.orange
        WatcherHealthStatus.ISSUE_DETECTED -> ios.red
    }

    IosGroupedSection(
        header = "Gemini Intelligence",
        footer = "Monitors BLE scans, Foreground Service, Overlay pop-ups, and transfer handshakes in real time. Works 100% offline; API key is optional."
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row + Live status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = ios.blue
                    )
                    Column {
                        Text(
                            text = "Diagnostic Watcher",
                            style = MaterialTheme.typography.titleMedium,
                            color = ios.labelPrimary
                        )
                        Text(
                            text = "FGS=${if (telemetry.foregroundServiceRunning) "ON" else "OFF"} • Scans=${telemetry.scanCallbackCount} • Overlay=${if (readiness.canDrawOverlays) "ON" else "OFF"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ios.labelSecondary
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.14f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when (healthStatus) {
                                WatcherHealthStatus.HEALTHY -> Icons.Rounded.CheckCircle
                                WatcherHealthStatus.STALL_DETECTED,
                                WatcherHealthStatus.ISSUE_DETECTED -> Icons.Rounded.WarningAmber
                                WatcherHealthStatus.WATCHING -> Icons.Rounded.Radar
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = telemetry.watchdogRemainingSec?.let { "Checking (${it}s)" }
                                ?: healthStatus.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }
            }

            telemetry.lastOverlayResult?.let { overlayLog ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ios.background,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Last Overlay Launch: $overlayLog",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (telemetry.lastOverlayError != null) ios.orange else ios.labelSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Watchdog & Snapshot Triggers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onRunProximityWatchdogCheck,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("gemini_test_proximity_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ios.blue,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = telemetry.watchdogRemainingSec?.let { "Watching (${it}s)..." }
                            ?: "Test Proximity Now",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onCaptureDiagnosticSnapshot,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("gemini_capture_snapshot_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "Capture Prompt",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Active Diagnostic Card & Automatic AI Fix Prompt Viewer
            if (snapshot != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = ios.background,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "AI Fix Prompt (${snapshot.capturedAt})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                            TextButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(
                                        ClipData.newPlainText("Name Drop AI Fix Prompt", snapshot.aiFixPrompt)
                                    )
                                    copiedPromptNotice = true
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("copy_ai_fix_prompt_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy AI Fix Prompt",
                                    tint = ios.blue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (copiedPromptNotice) "Copied!" else "Copy AI Fix Prompt",
                                    color = ios.blue,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Text(
                            text = snapshot.triggerReason,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ios.labelPrimary
                        )

                        snapshot.suspectedRootCauses.take(4).forEach { cause ->
                            Text(
                                text = "• $cause",
                                style = MaterialTheme.typography.bodySmall,
                                color = ios.labelSecondary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ios.card,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = snapshot.aiFixPrompt,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                color = ios.labelSecondary,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "No failures detected yet. Hold phones together or tap 'Test Proximity Now' if Name Drop does not pop up outside the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ios.labelSecondary
                )
            }

            IosHairlineDivider()

            // Optional User-Supplied Gemini API Key Diagnosis (Off by Default)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        tint = ios.labelSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Optional Gemini API Key",
                            style = MaterialTheme.typography.titleSmall,
                            color = ios.labelPrimary
                        )
                        Text(
                            text = if (userApiKey.isNotEmpty()) "Key configured locally" else "Disabled (Offline mode active)",
                            style = MaterialTheme.typography.labelSmall,
                            color = ios.labelSecondary
                        )
                    }
                }
                Switch(
                    checked = showApiKeyPanel,
                    onCheckedChange = { enabled ->
                        showApiKeyPanel = enabled
                        if (!enabled && userApiKey.isNotEmpty()) {
                            keyDraft = ""
                            onClearUserGeminiApiKey()
                        }
                    },
                    modifier = Modifier.testTag("gemini_api_key_toggle")
                )
            }

            AnimatedVisibility(visible = showApiKeyPanel) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ios.background, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text("Your Gemini API Key (Optional)") },
                        placeholder = { Text("Paste key (stored only on this device)") },
                        singleLine = true,
                        visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealKey = !revealKey }) {
                                Icon(
                                    imageVector = if (revealKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (revealKey) "Hide key" else "Show key"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_api_key_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSaveUserGeminiApiKey(keyDraft) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("save_gemini_key_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save Key")
                        }
                        if (userApiKey.isNotEmpty() || keyDraft.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    keyDraft = ""
                                    onClearUserGeminiApiKey()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Clear API Key",
                                    tint = ios.red
                                )
                            }
                        }
                        Button(
                            onClick = {
                                if (keyDraft.trim() != userApiKey.trim() && keyDraft.isNotBlank()) {
                                    onSaveUserGeminiApiKey(keyDraft)
                                }
                                onAskGeminiToDiagnose()
                            },
                            enabled = !diagnosisResult.isAnalyzing && (userApiKey.isNotBlank() || keyDraft.isNotBlank()),
                            modifier = Modifier
                                .weight(1.4f)
                                .heightIn(min = 48.dp)
                                .testTag("ask_gemini_diagnose_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ios.blue)
                        ) {
                            if (diagnosisResult.isAnalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Diagnosing...")
                            } else {
                                Text("Ask Gemini to Diagnose", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    diagnosisResult.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = ios.red
                        )
                    }

                    if (diagnosisResult.plainLanguageDiagnosis.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ios.card,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Gemini Plain-Language Diagnosis",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = ios.blue
                                )
                                Text(
                                    text = diagnosisResult.plainLanguageDiagnosis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ios.labelPrimary
                                )
                                if (diagnosisResult.updatedFixPrompt.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(
                                                ClipData.newPlainText(
                                                    "Updated AI Fix Prompt",
                                                    diagnosisResult.updatedFixPrompt
                                                )
                                            )
                                            copiedAiPromptNotice = true
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = null,
                                            tint = ios.blue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (copiedAiPromptNotice) "Updated Prompt Copied!" else "Copy Updated AI Fix Prompt",
                                            color = ios.blue
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
