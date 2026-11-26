package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ContactCard
import com.example.model.CountryProfile
import com.example.model.GlowPreviewMode
import com.example.model.GlowThemeStyle
import com.example.model.PosterFontStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "namedrop_prefs")

class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val CONTACT_SETUP_COMPLETED = booleanPreferencesKey("contact_setup_completed")
        val NAME_DROP_ENABLED = booleanPreferencesKey("name_drop_enabled")
        val RSSI_THRESHOLD = intPreferencesKey("rssi_threshold")
        val GLOW_PREVIEW_MODE = stringPreferencesKey("glow_preview_mode")
        val BLUETOOTH_PROMPT_ANSWERED = booleanPreferencesKey("bluetooth_prompt_answered")
        val TEST_ONLY_MODE = booleanPreferencesKey("test_only_device_mode")

        val DISPLAY_NAME = stringPreferencesKey("contact_display_name")
        val PHONE_NUMBER = stringPreferencesKey("contact_phone")
        val EMAIL = stringPreferencesKey("contact_email")
        val COMPANY = stringPreferencesKey("contact_company")
        val HANDLE = stringPreferencesKey("contact_handle")
        val NOTES = stringPreferencesKey("contact_notes")
        val BG_COLOR = longPreferencesKey("contact_bg_color")
        val NAME_COLOR = longPreferencesKey("contact_name_color")
        val FONT_STYLE = stringPreferencesKey("contact_font_style")
        val PHOTO_PATH = stringPreferencesKey("contact_photo_path")
        val PHOTO_SHA256 = stringPreferencesKey("contact_photo_sha256")
        val BG_PHOTO_PATH = stringPreferencesKey("contact_bg_photo_path")
        val BG_PHOTO_SHA256 = stringPreferencesKey("contact_bg_photo_sha256")
        val FULLSCREEN_BG_ENABLED = booleanPreferencesKey("contact_fullscreen_bg_enabled")
        val GLOW_ACCENT = stringPreferencesKey("contact_glow_accent")

        val COUNTRY_NAME = stringPreferencesKey("country_name")
        val COUNTRY_CODE = stringPreferencesKey("country_code")
        val DIAL_PREFIX = stringPreferencesKey("country_dial_prefix")
        val ZONE_ID = stringPreferencesKey("country_zone_id")
        val COUNTRY_VERIFIED = booleanPreferencesKey("country_verified")

        val LAST_UPDATE_EDITION_KEY = stringPreferencesKey("last_update_edition_key")
        val CACHED_GEMINI_TIP = stringPreferencesKey("cached_gemini_tip")
        val LAST_SELF_TEST_DATE = stringPreferencesKey("last_self_test_date")
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ONBOARDING_COMPLETED] ?: false
    }

    val contactSetupCompleted: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.CONTACT_SETUP_COMPLETED] ?: false
    }

    val nameDropEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.NAME_DROP_ENABLED] ?: false
    }

    // Balanced -48 dBm default so touching phones with varying antennas/cases trigger reliably
    val rssiThreshold: Flow<Int> = context.dataStore.data.map {
        it[Keys.RSSI_THRESHOLD] ?: -48
    }

    val glowPreviewMode: Flow<GlowPreviewMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.GLOW_PREVIEW_MODE] ?: GlowPreviewMode.AUTO_LOCAL_DATE.name
        runCatching { GlowPreviewMode.valueOf(raw) }.getOrDefault(GlowPreviewMode.AUTO_LOCAL_DATE)
    }

    val bluetoothPromptAnswered: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.BLUETOOTH_PROMPT_ANSWERED] ?: false
    }

    val testOnlyDeviceMode: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.TEST_ONLY_MODE] ?: false
    }

    val contactCard: Flow<ContactCard> = context.dataStore.data.map { prefs ->
        val fontName = prefs[Keys.FONT_STYLE] ?: PosterFontStyle.BOLD.name
        val fontStyle = runCatching { PosterFontStyle.valueOf(fontName) }
            .getOrDefault(PosterFontStyle.BOLD)
        val glowName = prefs[Keys.GLOW_ACCENT] ?: GlowThemeStyle.NONE.name
        val glowStyle = runCatching { GlowThemeStyle.valueOf(glowName) }
            .getOrDefault(GlowThemeStyle.NONE)
        ContactCard(
            displayName = prefs[Keys.DISPLAY_NAME] ?: "",
            phoneNumber = prefs[Keys.PHONE_NUMBER] ?: "",
            email = prefs[Keys.EMAIL] ?: "",
            company = prefs[Keys.COMPANY] ?: "",
            handle = prefs[Keys.HANDLE] ?: "",
            notes = prefs[Keys.NOTES] ?: "",
            backgroundColorHex = prefs[Keys.BG_COLOR] ?: 0xFF1E293B,
            nameColorHex = prefs[Keys.NAME_COLOR] ?: 0xFFFFFFFF,
            fontStyle = fontStyle,
            photoPath = prefs[Keys.PHOTO_PATH],
            photoSha256 = prefs[Keys.PHOTO_SHA256],
            backgroundPhotoPath = prefs[Keys.BG_PHOTO_PATH],
            backgroundPhotoSha256 = prefs[Keys.BG_PHOTO_SHA256],
            fullScreenBackgroundEnabled = prefs[Keys.FULLSCREEN_BG_ENABLED] ?: true,
            glowAccent = glowStyle
        )
    }

    val countryProfile: Flow<CountryProfile> = context.dataStore.data.map { prefs ->
        CountryProfile(
            countryName = prefs[Keys.COUNTRY_NAME] ?: "United States",
            countryCode = prefs[Keys.COUNTRY_CODE] ?: "US",
            dialPrefix = prefs[Keys.DIAL_PREFIX] ?: "+1",
            zoneId = prefs[Keys.ZONE_ID] ?: java.util.TimeZone.getDefault().id,
            isVerified = prefs[Keys.COUNTRY_VERIFIED] ?: false
        )
    }

    val cachedUpdateMeta: Flow<Pair<String, String>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.LAST_UPDATE_EDITION_KEY] ?: "") to (prefs[Keys.CACHED_GEMINI_TIP] ?: "")
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = done }
    }

    suspend fun setContactSetupCompleted(done: Boolean) {
        context.dataStore.edit { it[Keys.CONTACT_SETUP_COMPLETED] = done }
    }

    suspend fun setNameDropEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NAME_DROP_ENABLED] = enabled }
    }

    suspend fun setRssiThreshold(threshold: Int) {
        context.dataStore.edit { it[Keys.RSSI_THRESHOLD] = threshold.coerceIn(-70, -30) }
    }

    suspend fun setGlowPreviewMode(mode: GlowPreviewMode) {
        context.dataStore.edit { it[Keys.GLOW_PREVIEW_MODE] = mode.name }
    }

    suspend fun setBluetoothPromptChoice(answered: Boolean, testOnlyMode: Boolean) {
        context.dataStore.edit {
            it[Keys.BLUETOOTH_PROMPT_ANSWERED] = answered
            it[Keys.TEST_ONLY_MODE] = testOnlyMode
        }
    }

    suspend fun saveContactCard(card: ContactCard) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_NAME] = card.displayName
            prefs[Keys.PHONE_NUMBER] = card.phoneNumber
            prefs[Keys.EMAIL] = card.email
            prefs[Keys.COMPANY] = card.company
            prefs[Keys.HANDLE] = card.handle
            prefs[Keys.NOTES] = card.notes
            prefs[Keys.BG_COLOR] = card.backgroundColorHex
            prefs[Keys.NAME_COLOR] = card.nameColorHex
            prefs[Keys.FONT_STYLE] = card.fontStyle.name
            prefs[Keys.FULLSCREEN_BG_ENABLED] = card.fullScreenBackgroundEnabled
            prefs[Keys.GLOW_ACCENT] = card.glowAccent.name
            if (card.photoPath != null) {
                prefs[Keys.PHOTO_PATH] = card.photoPath
            } else {
                prefs.remove(Keys.PHOTO_PATH)
            }
            if (card.photoSha256 != null) {
                prefs[Keys.PHOTO_SHA256] = card.photoSha256
            } else {
                prefs.remove(Keys.PHOTO_SHA256)
            }
            if (card.backgroundPhotoPath != null) {
                prefs[Keys.BG_PHOTO_PATH] = card.backgroundPhotoPath
            } else {
                prefs.remove(Keys.BG_PHOTO_PATH)
            }
            if (card.backgroundPhotoSha256 != null) {
                prefs[Keys.BG_PHOTO_SHA256] = card.backgroundPhotoSha256
            } else {
                prefs.remove(Keys.BG_PHOTO_SHA256)
            }
        }
    }

    suspend fun saveCountryProfile(profile: CountryProfile) {
        context.dataStore.edit { prefs ->
            prefs[Keys.COUNTRY_NAME] = profile.countryName
            prefs[Keys.COUNTRY_CODE] = profile.countryCode
            prefs[Keys.DIAL_PREFIX] = profile.dialPrefix
            prefs[Keys.ZONE_ID] = profile.zoneId
            prefs[Keys.COUNTRY_VERIFIED] = profile.isVerified
        }
    }

    suspend fun saveDailyUpdateCache(editionKey: String, tip: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_UPDATE_EDITION_KEY] = editionKey
            prefs[Keys.CACHED_GEMINI_TIP] = tip
        }
    }

    suspend fun saveSelfTestDate(dateKey: String) {
        context.dataStore.edit { it[Keys.LAST_SELF_TEST_DATE] = dateKey }
    }
}
