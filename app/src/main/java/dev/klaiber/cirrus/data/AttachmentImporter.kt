package dev.klaiber.cirrus.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.klaiber.cirrus.domain.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Copies picked content into app-private storage and prepares it for the model.
 *
 * Content URIs are only valid for the lifetime of the grant, so anything attached is copied
 * immediately; otherwise reopening an old conversation would fail to re-send its images.
 */
@Singleton
class AttachmentImporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun import(uri: Uri): Result<Attachment> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = readMetadata(uri)
            val mimeType = context.contentResolver.getType(uri) ?: metadata.mimeGuess
            val directory = File(context.filesDir, ATTACHMENT_DIR).apply { mkdirs() }
            val id = UUID.randomUUID().toString()

            if (mimeType.startsWith("image/")) {
                val target = File(directory, "$id.jpg")
                val bytes = compressImage(uri, target)
                Attachment(
                    id = id,
                    messageId = "",
                    displayName = metadata.name,
                    mimeType = "image/jpeg",
                    sizeBytes = bytes,
                    localPath = target.absolutePath,
                    kind = Attachment.Kind.IMAGE,
                )
            } else {
                val text = readText(uri)
                    ?: throw IllegalArgumentException(
                        "Unsupported file type: $mimeType. Attach an image or a text-based file.",
                    )
                val target = File(directory, "$id.txt")
                target.writeText(text)
                Attachment(
                    id = id,
                    messageId = "",
                    displayName = metadata.name,
                    mimeType = mimeType,
                    sizeBytes = target.length(),
                    localPath = target.absolutePath,
                    kind = Attachment.Kind.DOCUMENT,
                    extractedText = text,
                )
            }
        }
    }

    fun delete(attachment: Attachment) {
        runCatching { File(attachment.localPath).delete() }
    }

    private data class Metadata(val name: String, val mimeGuess: String)

    private fun readMetadata(uri: Uri): Metadata {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.let { name = it }
            }
        }
        val guess = when (name.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> "image/*"
            else -> "text/plain"
        }
        return Metadata(name, guess)
    }

    /**
     * Downscales and re-encodes to JPEG.
     *
     * Vision models resize server-side anyway, and a full-resolution phone photo becomes
     * several megabytes once Base64-encoded into the request body.
     */
    private fun compressImage(uri: Uri, target: File): Long {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(longestEdge)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalArgumentException("Could not read the selected image.")

        val scaled = scaleToLimit(decoded)
        FileOutputStream(target).use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        }
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        return target.length()
    }

    private fun calculateSampleSize(longestEdge: Int): Int {
        var sampleSize = 1
        while (longestEdge / sampleSize > MAX_EDGE_PX * 2) sampleSize *= 2
        return sampleSize
    }

    private fun scaleToLimit(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE_PX) return bitmap
        val ratio = MAX_EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** Returns null when the bytes do not look like text, which callers report as unsupported. */
    private fun readText(uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().take(MAX_DOCUMENT_BYTES).toByteArray()
        } ?: return null

        // A NUL byte in the first block is the classic binary-file signal.
        if (bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }) return null
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val ATTACHMENT_DIR = "attachments"
        const val MAX_EDGE_PX = 1568
        const val JPEG_QUALITY = 85
        const val MAX_DOCUMENT_BYTES = 2_000_000
        const val BINARY_SNIFF_BYTES = 1024
    }
}
