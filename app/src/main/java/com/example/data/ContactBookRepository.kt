package com.example.data

import android.content.ContentProviderOperation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import com.example.ble.BlePermissions
import com.example.model.ContactCard
import com.example.model.GlowThemeStyle
import com.example.model.PosterFontStyle
import com.example.model.SavedContactEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Automatically saves received & swapped Name Drop Contact Cards (including phone/call numbers,
 * display name, avatar/portrait photo, email, company, handle, and notes) into:
 * 1. The Android device's Contacts Book (when READ_CONTACTS / WRITE_CONTACTS are granted), AND
 * 2. A durable local app-level saved contacts JSON store so no shared contact call info is ever lost
 *    even when system contacts permission is denied or restricted.
 */
object ContactBookRepository {

    data class SaveResult(
        val entry: SavedContactEntry,
        val savedToSystemContacts: Boolean,
        val statusText: String
    )

    private const val STORE_FILE_NAME = "saved_contacts.json"
    private val storeMutex = Mutex()

    private val _savedContacts = MutableStateFlow<List<SavedContactEntry>>(emptyList())
    val savedContacts: StateFlow<List<SavedContactEntry>> = _savedContacts.asStateFlow()

    suspend fun loadSavedContacts(context: Context) {
        withContext(Dispatchers.IO) {
            storeMutex.withLock {
                _savedContacts.value = readStoreFromDisk(context)
            }
        }
    }

    suspend fun autoSaveContactCard(
        context: Context,
        card: ContactCard,
        photoBytes: ByteArray? = null,
        sourceLabel: String = "Received"
    ): SaveResult? {
        if (card.displayName.isBlank() && card.phoneNumber.isBlank()) return null

        return withContext(Dispatchers.IO) {
            val resolvedPhotoPath = ensurePersistedPhotoPath(context, card.photoPath, photoBytes)
            val resolvedCard = if (resolvedPhotoPath != null && card.photoPath.isNullOrBlank()) {
                card.copy(photoPath = resolvedPhotoPath)
            } else {
                card
            }

            var systemUri: String? = null
            var savedToSystem = false

            if (BlePermissions.hasContactsPermission(context)) {
                systemUri = insertOrFindInSystemContacts(
                    context = context,
                    card = resolvedCard,
                    photoBytes = photoBytes ?: loadBytesFromPath(resolvedCard.photoPath)
                )
                savedToSystem = systemUri != null
            }

            val entry = storeMutex.withLock {
                val current = readStoreFromDisk(context).toMutableList()
                val normalizedPhone = normalizePhoneForCompare(resolvedCard.phoneNumber)
                val normalizedName = resolvedCard.displayName.trim().lowercase()

                val existingIdx = current.indexOfFirst { existing ->
                    val existingPhone = normalizePhoneForCompare(existing.card.phoneNumber)
                    if (normalizedPhone.isNotEmpty() && existingPhone.isNotEmpty()) {
                        existingPhone == normalizedPhone
                    } else {
                        existing.card.displayName.trim().lowercase() == normalizedName && normalizedName.isNotEmpty()
                    }
                }

                val newEntry = if (existingIdx >= 0) {
                    val prev = current[existingIdx]
                    prev.copy(
                        card = resolvedCard.copy(
                            photoPath = resolvedCard.photoPath ?: prev.card.photoPath,
                            backgroundPhotoPath = resolvedCard.backgroundPhotoPath ?: prev.card.backgroundPhotoPath
                        ),
                        savedAtMillis = System.currentTimeMillis(),
                        savedToSystemContacts = savedToSystem || prev.savedToSystemContacts,
                        systemContactUri = systemUri ?: prev.systemContactUri,
                        sourceLabel = sourceLabel
                    ).also { updated ->
                        current.removeAt(existingIdx)
                        current.add(0, updated)
                    }
                } else {
                    SavedContactEntry(
                        id = UUID.randomUUID().toString(),
                        card = resolvedCard,
                        savedAtMillis = System.currentTimeMillis(),
                        savedToSystemContacts = savedToSystem,
                        systemContactUri = systemUri,
                        sourceLabel = sourceLabel
                    ).also { created ->
                        current.add(0, created)
                    }
                }

                writeStoreToDisk(context, current)
                _savedContacts.value = current
                newEntry
            }

            val statusText = if (entry.savedToSystemContacts) {
                "Contact saved"
            } else {
                "Contact saved locally (grant Contacts permission to sync to Phone Book)"
            }
            DebugLogger.log(
                "Contacts",
                "Auto-saved contact (${entry.card.displayName.ifBlank { "Unnamed" }}, systemSynced=${entry.savedToSystemContacts}, source=$sourceLabel)"
            )
            SaveResult(
                entry = entry,
                savedToSystemContacts = entry.savedToSystemContacts,
                statusText = statusText
            )
        }
    }

