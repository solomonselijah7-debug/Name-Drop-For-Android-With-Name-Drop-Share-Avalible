package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.CardSwapState
import com.example.model.ContactCard
import com.example.model.GlowThemeStyle
import com.example.model.IncomingContactOffer
import com.example.model.PosterFontStyle
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.IosBlue
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Full-screen edge-to-edge iOS NameDrop Contact Card experience:
 * - Fills the entire screen cleanly with the user's created Profile & Full-Screen Background Profile.
 * - Supports scheduled local-date glow perimeter (Red, Green, Purple Surrounded) with zero neon harshness.
 * - Supports Receive Only, Share (mutual swap), Save Contact, Done, and Close actions.
 */
@Composable
fun FullScreenNameDropOverlay(
    offer: IncomingContactOffer,
    activeGlowTheme: GlowThemeStyle,
    isAwaitingMutualShare: Boolean,
    onReceiveOnly: () -> Unit,
    onShare: () -> Unit,
    onSaveContact: () -> Unit,
    onDone: () -> Unit
) {
    BackHandler { onDone() }

    val popAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        popAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    val p = popAnim.value
    val contact = offer.contact
    val resolvedGlow = when {
        activeGlowTheme != GlowThemeStyle.NONE -> activeGlowTheme
        contact.glowAccent != GlowThemeStyle.NONE -> contact.glowAccent
        else -> GlowThemeStyle.NONE
    }

    val glowColor = when (resolvedGlow) {
        GlowThemeStyle.RED_EDGE -> Color(0xFFFF3B30)
        GlowThemeStyle.GREEN_GLOW -> Color(0xFF34C759)
        GlowThemeStyle.PURPLE_SURROUNDED -> Color(0xFFAF52DE)
        GlowThemeStyle.NONE -> Color.Transparent
    }

    val baseBg = Color(contact.backgroundColorHex)
    val nameTint = Color(contact.nameColorHex)
    val fontFamily = when (contact.fontStyle) {
        PosterFontStyle.ROUNDED -> DisplayFontFamily
        PosterFontStyle.CLASSIC -> FontFamily.Serif
        PosterFontStyle.MONO -> FontFamily.Monospace
        PosterFontStyle.BOLD -> DisplayFontFamily
    }
    val nameWeight = when (contact.fontStyle) {
        PosterFontStyle.BOLD -> FontWeight.ExtraBold
        PosterFontStyle.CLASSIC -> FontWeight.SemiBold
        else -> FontWeight.Bold
    }

    val decodedPortrait = remember(offer.photoBytes) {
        offer.photoBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    val decodedBackdrop = remember(offer.backgroundPhotoBytes) {
        offer.backgroundPhotoBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    var showMoreInfo by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = (1f - p) * 260f
                scaleX = 0.94f + 0.06f * p
                scaleY = 0.94f + 0.06f * p
                alpha = p.coerceIn(0f, 1f)
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        baseBg,
                        baseBg.copy(alpha = 0.88f),
                        Color(0xFF0A0F1A)
                    )
                )
            )
    ) {
        // Full-Screen Background Profile Photo covering the entire screen edge-to-edge
        if (contact.fullScreenBackgroundEnabled) {
            when {
                decodedBackdrop != null -> {
                    Image(
                        bitmap = decodedBackdrop,
                        contentDescription = "Full-Screen Background Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                !contact.backgroundPhotoPath.isNullOrBlank() -> {
                    AsyncImage(
                        model = File(contact.backgroundPhotoPath),
                        contentDescription = "Full-Screen Background Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                decodedPortrait != null && offer.isTestSimulation -> {
                    Image(
                        bitmap = decodedPortrait,
                        contentDescription = "Full-Screen Background Profile",
                        contentScale = ContentScale.Crop,
                        alpha = 0.42f,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                !contact.photoPath.isNullOrBlank() -> {
                    AsyncImage(
                        model = File(contact.photoPath),
                        contentDescription = "Full-Screen Background Profile",
                        contentScale = ContentScale.Crop,
                        alpha = 0.36f,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Subtle readability scrim over full-bleed backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.34f),
                            Color.Black.copy(alpha = 0.14f),
                            Color.Black.copy(alpha = 0.62f)
                        )
                    )
                )
        )

        // Outer-edge Glow border (Red Glow / Green Glow / Purple Surrounded Glow)
        if (resolvedGlow != GlowThemeStyle.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = if (resolvedGlow == GlowThemeStyle.PURPLE_SURROUNDED) 7.dp else 5.dp,
                        color = glowColor.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(28.dp)
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: Eyebrow / Glow pill + Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = when {
                            isAwaitingMutualShare -> "Hold together to swap cards..."
                            offer.isSwappedExchange -> "Swapped Contact (${offer.rssi} dBm)"
                            offer.isReceiveOnlyMode -> "Received • Receive Only"
                            offer.isTestSimulation -> "NameDrop"
                            offer.deviceAddress == "NEARBY-BLE" -> "${offer.deviceName} (${offer.rssi} dBm)"
                            else -> "Contact Received (${offer.rssi} dBm)"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }

                IconButton(
                    onClick = onDone,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .testTag("fullscreen_card_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close NameDrop Card",
                        tint = Color.White
                    )
                }
            }

            if (resolvedGlow != GlowThemeStyle.NONE) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = CircleShape,
                    color = glowColor.copy(alpha = 0.24f)
                ) {
                    Text(
                        text = resolvedGlow.label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }

            if (offer.isAutoSavedToContacts) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF34C759).copy(alpha = 0.24f),
                    modifier = Modifier.testTag("contact_saved_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Contact saved",
                            tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (offer.savedToSystemContacts) {
                                "Contact saved"
                            } else {
                                "Contact saved locally"
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.55f))

            // Large iOS NameDrop Portrait
            Box(
                modifier = Modifier
                    .size(204.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .border(
                        width = 3.dp,
                        color = if (resolvedGlow != GlowThemeStyle.NONE) glowColor else Color.White.copy(alpha = 0.55f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    decodedPortrait != null -> {
                        Image(
                            bitmap = decodedPortrait,
                            contentDescription = "Contact Portrait",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    !contact.photoPath.isNullOrBlank() -> {
                        AsyncImage(
                            model = File(contact.photoPath),
                            contentDescription = "Contact Portrait",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = "Default Portrait",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(92.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Large Contact Poster Name
            Text(
                text = contact.displayName.ifBlank { "Your Name" },
                fontFamily = fontFamily,
                fontWeight = nameWeight,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                color = nameTint,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Phone + (i) expandable pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable { showMoreInfo = !showMoreInfo }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = contact.phoneNumber.ifBlank { "Phone Number" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Contact Details",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = showMoreInfo) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (contact.email.isNotBlank()) Text("Email: ${contact.email}", color = Color.White, fontSize = 14.sp)
                        if (contact.company.isNotBlank()) Text("Company: ${contact.company}", color = Color.White, fontSize = 14.sp)
                        if (contact.handle.isNotBlank()) Text("Handle: ${contact.handle}", color = Color.White, fontSize = 14.sp)
                        if (contact.notes.isNotBlank()) Text("Notes: ${contact.notes}", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                        if (contact.email.isBlank() && contact.company.isBlank() &&
                            contact.handle.isBlank() && contact.notes.isBlank()
                        ) {
                            Text("No extra contact fields added.", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom Frosted iOS Action Bar
            if (!offer.isTestSimulation && offer.deviceAddress != "NEARBY-BLE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onSaveContact,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("save_contact_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.94f),
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = if (offer.savedToSystemContacts) Icons.Rounded.CheckCircle else Icons.Rounded.BookmarkAdd,
                            contentDescription = null,
                            tint = if (offer.savedToSystemContacts) Color(0xFF16A34A) else Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                offer.savedToSystemContacts -> "Saved"
                                offer.isAutoSavedToContacts -> "Sync Contacts"
                                else -> "Save Contact"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = onShare,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("share_contact_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IosBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAwaitingMutualShare) "Swapping..." else "Share Back",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("done_contact_button"),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White
                    )
                ) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onReceiveOnly,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("receive_only_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.22f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Receive Only", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onShare,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("share_contact_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.95f),
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAwaitingMutualShare) "Waiting Peer..." else "Share",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Smooth iOS-style Contact Card Swap Arc Animation when BOTH phones tap Share in proximity.
 */
@Composable
fun MutualCardSwapOverlay(
    swapState: CardSwapState,
    onSwapComplete: () -> Unit
) {
    if (!swapState.isSwapping) return

    val progress = remember(swapState) { Animatable(0f) }
    LaunchedEffect(swapState) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessVeryLow
            )
        )
        onSwapComplete()
    }

    val p = progress.value
    val arcLift = sin(p * PI.toFloat()) * 110f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outgoing "My Card" arcing upward and receding
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = Color(swapState.myCard.backgroundColorHex),
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .graphicsLayer {
                    translationY = -p * 520f - arcLift * 0.4f
                    translationX = -sin(p * PI.toFloat()) * 90f
                    scaleX = 1f - 0.24f * p
                    scaleY = 1f - 0.24f * p
                    alpha = (1f - p * 0.85f).coerceIn(0f, 1f)
                }
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = swapState.myCard.displayName.ifBlank { "My Card" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text("Sending via NameDrop Swap", color = Color.White.copy(alpha = 0.78f))
            }
        }

        // Incoming "Peer Card" arcing in from top and expanding into full screen
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = Color(swapState.peerCard.backgroundColorHex),
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .graphicsLayer {
                    translationY = (1f - p) * -420f + arcLift * 0.35f
                    translationX = sin(p * PI.toFloat()) * 90f
                    scaleX = 0.78f + 0.22f * p
                    scaleY = 0.78f + 0.22f * p
                    alpha = p.coerceIn(0f, 1f)
                }
                .border(2.dp, IosBlue.copy(alpha = 0.65f), RoundedCornerShape(32.dp))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Swapping Contacts...", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = swapState.peerCard.displayName.ifBlank { swapState.peerDeviceName },
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = swapState.peerCard.phoneNumber.ifBlank { "Exchanging Contact Poster" },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp
                )
            }
        }
    }
}
