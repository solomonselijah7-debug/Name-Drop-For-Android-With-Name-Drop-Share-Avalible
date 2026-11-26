package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.model.ContactCard
import com.example.model.CountryProfile
import com.example.ui.components.ContactPosterCard
import com.example.ui.components.IosGroupedSection
import com.example.ui.theme.LocalIosColors

@Composable
fun CountryVerifySetupSheet(
    initialCard: ContactCard,
    countryProfile: CountryProfile,
    isVerifyingCountry: Boolean,
    onBackToPermissions: () -> Unit,
    onVerifyCountry: (String, (String) -> Unit) -> Unit,
    onSaveContactSetup: (String, String, Uri?) -> Unit
) {
    val ios = LocalIosColors.current
    var displayName by remember(initialCard.displayName) { mutableStateOf(initialCard.displayName) }
    var phoneNumber by remember(initialCard.phoneNumber) {
        mutableStateOf(
            initialCard.phoneNumber.ifBlank {
                if (countryProfile.isVerified) "${countryProfile.dialPrefix} " else ""
            }
        )
    }
    var pickedPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pickedPhotoUri = uri
    }

    val previewCard = initialCard.copy(
        displayName = displayName.ifBlank { "Your Name" },
        phoneNumber = phoneNumber.ifBlank { "${countryProfile.dialPrefix} 000 0000" }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ios.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackToPermissions,
                    modifier = Modifier.testTag("back_to_permissions_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Permissions",
                        tint = ios.blue
                    )
                }
                Column {
                    Text(
                        text = "STEP 2 OF 2 • COUNTRY & CONTACT CARD",
                        style = MaterialTheme.typography.labelSmall,
                        color = ios.blue
                    )
                    Text(
                        text = "Set Up Your Contact",
                        style = MaterialTheme.typography.headlineLarge,
                        color = ios.labelPrimary
                    )
                }
            }

            // Country Verification Card
            IosGroupedSection(
                header = "Country & Local 12 PM Schedule",
                footer = "Verifying your country sets your dialing prefix and unlocks Gemini daily updates at 12:00 PM local time."
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = ios.blue
                            )
                            Column {
                                Text(
                                    text = if (countryProfile.isVerified) {
                                        "${countryProfile.countryName} (${countryProfile.dialPrefix})"
                                    } else {
                                        "Country Not Verified Yet"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ios.labelPrimary
                                )
                                Text(
                                    text = countryProfile.zoneId,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ios.labelSecondary
                                )
                            }
                        }

                        Button(
                            onClick = {
                                onVerifyCountry(phoneNumber) { updatedPhone ->
                                    phoneNumber = updatedPhone
                                }
                            },
                            enabled = !isVerifyingCountry,
                            shape = CircleShape,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("verify_country_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (countryProfile.isVerified) ios.green else ios.blue,
                                contentColor = Color.White
                            )
                        ) {
                            if (isVerifyingCountry) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                if (countryProfile.isVerified) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(if (countryProfile.isVerified) "Verified" else "Verify")
                            }
                        }
                    }
                }
            }

            // Contact Details + Photo Upload
            IosGroupedSection(header = "Your Contact Details") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_name_input")
                    )

                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text("Phone Number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_phone_input")
                    )

                    TextButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("setup_pick_photo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                            tint = ios.blue
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (pickedPhotoUri != null || initialCard.photoPath != null) {
                                "Change Contact Card Photo"
                            } else {
                                "Upload Contact Card Photo"
                            },
                            color = ios.blue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Live Contact Poster Preview
            Text(
                text = "LIVE CONTACT POSTER PREVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = ios.labelSecondary,
                modifier = Modifier.padding(start = 12.dp)
            )
            ContactPosterCard(
                contact = previewCard,
                headerLabel = "Share Your Contact",
                onPortraitClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )

            Button(
                onClick = { onSaveContactSetup(displayName, phoneNumber, pickedPhotoUri) },
                enabled = displayName.isNotBlank() && phoneNumber.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .testTag("put_on_contact_card_button"),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ios.blue,
                    contentColor = Color.White
                )
            ) {
                Text("Put on My Contact Card", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
