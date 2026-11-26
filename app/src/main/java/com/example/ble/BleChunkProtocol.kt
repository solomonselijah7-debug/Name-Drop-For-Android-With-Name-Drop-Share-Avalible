package com.example.ble

import com.example.data.MediaStorageHelper
import com.example.model.ContactCard
import com.example.model.GlowThemeStyle
import com.example.model.PosterFontStyle
import org.json.JSONObject
import java.nio.ByteBuffer

object BleChunkProtocol {
    const val TYPE_CONTACT_CARD = 1
    const val TYPE_PHOTO_SHARE = 2
    const val TYPE_GROUP_PHOTO_SHARE = 3
    const val TYPE_VIDEO_SHARE = 4
    const val TYPE_GROUP_VIDEO_SHARE = 5
    const val TYPE_SHARE_HANDSHAKE_READY = 6

    data class DecodedPayload(
        val type: Int,
        val contact: ContactCard? = null,
        val fileName: String? = null,
        val senderName: String? = null,
        val mimeType: String = "image/jpeg",
        val isVideo: Boolean = false,
        val photoBytes: ByteArray? = null,
        val backgroundPhotoBytes: ByteArray? = null,
        val verifiedSha256: Boolean = true
    )

    fun encodeContactPayload(
        card: ContactCard,
        photoBytes: ByteArray?,
        backgroundPhotoBytes: ByteArray? = null
    ): ByteArray {
        val portrait = photoBytes ?: ByteArray(0)
        val backdrop = backgroundPhotoBytes ?: ByteArray(0)
        val header = JSONObject().apply {
            put("type", TYPE_CONTACT_CARD)
            put("displayName", card.displayName)
            put("phoneNumber", card.phoneNumber)
            put("email", card.email)
            put("company", card.company)
            put("handle", card.handle)
            put("notes", card.notes)
            put("bgColor", card.backgroundColorHex)
            put("nameColor", card.nameColorHex)
            put("fontStyle", card.fontStyle.name)
            put("fullScreenBg", card.fullScreenBackgroundEnabled)
            put("glowAccent", card.glowAccent.name)
            put("portraitLen", portrait.size)
            put("bgPhotoLen", backdrop.size)
            if (portrait.isNotEmpty()) {
                put("photoSha256", MediaStorageHelper.computeSha256(portrait))
            }
            if (backdrop.isNotEmpty()) {
                put("bgPhotoSha256", MediaStorageHelper.computeSha256(backdrop))
            }
        }.toString().toByteArray(Charsets.UTF_8)

        val totalBinary = portrait.size + backdrop.size
        return ByteBuffer.allocate(8 + header.size + totalBinary)
            .putInt(header.size)
            .putInt(totalBinary)
            .put(header)
            .put(portrait)
            .put(backdrop)
            .array()
    }

    fun encodePhotoPayload(
        senderName: String,
        fileName: String,
        photoBytes: ByteArray,
        isGroupShare: Boolean
    ): ByteArray = encodeMediaPayload(
        senderName = senderName,
        fileName = fileName,
        mediaBytes = photoBytes,
        mimeType = "image/jpeg",
        isVideo = false,
        isGroupShare = isGroupShare
    )

    fun encodeMediaPayload(
        senderName: String,
        fileName: String,
        mediaBytes: ByteArray,
        mimeType: String,
        isVideo: Boolean,
        isGroupShare: Boolean
    ): ByteArray {
        val msgType = when {
            isVideo && isGroupShare -> TYPE_GROUP_VIDEO_SHARE
            isVideo -> TYPE_VIDEO_SHARE
            isGroupShare -> TYPE_GROUP_PHOTO_SHARE
            else -> TYPE_PHOTO_SHARE
        }
        val header = JSONObject().apply {
            put("type", msgType)
            put("senderName", senderName)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("isVideo", isVideo)
            put("photoSha256", MediaStorageHelper.computeSha256(mediaBytes))
        }.toString().toByteArray(Charsets.UTF_8)

        return ByteBuffer.allocate(8 + header.size + mediaBytes.size)
            .putInt(header.size)
            .putInt(mediaBytes.size)
            .put(header)
            .put(mediaBytes)
            .array()
    }

    fun encodeMutualShareHandshake(card: ContactCard, photoBytes: ByteArray?): ByteArray {
        val portrait = photoBytes ?: ByteArray(0)
        val header = JSONObject().apply {
            put("type", TYPE_SHARE_HANDSHAKE_READY)
            put("displayName", card.displayName)
            put("phoneNumber", card.phoneNumber)
            put("email", card.email)
            put("company", card.company)
            put("handle", card.handle)
            put("notes", card.notes)
            put("bgColor", card.backgroundColorHex)
            put("nameColor", card.nameColorHex)
            put("fontStyle", card.fontStyle.name)
            put("fullScreenBg", card.fullScreenBackgroundEnabled)
            put("glowAccent", card.glowAccent.name)
            put("portraitLen", portrait.size)
            if (portrait.isNotEmpty()) {
                put("photoSha256", MediaStorageHelper.computeSha256(portrait))
            }
        }.toString().toByteArray(Charsets.UTF_8)

        return ByteBuffer.allocate(8 + header.size + portrait.size)
            .putInt(header.size)
            .putInt(portrait.size)
            .put(header)
            .put(portrait)
            .array()
    }

