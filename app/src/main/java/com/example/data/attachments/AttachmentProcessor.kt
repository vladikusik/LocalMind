package com.example.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class AttachmentProcessor(private val context: Context) {

    private val attachmentsDir: File
        get() = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    suspend fun importFromUri(uri: Uri, forceImage: Boolean = false): Result<MessageAttachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val detectedMime = resolver.getType(uri).orEmpty()
                val rawName = queryDisplayName(uri) ?: if (forceImage) "photo.jpg" else "document"
                val mimeType = when {
                    detectedMime.isNotBlank() -> detectedMime
                    forceImage -> "image/jpeg"
                    rawName.endsWith(".png", true) -> "image/png"
                    rawName.endsWith(".jpg", true) || rawName.endsWith(".jpeg", true) -> "image/jpeg"
                    rawName.endsWith(".webp", true) -> "image/webp"
                    rawName.endsWith(".pdf", true) -> "application/pdf"
                    rawName.endsWith(".json", true) -> "application/json"
                    rawName.endsWith(".md", true) -> "text/markdown"
                    else -> "text/plain"
                }

                val isImage = forceImage || mimeType.startsWith("image/", ignoreCase = true)
                val id = UUID.randomUUID().toString()
                val safeSuffix = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_").takeLast(48)

                if (isImage) {
                    val destFile = File(attachmentsDir, "${id}_$safeSuffix.jpg")
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Unable to read selected image.")
                    val compressed = compressImageForLlm(bytes)
                    FileOutputStream(destFile).use { it.write(compressed) }
                    MessageAttachment(
                        id = id,
                        displayName = rawName,
                        mimeType = "image/jpeg",
                        localPath = destFile.absolutePath,
                        isImage = true,
                        sizeBytes = destFile.length(),
                        extractedText = null
                    )
                } else {
                    val destFile = File(attachmentsDir, "${id}_$safeSuffix")
                    resolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output, bufferSize = 32 * 1024)
                        }
                    } ?: throw IOException("Unable to read selected file.")

                    val extracted = extractReadableText(destFile, mimeType, rawName)
                    MessageAttachment(
                        id = id,
                        displayName = rawName,
                        mimeType = mimeType,
                        localPath = destFile.absolutePath,
                        isImage = false,
                        sizeBytes = destFile.length(),
                        extractedText = extracted
                    )
                }
            }
        }

    fun readBase64(attachment: MessageAttachment): String? {
        val file = File(attachment.localPath)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun readBytes(attachment: MessageAttachment): ByteArray? {
        val file = File(attachment.localPath)
        if (!file.exists()) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun compressImageForLlm(rawBytes: ByteArray): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            ?: return rawBytes
        val maxDim = 1280
        val width = decoded.width
        val height = decoded.height
        val scaled = if (width > maxDim || height > maxDim) {
            val ratio = minOf(maxDim.toFloat() / width, maxDim.toFloat() / height)
            val newW = (width * ratio).toInt().coerceAtLeast(1)
            val newH = (height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, newW, newH, true).also {
                if (it != decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        return out.toByteArray()
    }

    private fun extractReadableText(file: File, mimeType: String, name: String): String? {
        val textExtensions = setOf(
            "txt", "md", "markdown", "json", "xml", "csv", "tsv", "kt", "kts", "java",
            "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "hpp", "rs", "go", "rb",
            "php", "swift", "sh", "bash", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "html", "htm", "css", "scss", "sql", "gradle", "properties", "log", "env"
        )
        val ext = name.substringAfterLast('.', "").lowercase()
        val isTextLike = mimeType.startsWith("text/", ignoreCase = true) ||
            mimeType.contains("json", ignoreCase = true) ||
            mimeType.contains("xml", ignoreCase = true) ||
            ext in textExtensions

        if (!isTextLike) return null

        return runCatching {
            val maxChars = 28_000
            val fullText = file.readText(Charsets.UTF_8)
            if (fullText.length > maxChars) {
                fullText.take(maxChars) + "\n\n... [truncated after $maxChars characters]"
            } else {
                fullText
            }
        }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }
}
