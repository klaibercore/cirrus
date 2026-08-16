package dev.klaiber.cirrus.data

import dev.klaiber.cirrus.domain.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Copies a picked file into Cirrus's own storage and prepares it for the model.
 *
 * The copy is not a leftover from Android's content URIs — those grants expire, which is why the
 * original copies immediately — but it earns its place here too: a conversation that references a
 * file on the desktop would fail to re-send its images the first time somebody tidies up.
 *
 * `ImageIO` and `Graphics2D` in place of `BitmapFactory`. There is no `inSampleSize` equivalent, so
 * a large photo is decoded in full and then scaled once, which costs memory a phone did not have to
 * spend and a desktop does not notice.
 */
@Singleton
class AttachmentImporter @Inject constructor(
    private val dataDir: File,
) {

    suspend fun import(source: File): Result<Attachment> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(dataDir, ATTACHMENT_DIR).apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val name = source.name.ifBlank { "attachment" }

            if (looksLikeImage(name)) {
                val target = File(directory, "$id.jpg")
                val bytes = compressImage(source, target)
                Attachment(
                    id = id,
                    messageId = "",
                    displayName = name,
                    mimeType = "image/jpeg",
                    sizeBytes = bytes,
                    localPath = target.absolutePath,
                    kind = Attachment.Kind.IMAGE,
                )
            } else {
                val text = readText(source)
                    ?: throw IllegalArgumentException(
                        "Unsupported file: ${source.name}. Attach an image or a text-based file.",
                    )
                val target = File(directory, "$id.txt")
                target.writeText(text)
                Attachment(
                    id = id,
                    messageId = "",
                    displayName = name,
                    mimeType = mimeGuess(name),
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

    private fun looksLikeImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun mimeGuess(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "json" -> "application/json"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            else -> "text/plain"
        }

    /**
     * Downscales and re-encodes to JPEG.
     *
     * Vision models resize server-side anyway, and a full-resolution photo becomes several
     * megabytes once Base64-encoded into the request body.
     */
    private fun compressImage(source: File, target: File): Long {
        val decoded = ImageIO.read(source)
            ?: throw IllegalArgumentException("Could not read ${source.name} as an image.")

        val scaled = scaleToLimit(decoded)
        // JPEG has no alpha channel, and writing an image that has one produces a red-tinted mess
        // rather than an error, so anything with transparency is flattened onto white first.
        val opaque = if (scaled.type == BufferedImage.TYPE_INT_RGB) {
            scaled
        } else {
            BufferedImage(scaled.width, scaled.height, BufferedImage.TYPE_INT_RGB).also { flat ->
                flat.createGraphics().run {
                    color = java.awt.Color.WHITE
                    fillRect(0, 0, scaled.width, scaled.height)
                    drawImage(scaled, 0, 0, null)
                    dispose()
                }
            }
        }

        ImageIO.write(opaque, "jpg", target)
        return target.length()
    }

    private fun scaleToLimit(image: BufferedImage): BufferedImage {
        val longest = maxOf(image.width, image.height)
        if (longest <= MAX_EDGE_PX) return image
        val ratio = MAX_EDGE_PX.toFloat() / longest
        val width = (image.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (image.height * ratio).roundToInt().coerceAtLeast(1)

        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { target ->
            target.createGraphics().run {
                setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                drawImage(image, 0, 0, width, height, null)
                dispose()
            }
        }
    }

    /** Returns null when the bytes do not look like text, which callers report as unsupported. */
    private fun readText(source: File): String? {
        val bytes = runCatching {
            source.inputStream().use { input ->
                input.readNBytes(MAX_DOCUMENT_BYTES)
            }
        }.getOrNull() ?: return null

        // A NUL byte in the first block is the classic binary-file signal.
        if (bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }) return null
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val ATTACHMENT_DIR = "attachments"
        const val MAX_EDGE_PX = 1568
        const val MAX_DOCUMENT_BYTES = 2_000_000
        const val BINARY_SNIFF_BYTES = 1024

        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }
}
