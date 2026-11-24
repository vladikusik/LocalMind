package com.example.data.huggingface

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HfRepoSummary(
    val repoId: String,
    val author: String,
    val downloads: Long,
    val likes: Int,
    val isGated: Boolean,
    val tags: List<String>
)

data class HfModelFile(
    val repoId: String,
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val format: String, // "task", "bin", "litertlm", "gguf"
    val isOnDeviceRunnable: Boolean
)

sealed interface ModelDownloadProgress {
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) : ModelDownloadProgress

    data class Completed(
        val file: File,
        val sizeBytes: Long
    ) : ModelDownloadProgress
}

class HuggingFaceClient(private val context: Context) {

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val mlcDownloader = com.example.data.mlc.MlcModelPackageDownloader(context)

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    suspend fun searchRepositories(
        query: String,
        hfToken: String,
        authorFilter: String? = null
    ): Result<List<HfRepoSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val urlBuilder = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
                .addQueryParameter("sort", "downloads")
                .addQueryParameter("direction", "-1")
                .addQueryParameter("limit", "25")

            if (!authorFilter.isNullOrBlank()) {
                urlBuilder.addQueryParameter("author", authorFilter.trim())
            }
            if (query.isNotBlank()) {
                urlBuilder.addQueryParameter("search", query.trim())
            }

            val reqBuilder = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .header("Accept", "application/json")

            if (hfToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
            }