    suspend fun syncExistingLocalContactToSystem(context: Context, entryId: String): SaveResult? {
        val currentEntry = _savedContacts.value.firstOrNull { it.id == entryId } ?: return null
        return autoSaveContactCard(
            context = context,
            card = currentEntry.card,
            photoBytes = loadBytesFromPath(currentEntry.card.photoPath),
            sourceLabel = currentEntry.sourceLabel
        )
    }

    fun normalizePhoneForCompare(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val digitsOnly = trimmed.filter { it.isDigit() }
        return if (digitsOnly.length >= 7) digitsOnly else trimmed.lowercase()
    }

    fun deduplicationKey(card: ContactCard): String {
        val phoneKey = normalizePhoneForCompare(card.phoneNumber)
        if (phoneKey.isNotEmpty()) return "phone:$phoneKey"
        val nameKey = card.displayName.trim().lowercase()
        return if (nameKey.isNotEmpty()) "name:$nameKey" else ""
    }

    fun formatSavedSummary(savedToSystemContacts: Boolean): String {
        return if (savedToSystemContacts) {
            "Contact saved"
        } else {
            "Contact saved locally (grant Contacts permission to sync to Phone Book)"
        }
    }

    private fun insertOrFindInSystemContacts(
        context: Context,
        card: ContactCard,
        photoBytes: ByteArray?
    ): String? {
        return runCatching {
            // 1. Check if phone number already exists in Android Contacts so we avoid duplicates
            if (card.phoneNumber.isNotBlank()) {
                val lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(card.phoneNumber.trim())
                )
                context.contentResolver.query(
                    lookupUri,
                    arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        val key = cursor.getString(1)
                        return@runCatching ContactsContract.Contacts.getLookupUri(id, key)?.toString()
                            ?: "content://com.android.contacts/contacts/$id"
                    }
                }
            }

