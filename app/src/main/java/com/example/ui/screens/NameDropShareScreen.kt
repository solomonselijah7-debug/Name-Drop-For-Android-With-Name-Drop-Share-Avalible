package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import com.example.data.MediaStorageHelper
import com.example.model.BleProximityState
import com.example.model.PhotoTransferState
import com.example.model.TransferPhase
import com.example.ui.components.IosCapsuleSwitch
import com.example.ui.components.IosGroupedSection
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosIconBadge
import com.example.ui.components.PhoneTapIllustration
import com.example.ui.theme.IosIndigo
import com.example.ui.theme.LocalIosColors

@Composable
fun NameDropShareScreen(
    isShareEnabled: Boolean,
    bleState: BleProximityState,
    selectedPhotoUri: Uri?,
    transferState: PhotoTransferState,
    contentPadding: PaddingValues,
    onToggleShare: (Boolean) -> Unit,
    onStartReceiveListening: () -> Unit,
    onStopReceiveListening: () -> Unit,
    onSelectPhoto: (Uri?) -> Unit,
    onStartSingleShare: () -> Unit,
    onStartGroupShare: () -> Unit,
    onResetTransfer: () -> Unit
) {
    val ios = LocalIosColors.current
    val context = LocalContext.current

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        onSelectPhoto(uri)
    }

    val isSelectedVideo = remember(selectedPhotoUri) {
        selectedPhotoUri != null && MediaStorageHelper.isVideoUri(context, selectedPhotoUri)
    }
    val selectedVideoThumb = remember(selectedPhotoUri, isSelectedVideo) {
        if (isSelectedVideo && selectedPhotoUri != null) {
            MediaStorageHelper.extractVideoThumbnail(context, selectedPhotoUri)?.asImageBitmap()
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ios.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Name Drop Share",
                style = MaterialTheme.typography.displayLarge,
                color = ios.labelPrimary
            )

            Text(
                text = "Pure-BLE sharing for photos, videos, and contacts. Tap Receive to listen or Share to send when two phones meet.",
                style = MaterialTheme.typography.bodyMedium,
                color = ios.labelSecondary
            )

            IosGroupedSection(header = "Sharing Hub") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PhoneTapIllustration()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IosIconBadge(
                            icon = Icons.Rounded.Share,
                            badgeColor = if (isShareEnabled) ios.green else ios.blue
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Sharing",
                                style = MaterialTheme.typography.titleMedium,
                                color = ios.labelPrimary
                            )
                            Text(
                                text = if (isShareEnabled) {
                                    "Active • Ready to send & receive photos/videos"
                                } else {
                                    "Plays perimeter sweep & unlocks controls"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = ios.labelSecondary
                            )
                        }
                        IosCapsuleSwitch(
                            checked = isShareEnabled,
                            onCheckedChange = onToggleShare,
                            testTag = "share_enable_switch"
                        )
                    }
                }
            }

            // Always allow tapping Receive or Select Photo/Video directly
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onStartReceiveListening,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("receive_nearby_photo_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (bleState.isReceiveListening) ios.green else ios.card,
                            contentColor = if (bleState.isReceiveListening) Color.White else ios.labelPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = if (bleState.isReceiveListening) Color.White else ios.blue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (bleState.isReceiveListening) "Listening..." else "Receive",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            if (!isShareEnabled) onToggleShare(true)
                            mediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("pick_photo_to_share_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ios.blue,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Photo / Video", fontWeight = FontWeight.SemiBold)
                    }
                }

                // Active Receive Listening Card ("Waiting for nearby device...")
                AnimatedVisibility(visible = bleState.isReceiveListening) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = ios.card,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("receive_waiting_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = ios.blue,
                                strokeWidth = 3.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Waiting for nearby device...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ios.labelPrimary
                                )
                                Text(
                                    text = bleState.nearestDeviceName?.let {
                                        "Nearby signal: $it (${bleState.smoothedRssi ?: bleState.nearestRssi ?: "--"} dBm) • Advertising active"
                                    } ?: "Bluetooth scanning & advertising active. Hold sending phone close.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ios.labelSecondary
                                )
                            }
                            TextButton(
                                onClick = onStopReceiveListening,
                                modifier = Modifier.testTag("stop_receive_listening_button")
                            ) {
                                Text("Stop", color = ios.red)
                            }
                        }
                    }
                }

                // Selected Photo / Video Preview + Share Nearby / Group Share
                if (selectedPhotoUri != null) {
                    IosGroupedSection(
                        header = if (isSelectedVideo) "Selected Video Ready" else "Selected Photo Ready",
                        footer = "Tap Share, then hold this phone against another phone. The receiver prompts Accept and saves to Gallery."
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.Black)
                            ) {
                                if (isSelectedVideo && selectedVideoThumb != null) {
                                    Image(
                                        bitmap = selectedVideoThumb,
                                        contentDescription = "Selected video thumbnail",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    AsyncImage(
                                        model = selectedPhotoUri,
                                        contentDescription = "Selected media to share",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                if (isSelectedVideo) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.65f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayCircle,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "VIDEO",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = onStartSingleShare,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .testTag("start_single_photo_share_button"),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = ios.blue)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSelectedVideo) "Share Video Nearby" else "Share Photo Nearby",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            IosHairlineDivider()

                            Button(
                                onClick = onStartGroupShare,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .testTag("start_group_photo_share_button"),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = IosIndigo)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Groups,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Step-by-Step Group Share", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Transfer status & completion card
            AnimatedVisibility(visible = transferState.phase != TransferPhase.IDLE && !bleState.isReceiveListening) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = ios.card,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transfer_status_card")
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (transferState.phase) {
                            TransferPhase.SAVED -> Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = ios.green,
                                modifier = Modifier.size(40.dp)
                            )

                            TransferPhase.HOLD_TO_SHARE,
                            TransferPhase.WAITING_NEARBY,
                            TransferPhase.WAITING_ACCEPTANCE -> Icon(
                                imageVector = Icons.Rounded.BluetoothSearching,
                                contentDescription = null,
                                tint = ios.blue,
                                modifier = Modifier.size(38.dp)
                            )

                            else -> Unit
                        }

                        Text(
                            text = transferState.statusText,
                            style = MaterialTheme.typography.titleMedium,
                            color = ios.labelPrimary
                        )

                        if (transferState.phase == TransferPhase.TRANSFERRING) {
                            LinearProgressIndicator(
                                progress = { transferState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                                color = ios.blue
                            )
                        }

                        transferState.savedGalleryUri?.let { savedUri ->
                            if (!transferState.isVideo) {
                                AsyncImage(
                                    model = savedUri,
                                    contentDescription = "Saved media preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                )
                            }
                        }

                        if (transferState.phase == TransferPhase.SAVED ||
                            transferState.phase == TransferPhase.DECLINED ||
                            transferState.phase == TransferPhase.ERROR ||
                            transferState.phase == TransferPhase.HOLD_TO_SHARE ||
                            transferState.phase == TransferPhase.WAITING_ACCEPTANCE
                        ) {
                            Button(
                                onClick = onResetTransfer,
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("photo_transfer_done_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (transferState.phase == TransferPhase.SAVED) ios.blue else ios.background,
                                    contentColor = if (transferState.phase == TransferPhase.SAVED) Color.White else ios.labelPrimary
                                )
                            ) {
                                Text(
                                    text = if (transferState.phase == TransferPhase.HOLD_TO_SHARE ||
                                        transferState.phase == TransferPhase.WAITING_ACCEPTANCE
                                    ) "Cancel" else "Done",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(104.dp))
        }
    }
}
