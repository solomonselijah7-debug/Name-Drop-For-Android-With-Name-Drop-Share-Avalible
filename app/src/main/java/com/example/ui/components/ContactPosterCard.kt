package com.example.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.ContactCard
import com.example.model.GlowThemeStyle
import com.example.model.PosterFontStyle
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.IosBlue
import java.io.File

/**
 * Contact Poster Card supporting portrait + full-bleed background profile artwork,
 * expandable (i) contact details, Receive Only / Share / Save Contact / Done actions,
 * and scheduled Glow highlights (Red Glow, Green Glow, Purple Surrounded Glow).
 */
@Composable
fun ContactPosterCard(
    contact: ContactCard,
    headerLabel: String,
    photoBytes: ByteArray? = null,
    backgroundPhotoBytes: ByteArray? = null,
    previewPortraitUri: Uri? = null,
    previewBackgroundUri: Uri? = null,
    isIncomingReceived: Boolean = false,
    hasRedEdgeGlowUp: Boolean = false,
    glowTheme: GlowThemeStyle = GlowThemeStyle.NONE,
    onPortraitClick: (() -> Unit)? = null,
    onReceiveOnly: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onSaveContact: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMoreInfo by remember { mutableStateOf(false) }

    val resolvedGlow = when {
        glowTheme != GlowThemeStyle.NONE -> glowTheme
        hasRedEdgeGlowUp -> GlowThemeStyle.RED_EDGE
        else -> contact.glowAccent
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

    val decodedBitmap = remember(photoBytes) {
        photoBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                .getOrNull()
        }
    }
    val decodedBgBitmap = remember(backgroundPhotoBytes) {
        backgroundPhotoBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                .getOrNull()
        }
    }

    val shape = RoundedCornerShape(30.dp)
    val outerModifier = modifier
        .fillMaxWidth()
        .shadow(18.dp, shape)
        .then(
            if (resolvedGlow != GlowThemeStyle.NONE) {
                Modifier
                    .border(
                        width = if (resolvedGlow == GlowThemeStyle.PURPLE_SURROUNDED) 5.5.dp else 4.dp,
                        color = glowColor,
                        shape = shape
                    )
                    .padding(1.dp)
            } else {
                Modifier.border(0.75.dp, Color.White.copy(alpha = 0.22f), shape)
            }
        )
        .clip(shape)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    baseBg,
                    baseBg.copy(alpha = 0.92f),
                    Color(0xFF0B0F19)
                )
            )
        )

    Box(modifier = outerModifier) {
        // Full-Screen Background Profile Photo covering the entire card cleanly
        if (contact.fullScreenBackgroundEnabled) {
            when {
                previewBackgroundUri != null -> {
                    AsyncImage(
                        model = previewBackgroundUri,
                        contentDescription = "Full-Screen Background Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }

                decodedBgBitmap != null -> {
                    Image(
                        bitmap = decodedBgBitmap,
                        contentDescription = "Full-Screen Background Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }

                !contact.backgroundPhotoPath.isNullOrBlank() -> {
                    AsyncImage(
                        model = File(contact.backgroundPhotoPath),
                        contentDescription = "Full-Screen Background Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }

            if (previewBackgroundUri != null || decodedBgBitmap != null || !contact.backgroundPhotoPath.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.30f),
                                    Color.Black.copy(alpha = 0.18f),
                                    Color.Black.copy(alpha = 0.56f)
                                )
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (resolvedGlow != GlowThemeStyle.NONE) {
                Surface(
                    shape = CircleShape,
                    color = glowColor.copy(alpha = 0.24f),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Glow Up • ${resolvedGlow.label}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = headerLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.84f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            // Large centered circular portrait with subtle glass rim
            Box(
                modifier = Modifier
                    .size(164.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(
                        width = 2.5.dp,
                        color = if (resolvedGlow != GlowThemeStyle.NONE) glowColor else Color.White.copy(alpha = 0.45f),
                        shape = CircleShape
                    )
                    .then(
                        if (onPortraitClick != null) {
                            Modifier.clickable { onPortraitClick() }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    previewPortraitUri != null -> {
                        AsyncImage(
                            model = previewPortraitUri,
                            contentDescription = "Contact Poster Portrait",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    decodedBitmap != null -> {
                        Image(
                            bitmap = decodedBitmap,
                            contentDescription = "Contact Poster Portrait",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    !contact.photoPath.isNullOrBlank() -> {
                        AsyncImage(
                            model = File(contact.photoPath),
                            contentDescription = "Contact Poster Portrait",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = "Default Portrait",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(72.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = contact.displayName.ifBlank { "Your Name" },
                fontFamily = fontFamily,
                fontWeight = nameWeight,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                color = nameTint,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { showMoreInfo = !showMoreInfo }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = contact.phoneNumber.ifBlank { "Add phone number" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Show extra contact details",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = showMoreInfo) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.35f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (contact.email.isNotBlank()) {
                            Text("Email: ${contact.email}", color = Color.White, fontSize = 13.sp)
                        }
                        if (contact.company.isNotBlank()) {
                            Text("Company: ${contact.company}", color = Color.White, fontSize = 13.sp)
                        }
                        if (contact.handle.isNotBlank()) {
                            Text("Handle: ${contact.handle}", color = Color.White, fontSize = 13.sp)
                        }
                        if (contact.notes.isNotBlank()) {
                            Text("Notes: ${contact.notes}", color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp)
                        }
                        if (contact.email.isBlank() && contact.company.isBlank() &&
                            contact.handle.isBlank() && contact.notes.isBlank()
                        ) {
                            Text(
                                "No additional contact fields provided.",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            if (isIncomingReceived) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onSaveContact?.invoke() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("save_contact_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.94f),
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Contact", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onDone?.invoke() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("done_contact_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IosBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Done", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (onReceiveOnly != null && onShare != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onReceiveOnly,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("receive_only_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.94f),
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Receive Only", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onShare,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("share_contact_button"),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.94f),
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Share", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