    fun decodePayload(raw: ByteArray): DecodedPayload? {
        return runCatching {
            if (raw.size < 8) return null
            val buffer = ByteBuffer.wrap(raw)
            val headerLen = buffer.int
            val binaryLen = buffer.int
            if (headerLen <= 0 || binaryLen < 0 || headerLen + binaryLen + 8 > raw.size) return null

            val headerBytes = ByteArray(headerLen)
            buffer.get(headerBytes)
            val binaryBytes = if (binaryLen > 0) {
                ByteArray(binaryLen).also { buffer.get(it) }
            } else {
                null
            }

            val json = JSONObject(String(headerBytes, Charsets.UTF_8))
            val type = json.getInt("type")

            if (type == TYPE_CONTACT_CARD || type == TYPE_SHARE_HANDSHAKE_READY) {
                val portraitLen = json.optInt("portraitLen", binaryLen).coerceIn(0, binaryLen)
                val bgPhotoLen = json.optInt("bgPhotoLen", (binaryLen - portraitLen).coerceAtLeast(0))
                    .coerceIn(0, (binaryLen - portraitLen).coerceAtLeast(0))

                val portraitBytes = if (binaryBytes != null && portraitLen > 0) {
                    binaryBytes.copyOfRange(0, portraitLen)
                } else {
                    null
                }
                val bgBytes = if (binaryBytes != null && bgPhotoLen > 0) {
                    binaryBytes.copyOfRange(portraitLen, portraitLen + bgPhotoLen)
                } else {
                    null
                }

                val expectedSha = json.optString("photoSha256", "")
                val verified = if (portraitBytes != null && expectedSha.isNotBlank()) {
                    MediaStorageHelper.computeSha256(portraitBytes) == expectedSha
                } else {
                    true
                }

                val font = runCatching {
                    PosterFontStyle.valueOf(json.optString("fontStyle", PosterFontStyle.BOLD.name))
                }.getOrDefault(PosterFontStyle.BOLD)
                val glow = runCatching {
                    GlowThemeStyle.valueOf(json.optString("glowAccent", GlowThemeStyle.NONE.name))
                }.getOrDefault(GlowThemeStyle.NONE)

                val card = ContactCard(
                    displayName = json.optString("displayName", "Nearby Contact"),
                    phoneNumber = json.optString("phoneNumber", ""),
                    email = json.optString("email", ""),
                    company = json.optString("company", ""),
                    handle = json.optString("handle", ""),
                    notes = json.optString("notes", ""),
                    backgroundColorHex = json.optLong("bgColor", 0xFF1E293B),
                    nameColorHex = json.optLong("nameColor", 0xFFFFFFFF),
                    fontStyle = font,
                    photoSha256 = expectedSha.takeIf { it.isNotBlank() },
                    fullScreenBackgroundEnabled = json.optBoolean("fullScreenBg", true),
                    glowAccent = glow
                )
                DecodedPayload(
                    type = type,
                    contact = card,
                    photoBytes = portraitBytes,
                    backgroundPhotoBytes = bgBytes,
                    verifiedSha256 = verified
                )
            } else {
                val expectedSha = json.optString("photoSha256", "")
                val verified = if (binaryBytes != null && expectedSha.isNotBlank()) {
                    MediaStorageHelper.computeSha256(binaryBytes) == expectedSha
                } else {
                    true
                }
                val isVideo = json.optBoolean(
                    "isVideo",
                    type == TYPE_VIDEO_SHARE || type == TYPE_GROUP_VIDEO_SHARE
                )
                val defaultMime = if (isVideo) "video/mp4" else "image/jpeg"
                DecodedPayload(
                    type = type,
                    senderName = json.optString("senderName", "Nearby Phone"),
                    fileName = json.optString(
                        "fileName",
                        if (isVideo) "NameDrop_${System.currentTimeMillis()}.mp4" else "NameDrop_${System.currentTimeMillis()}.jpg"
                    ),
                    mimeType = json.optString("mimeType", defaultMime),
                    isVideo = isVideo,
                    photoBytes = binaryBytes,
                    verifiedSha256 = verified
                )
            }
        }.getOrNull()
    }

    fun chunkBytes(payload: ByteArray, chunkSize: Int = 460): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val chunks = ArrayList<ByteArray>((payload.size / chunkSize) + 1)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }
}