            apiClient.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Hugging Face HTTP ${response.code}: ${response.message}")
                }
                val jsonArray = JSONArray(body)
                buildList {
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.optJSONObject(i) ?: continue
                        val repoId = obj.optString("id").ifBlank { obj.optString("modelId") }
                        if (repoId.isBlank()) continue
                        val tagsJson = obj.optJSONArray("tags")
                        val tags = buildList {
                            if (tagsJson != null) {
                                for (t in 0 until minOf(tagsJson.length(), 6)) {
                                    tagsJson.optString(t).takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }
                        }
                        val gatedRaw = obj.opt("gated")
                        val isGated = gatedRaw == true || (gatedRaw is String && gatedRaw.isNotBlank() && gatedRaw != "false")
                        add(
                            HfRepoSummary(
                                repoId = repoId,
                                author = repoId.substringBefore('/', ""),
                                downloads = obj.optLong("downloads", 0L),
                                likes = obj.optInt("likes", 0),
                                isGated = isGated,
                                tags = tags
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun fetchModelFiles(
        repoId: String,
        hfToken: String
    ): Result<List<HfModelFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedSegments = repoId.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val url = "https://huggingface.co/api/models/$encodedSegments/tree/main?recursive=true"
            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")

            if (hfToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
            }

            apiClient.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val hint = if (response.code == 401 || response.code == 403) {
                        " (Gated repo: add your Hugging Face token in Settings)"
                    } else {
                        ""
                    }
                    throw IOException("HTTP ${response.code}$hint")
                }

                val isLocalDream = com.example.data.imagegen.LocalDreamCatalog.isLocalDreamRepo(repoId)
                val array = JSONArray(body)

                // Detect MLC Chat compiled bundles (mlc-chat-config.json + params_shard_*.bin)
                var hasMlcConfig = false
                var mlcTotalShardBytes = 0L
                var mlcShardCount = 0
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val p = item.optString("path").lowercase()
                    if (p == "mlc-chat-config.json") {
                        hasMlcConfig = true
                    } else if (p.startsWith("params_shard_") && p.endsWith(".bin")) {
                        mlcShardCount++
                        val lfsSize = item.optJSONObject("lfs")?.optLong("size", -1L) ?: -1L
                        mlcTotalShardBytes += if (lfsSize > 0L) lfsSize else item.optLong("size", 0L)
                    }
                }

                buildList {
                    if (hasMlcConfig) {
                        val bundleTitle = "${repoId.substringAfterLast('/')} (${mlcShardCount.coerceAtLeast(1)} shards).mlc"
                        add(
                            HfModelFile(
                                repoId = repoId,
                                path = "mlc-chat-config.json",
                                fileName = bundleTitle,
                                sizeBytes = mlcTotalShardBytes,
                                format = "mlc",
                                isOnDeviceRunnable = true
                            )
                        )
                    }
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        if (item.optString("type") != "file") continue
                        val path = item.optString("path")
                        if (path.isBlank()) continue
                        val lower = path.lowercase()
                        if (hasMlcConfig && lower.startsWith("params_shard_")) continue
                        val format = when {
                            lower.endsWith(".task") -> "task"
                            lower.endsWith(".litertlm") -> "litertlm"
                            lower.endsWith(".gguf") -> "gguf"
                            lower.endsWith(".safetensors") -> "safetensors"
                            lower.endsWith(".ckpt") -> "ckpt"
                            lower.endsWith(".mnn") -> "mnn"
                            lower.endsWith(".dlc") -> "dlc"
                            lower.endsWith(".zip") && isLocalDream -> "zip"
                            lower.endsWith(".bin") && !lower.contains("pytorch_model") && !lower.contains("training_args") -> {
                                if (isLocalDream) "qnn" else "bin"
                            }
                            else -> continue
                        }
                        val lfsSize = item.optJSONObject("lfs")?.optLong("size", -1L) ?: -1L
                        val size = if (lfsSize > 0L) lfsSize else item.optLong("size", 0L)
                        add(
                            HfModelFile(
                                repoId = repoId,
                                path = path,
                                fileName = path.substringAfterLast('/'),
                                sizeBytes = size,
                                format = format,
                                isOnDeviceRunnable = format in setOf(
                                    "mlc", "gguf", "litertlm", "task", "bin", "safetensors", "ckpt", "mnn", "dlc", "qnn", "zip"
                                )
                            )
                        )
                    }
                }.sortedWith(
                    compareByDescending<HfModelFile> { it.isOnDeviceRunnable }
                        .thenBy { it.sizeBytes }
                )
            }
        }
    }

    fun downloadModelFile(
        modelFile: HfModelFile,
        hfToken: String
    ): Flow<ModelDownloadProgress> {
        if (modelFile.format.equals("mlc", ignoreCase = true)) {
            return mlcDownloader.downloadMlcBundle(modelFile.repoId, hfToken)
        }
        return flow {
        val encodedRepo = modelFile.repoId.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val encodedPath = modelFile.path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val downloadUrl = "https://huggingface.co/$encodedRepo/resolve/main/$encodedPath"

        val safeFileName = "${modelFile.repoId.replace('/', '_')}__${modelFile.fileName}"
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val finalFile = File(modelsDir, safeFileName)
        val tempFile = File(modelsDir, "$safeFileName.part")

        val reqBuilder = Request.Builder()
            .url(downloadUrl)
            .get()

        if (hfToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
        }

        val call = downloadClient.newCall(reqBuilder.build())
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val hint = if (response.code == 401 || response.code == 403) {
                        "Access denied (HTTP ${response.code}). Accept the model license on Hugging Face and enter your HF token."
                    } else {
                        "Download failed with HTTP ${response.code}: ${response.message}"
                    }
                    throw IOException(hint)
                }

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("text/html")) {
                    throw IOException(
                        "Hugging Face returned a web page instead of the model file (gated or requires token). Add your HF token in Model Hub."
                    )
                }

                val body = response.body ?: throw IOException("Empty download body")
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: modelFile.sizeBytes
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                val startTimeMs = System.currentTimeMillis()
                var lastEmitMs = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 180L) {
                                lastEmitMs = now
                                val elapsedSec = ((now - startTimeMs) / 1000.0).coerceAtLeast(0.1)
                                val speed = (downloaded / elapsedSec).toLong()
                                emit(
                                    ModelDownloadProgress.Downloading(
                                        bytesDownloaded = downloaded,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = speed
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    throw IOException("Failed to finalize downloaded model file.")
                }

                emit(
                    ModelDownloadProgress.Completed(
                        file = finalFile,
                        sizeBytes = finalFile.length()
                    )
                )
            }
        } catch (e: Throwable) {
            call.cancel()
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }.flowOn(Dispatchers.IO)
    }

    suspend fun importLocalModelUri(
        contentResolver: ContentResolver,
        uri: Uri
    ): Result<Pair<String, File>> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(contentResolver, uri) ?: "imported_model.task"
            val safeName = "local__${fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
            val destFile = File(modelsDir, safeName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: throw IOException("Unable to read selected file.")
            fileName to destFile
        }
    }

    fun deleteLocalFile(path: String) {
        runCatching {
            val f = File(path)
            if (f.exists()) {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }
}
