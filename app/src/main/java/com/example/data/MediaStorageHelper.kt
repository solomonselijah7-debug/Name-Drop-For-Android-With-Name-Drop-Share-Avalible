package com.example.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.model.ContactCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object MediaStorageHelper {

    data class ReadMediaPayload(
        val bytes: ByteArray,
        val fileName: String,
        val mimeType: String,
        val isVideo: Boolean
    )

    suspend fun copyContactPhotoToInternal(context: Context, sourceUri: Uri): Pair<String, String>? =
        copyInternalImage(context, sourceUri, prefix = "my_contact_poster_", maxDim = 720)

    suspend fun copyBackgroundPhotoToInternal(context: Context, sourceUri: Uri): Pair<String, String>? =
        copyInternalImage(context, sourceUri, prefix = "my_bg_poster_", maxDim = 1280)

    private suspend fun copyInternalImage(
        context: Context,
        sourceUri: Uri,
        prefix: String,
        maxDim: Int
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = File(context.filesDir, "posters").apply { mkdirs() }
            val destFile = File(destDir, "${prefix}${System.currentTimeMillis()}.jpg")

            destDir.listFiles()?.forEach { existing ->
                if (existing.name.startsWith(prefix)) {
                    existing.delete()
                }
            }

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val rawBytes = input.readBytes()
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (bitmap != null) {
                    val scaled = scaleBitmapMax(bitmap, maxDim)
                    destFile.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    }
                    if (scaled !== bitmap) bitmap.recycle()
                    scaled.recycle()
                } else {
                    destFile.writeBytes(rawBytes)
                }
            } ?: return@withContext null

            val savedBytes = destFile.readBytes()
            val sha256 = computeSha256(savedBytes)
            destFile.absolutePath to sha256
        }.onFailure {
            DebugLogger.log("Media", "Failed copying image ($prefix): ${it.message}", "ERROR")
        }.getOrNull()
    }

    suspend fun saveReceivedContactPhoto(
        context: Context,
        bytes: ByteArray,
        prefix: String = "received_"
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = File(context.filesDir, "received_posters").apply { mkdirs() }
            val destFile = File(destDir, "${prefix}${System.currentTimeMillis()}.jpg")
            destFile.writeBytes(bytes)
            val sha = computeSha256(bytes)
            destFile.absolutePath to sha
        }.getOrNull()
    }

    fun readContactPhotoBytesForBle(photoPath: String?, maxBytes: Int = 24_000): ByteArray? {
        if (photoPath.isNullOrBlank()) return null
        return runCatching {
            val file = File(photoPath)
            if (!file.exists()) return null
            val raw = file.readBytes()
            if (raw.size <= maxBytes) return raw

            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
            val scaled = scaleBitmapMax(bmp, 240)
            var quality = 76
            val stream = ByteArrayOutputStream()
            do {
                stream.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                quality -= 10
            } while (stream.size() > maxBytes && quality >= 30)
            if (scaled !== bmp) bmp.recycle()
            scaled.recycle()
            stream.toByteArray()
        }.getOrNull()
    }

    fun detectMimeType(context: Context, uri: Uri, hintMime: String? = null): String {
        val resolved = context.contentResolver.getType(uri)
        if (!resolved.isNullOrBlank() && resolved != "*/*") return resolved
        if (!hintMime.isNullOrBlank() && hintMime != "*/*") return hintMime
        val path = uri.toString().lowercase()
        return when {
            path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".mkv") || path.contains("video") -> "video/mp4"
            else -> "image/jpeg"
        }
    }

    fun isVideoUri(context: Context, uri: Uri, hintMime: String? = null): Boolean {
        return detectMimeType(context, uri, hintMime).startsWith("video/")
    }

    fun queryDisplayName(context: Context, uri: Uri, isVideo: Boolean): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return if (isVideo) {
            "NameDrop_Video_${System.currentTimeMillis()}.mp4"
        } else {
            "NameDrop_Photo_${System.currentTimeMillis()}.jpg"
        }
    }

    suspend fun readUriBytes(context: Context, uri: Uri, maxSide: Int = 1440): ByteArray? =
        readMediaPayload(context, uri, maxImageSide = maxSide)?.bytes

    suspend fun readMediaPayload(
        context: Context,
        uri: Uri,
        hintMime: String? = null,
        maxImageSide: Int = 1440,
        maxVideoBytes: Int = 4 * 1024 * 1024
    ): ReadMediaPayload? = withContext(Dispatchers.IO) {
        runCatching {
            val mime = detectMimeType(context, uri, hintMime)
            val isVideo = mime.startsWith("video/")
            val fileName = queryDisplayName(context, uri, isVideo)

            val rawBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                if (isVideo) {
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        val allowed = minOf(read, maxVideoBytes - total)
                        if (allowed > 0) {
                            buf.write(chunk, 0, allowed)
                            total += allowed
                        }
                        if (total >= maxVideoBytes) break
                    }
                    buf.toByteArray()
                } else {
                    val raw = input.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@use raw
                    val scaled = scaleBitmapMax(bmp, maxImageSide)
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    if (scaled !== bmp) bmp.recycle()
                    scaled.recycle()
                    out.toByteArray()
                }
            } ?: return@withContext null

            ReadMediaPayload(
                bytes = rawBytes,
                fileName = fileName,
                mimeType = if (isVideo) (if (mime == "video/*") "video/mp4" else mime) else "image/jpeg",
                isVideo = isVideo
            )
        }.onFailure {
            DebugLogger.log("Media", "Failed reading media URI: ${it.message}", "ERROR")
        }.getOrNull()
    }

    fun extractVideoThumbnail(context: Context, uri: Uri): Bitmap? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            frame
        }.getOrNull()
    }

    fun extractVideoThumbnailFromBytes(context: Context, bytes: ByteArray): Bitmap? {
        return runCatching {
            val tmp = File.createTempFile("nd_vid_thumb_", ".mp4", context.cacheDir)
            tmp.writeBytes(bytes)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tmp.absolutePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            tmp.delete()
            frame
        }.getOrNull()
    }

    suspend fun savePhotoToGallery(
        context: Context,
        photoBytes: ByteArray,
        displayName: String = "NameDrop_${System.currentTimeMillis()}.jpg"
    ): Uri? = saveMediaToGallery(
        context = context,
        mediaBytes = photoBytes,
        displayName = displayName,
        mimeType = "image/jpeg",
        isVideo = false
    )

    suspend fun saveMediaToGallery(
        context: Context,
        mediaBytes: ByteArray,
        displayName: String,
        mimeType: String,
        isVideo: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val safeMime = when {
                isVideo && mimeType.startsWith("video/") && mimeType != "video/*" -> mimeType
                isVideo -> "video/mp4"
                mimeType.startsWith("image/") && mimeType != "image/*" -> mimeType
                else -> "image/jpeg"
            }

            val collectionUri = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val contentValues = ContentValues().apply {
                if (isVideo) {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, safeMime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/NameDrop")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                } else {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, safeMime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/NameDrop")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
            }

            val uri = resolver.insert(collectionUri, contentValues) ?: return@withContext null

            resolver.openOutputStream(uri)?.use { out ->
                out.write(mediaBytes)
                out.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                if (isVideo) {
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                } else {
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, contentValues, null, null)
            }

            DebugLogger.log("Media", "Saved ${if (isVideo) "video" else "photo"} to Gallery: $uri")
            uri
        }.onFailure {
            DebugLogger.log("Media", "Gallery save failed: ${it.message}", "ERROR")
        }.getOrNull()
    }

    fun buildSaveContactIntent(card: ContactCard): Intent {
        return Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, card.displayName)
            putExtra(ContactsContract.Intents.Insert.PHONE, card.phoneNumber)
            if (card.email.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.EMAIL, card.email)
            }
            if (card.company.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.COMPANY, card.company)
            }
            if (card.notes.isNotBlank() || card.handle.isNotBlank()) {
                val noteText = listOfNotNull(
                    card.handle.takeIf { it.isNotBlank() }?.let { "Handle: $it" },
                    card.notes.takeIf { it.isNotBlank() }
                ).joinToString("\n")
                putExtra(ContactsContract.Intents.Insert.NOTES, noteText)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun scaleBitmapMax(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = w.toFloat() / h.toFloat()
        val (targetW, targetH) = if (w >= h) {
            maxDim to (maxDim / ratio).toInt().coerceAtLeast(1)
        } else {
            (maxDim * ratio).toInt().coerceAtLeast(1) to maxDim
        }
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }
}
