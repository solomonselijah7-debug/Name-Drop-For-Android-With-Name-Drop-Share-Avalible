package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.model.ContactCard
import com.example.model.GlowThemeStyle
import com.example.model.PosterFontStyle
import com.example.ui.components.ContactPosterCard
import com.example.ui.components.IosCapsuleSwitch
import com.example.ui.components.IosGroupedSection
import com.example.ui.components.IosHairlineDivider
import com.example.ui.theme.LocalIosColors
import com.example.ui.theme.PosterNameColors
import com.example.ui.theme.PosterPaletteColors

@Composable
fun ContactCustomizerSheet(
    currentCard: ContactCard,
    onDismiss: () -> Unit,
    onSave: (ContactCard, Uri?, Uri?) -> Unit
) {
    BackHandler { onDismiss() }

    val ios = LocalIosColors.current
    var displayName by remember { mutableStateOf(currentCard.displayName) }
    var phone by remember { mutableStateOf(currentCard.phoneNumber) }
    var email by remember { mutableStateOf(currentCard.email) }
    var company by remember { mutableStateOf(currentCard.company) }
    var handle by remember { mutableStateOf(currentCard.handle) }
    var notes by remember { mutableStateOf(currentCard.notes) }
    var bgHex by remember { mutableLongStateOf(currentCard.backgroundColorHex) }
    var nameHex by remember { mutableLongStateOf(currentCard.nameColorHex) }
    var fontStyle by remember { mutableStateOf(currentCard.fontStyle) }
    var fullScreenBgEnabled by remember { mutableStateOf(currentCard.fullScreenBackgroundEnabled) }
    var backgroundPhotoPath by remember { mutableStateOf(currentCard.backgroundPhotoPath) }
    var backgroundPhotoSha by remember { mutableStateOf(currentCard.backgroundPhotoSha256) }
    var glowAccent by remember { mutableStateOf(currentCard.glowAccent) }
    var pickedPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var pickedBgUri by remember { mutableStateOf<Uri?>(null) }

    val portraitPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pickedPhotoUri = uri
    }

    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedBgUri = uri
            fullScreenBgEnabled = true
        }
    }

    val draft = currentCard.copy(
        displayName = displayName,
        phoneNumber = phone,
        email = email,
        company = company,
        handle = handle,
        notes = notes,
        backgroundColorHex = bgHex,
        nameColorHex = nameHex,
        fontStyle = fontStyle,
        backgroundPhotoPath = backgroundPhotoPath,
        backgroundPhotoSha256 = backgroundPhotoSha,
        fullScreenBackgroundEnabled = fullScreenBgEnabled,
        glowAccent = glowAccent
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(ios.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = ios.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // iOS Sheet Grabber + Cancel / Title / Done bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ios.card)
                    .padding(top = 8.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(ios.separator)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("customizer_cancel_button")
                    ) {
                        Text("Cancel", color = ios.blue)
                    }
                    Text(
                        text = "Profile & Poster",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ios.labelPrimary
                    )
                    TextButton(
                        onClick = { onSave(draft, pickedPhotoUri, pickedBgUri) },
                        modifier = Modifier.testTag("customizer_done_button")
                    ) {
                        Text("Done", color = ios.blue, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                ContactPosterCard(
                    contact = draft,
                    headerLabel = "Tap Portrait to Customize Photo",
                    previewPortraitUri = pickedPhotoUri,
                    previewBackgroundUri = pickedBgUri,
                    onPortraitClick = {
                        portraitPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            portraitPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("customize_photo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                            tint = ios.blue
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Portrait Photo", color = ios.blue, fontWeight = FontWeight.SemiBold)
                    }

                    TextButton(
                        onClick = {
                            backgroundPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("customize_background_photo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wallpaper,
                            contentDescription = null,
                            tint = ios.blue
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Background Photo", color = ios.blue, fontWeight = FontWeight.SemiBold)
                    }
                }

                IosGroupedSection(
                    header = "Full-Screen Background Profile",
                    footer = "Covers the entire Name Drop screen edge-to-edge when exchanging contacts."
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Full-Screen Background Profile",
                                style = MaterialTheme.typography.titleMedium,
                                color = ios.labelPrimary
                            )
                            Text(
                                text = if (pickedBgUri != null || !backgroundPhotoPath.isNullOrBlank()) {
                                    "Custom background image active"
                                } else {
                                    "Using full-bleed color backdrop"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = ios.labelSecondary
                            )
                        }
                        IosCapsuleSwitch(
                            checked = fullScreenBgEnabled,
                            onCheckedChange = { fullScreenBgEnabled = it },
                            testTag = "fullscreen_bg_switch"
                        )
                    }

                    if (pickedBgUri != null || !backgroundPhotoPath.isNullOrBlank()) {
                        IosHairlineDivider()
                        TextButton(
                            onClick = {
                                pickedBgUri = null
                                backgroundPhotoPath = null
                                backgroundPhotoSha = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clear_background_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = ios.red
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Remove Custom Background Photo", color = ios.red)
                        }
                    }
                }

                IosGroupedSection(header = "Poster Style, Palette & Glow Accent") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Background Color",
                            style = MaterialTheme.typography.labelLarge,
                            color = ios.labelPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PosterPaletteColors.forEach { hex ->
                                val selected = hex == bgHex
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(hex))
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) ios.blue else ios.separator,
                                            shape = CircleShape
                                        )
                                        .clickable { bgHex = hex },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Profile Glow Accent",
                            style = MaterialTheme.typography.labelLarge,
                            color = ios.labelPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlowThemeStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = glowAccent == style,
                                    onClick = { glowAccent = style },
                                    label = { Text(style.label) }
                                )
                            }
                        }

                        Text(
                            text = "Name Font & Color",
                            style = MaterialTheme.typography.labelLarge,
                            color = ios.labelPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PosterFontStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = fontStyle == style,
                                    onClick = { fontStyle = style },
                                    label = { Text(style.label) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PosterNameColors.forEach { hex ->
                                val selected = hex == nameHex
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(hex))
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) ios.blue else ios.separator,
                                            shape = CircleShape
                                        )
                                        .clickable { nameHex = hex }
                                )
                            }
                        }
                    }
                }

                IosGroupedSection(header = "Identity & Optional Fields") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Display Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Phone Number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email (Optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = company,
                            onValueChange = { company = it },
                            label = { Text("Company (Optional)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = handle,
                            onValueChange = { handle = it },
                            label = { Text("Social Handle (Optional)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Notes (Optional)") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