            // 2. Insert full RawContact + structured fields atomically
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            val name = card.displayName.trim().ifEmpty { card.phoneNumber.trim() }
            if (name.isNotEmpty()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build()
                )
            }

            if (card.phoneNumber.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, card.phoneNumber.trim())
                        .withValue(
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        )
                        .build()
                )
            }

            if (card.email.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, card.email.trim())
                        .withValue(
                            ContactsContract.CommonDataKinds.Email.TYPE,
                            ContactsContract.CommonDataKinds.Email.TYPE_MOBILE
                        )
                        .build()
                )
            }

            if (card.company.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, card.company.trim())
                        .withValue(
                            ContactsContract.CommonDataKinds.Organization.TYPE,
                            ContactsContract.CommonDataKinds.Organization.TYPE_WORK
                        )
                        .build()
                )
            }

            if (card.handle.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Nickname.NAME, card.handle.trim())
                        .build()
                )
            }

            val combinedNotes = listOfNotNull(
                card.handle.takeIf { it.isNotBlank() }?.let { "Handle: $it" },
                card.notes.takeIf { it.isNotBlank() },
                "Saved via Name Drop"
            ).joinToString("\n")
            if (combinedNotes.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, combinedNotes)
                        .build()
                )
            }

            prepareContactPhotoBytes(photoBytes)?.let { safeAvatar ->
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, safeAvatar)
                        .build()
                )
            }

            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            results.firstOrNull()?.uri?.toString()
        }.onFailure {
            DebugLogger.log("Contacts", "System Contacts insert fell back to local store: ${it.message}", "WARN")
        }.getOrNull()
    }

    private fun prepareContactPhotoBytes(raw: ByteArray?): ByteArray? {
        if (raw == null || raw.isEmpty()) return null
        return runCatching {
            if (raw.size <= 120_000) return@runCatching raw
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@runCatching null
            val maxDim = 360
            val w = bmp.width
            val h = bmp.height
            val scale = minOf(maxDim.toFloat() / w.coerceAtLeast(1), maxDim.toFloat() / h.coerceAtLeast(1), 1f)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (scaled !== bmp) bmp.recycle()
            scaled.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    private fun ensurePersistedPhotoPath(
        context: Context,
        existingPath: String?,
        photoBytes: ByteArray?
    ): String? {
        if (!existingPath.isNullOrBlank() && File(existingPath).exists()) {
            return existingPath
        }
        if (photoBytes == null || photoBytes.isEmpty()) return null
        return runCatching {
            val dir = File(context.filesDir, "received_posters").apply { mkdirs() }
            val file = File(dir, "saved_avatar_${System.currentTimeMillis()}.jpg")
            file.writeBytes(photoBytes)
            file.absolutePath
        }.getOrNull()
    }

    private fun loadBytesFromPath(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val f = File(path)
            if (f.exists()) f.readBytes() else null
        }.getOrNull()
    }

    private fun readStoreFromDisk(context: Context): List<SavedContactEntry> {
        val file = File(context.filesDir, STORE_FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            parseEntriesFromJson(arr)
        }.getOrDefault(emptyList())
    }

    private fun writeStoreToDisk(context: Context, entries: List<SavedContactEntry>) {
        runCatching {
            val file = File(context.filesDir, STORE_FILE_NAME)
            file.writeText(serializeEntriesToJson(entries).toString())
        }.onFailure {
            DebugLogger.log("Contacts", "Failed persisting local saved contacts: ${it.message}", "ERROR")
        }
    }

    fun serializeEntriesToJson(entries: List<SavedContactEntry>): JSONArray {
        val arr = JSONArray()
        entries.forEach { entry ->
            val card = entry.card
            val cardJson = JSONObject().apply {
                put("displayName", card.displayName)
                put("phoneNumber", card.phoneNumber)
                put("email", card.email)
                put("company", card.company)
                put("handle", card.handle)
                put("notes", card.notes)
                put("backgroundColorHex", card.backgroundColorHex)
                put("nameColorHex", card.nameColorHex)
                put("fontStyle", card.fontStyle.name)
                put("photoPath", card.photoPath ?: JSONObject.NULL)
                put("photoSha256", card.photoSha256 ?: JSONObject.NULL)
                put("backgroundPhotoPath", card.backgroundPhotoPath ?: JSONObject.NULL)
                put("backgroundPhotoSha256", card.backgroundPhotoSha256 ?: JSONObject.NULL)
                put("fullScreenBackgroundEnabled", card.fullScreenBackgroundEnabled)
                put("glowAccent", card.glowAccent.name)
            }
            val itemJson = JSONObject().apply {
                put("id", entry.id)
                put("savedAtMillis", entry.savedAtMillis)
                put("savedToSystemContacts", entry.savedToSystemContacts)
                put("systemContactUri", entry.systemContactUri ?: JSONObject.NULL)
                put("sourceLabel", entry.sourceLabel)
                put("card", cardJson)
            }
            arr.put(itemJson)
        }
        return arr
    }

    fun parseEntriesFromJson(arr: JSONArray): List<SavedContactEntry> {
        val list = mutableListOf<SavedContactEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val c = obj.optJSONObject("card") ?: continue
            val card = ContactCard(
                displayName = c.optString("displayName", ""),
                phoneNumber = c.optString("phoneNumber", ""),
                email = c.optString("email", ""),
                company = c.optString("company", ""),
                handle = c.optString("handle", ""),
                notes = c.optString("notes", ""),
                backgroundColorHex = c.optLong("backgroundColorHex", 0xFF1E293B),
                nameColorHex = c.optLong("nameColorHex", 0xFFFFFFFF),
                fontStyle = runCatching {
                    PosterFontStyle.valueOf(c.optString("fontStyle", PosterFontStyle.BOLD.name))
                }.getOrDefault(PosterFontStyle.BOLD),
                photoPath = c.optString("photoPath").takeIf { it.isNotBlank() && it != "null" },
                photoSha256 = c.optString("photoSha256").takeIf { it.isNotBlank() && it != "null" },
                backgroundPhotoPath = c.optString("backgroundPhotoPath").takeIf { it.isNotBlank() && it != "null" },
                backgroundPhotoSha256 = c.optString("backgroundPhotoSha256").takeIf { it.isNotBlank() && it != "null" },
                fullScreenBackgroundEnabled = c.optBoolean("fullScreenBackgroundEnabled", true),
                glowAccent = runCatching {
                    GlowThemeStyle.valueOf(c.optString("glowAccent", GlowThemeStyle.NONE.name))
                }.getOrDefault(GlowThemeStyle.NONE)
            )
            list.add(
                SavedContactEntry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    card = card,
                    savedAtMillis = obj.optLong("savedAtMillis", 0L),
                    savedToSystemContacts = obj.optBoolean("savedToSystemContacts", false),
                    systemContactUri = obj.optString("systemContactUri").takeIf { it.isNotBlank() && it != "null" },
                    sourceLabel = obj.optString("sourceLabel", "Received")
                )
            )
        }
        return list
    }
}
