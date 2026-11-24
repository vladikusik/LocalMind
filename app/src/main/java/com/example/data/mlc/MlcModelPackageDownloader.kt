package com.example.data.mlc

import android.content.Context
import com.example.data.huggingface.ModelDownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Multi-shard MLC Chat package downloader modeled on `MLCChat/AppViewModel.kt`.
 *
 * Downloads a complete MLC model directory from Hugging Face:
 * 1. `mlc-chat-config.json` (quantization, context_window_size, prefill_chunk_size, tokenizer list)
 * 2. `ndarray-cache.json` (weight shard manifest with exact byte sizes)
 * 3. Tokenizer files (`tokenizer.json`, `tokenizer_config.json`, etc.)
 * 4. All `params_shard_*.bin` binary shards with automatic resume (skips already-verified shards)
 */
class MlcModelPackageDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val mlcRootDir: File
        get() = File(File(context.filesDir, "models"), "mlc").apply { if (!exists()) mkdirs() }

    fun downloadMlcBundle(
        repoId: String,
        hfToken: String
    ): Flow<ModelDownloadProgress> = flow {
        val safeDirName = repoId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetDir = File(mlcRootDir, safeDirName).apply { if (!exists()) mkdirs() }

        // 1. Fetch mlc-chat-config.json
        val configFile = File(targetDir, "mlc-chat-config.json")
        downloadSingleFile(repoId, "mlc-chat-config.json", configFile, hfToken)
        val chatConfig = MlcChatConfig.parse(configFile.readText())

        // 2. Fetch ndarray-cache.json
        val cacheIndexFile = File(targetDir, "ndarray-cache.json")
        downloadSingleFile(repoId, "ndarray-cache.json", cacheIndexFile, hfToken)
        val cacheManifest = MlcNdArrayCache.parse(cacheIndexFile.readText())

        // 3. Download tokenizer files (skip missing optional ones gracefully)
        for (tokFileName in chatConfig.tokenizerFiles.distinct()) {
            currentCoroutineContext().ensureActive()
            val dest = File(targetDir, tokFileName)
            if (!dest.exists() || dest.length() == 0L) {
                runCatching { downloadSingleFile(repoId, tokFileName, dest, hfToken) }
            }
        }

        // 4. Download all weight shards with byte-size verification & resume support
        val totalBytes = cacheManifest.totalBytes.coerceAtLeast(1L)
        var completedBytes = 0L
        val startTimeMs = System.currentTimeMillis()
        var lastEmitMs = 0L

        for (shard in cacheManifest.records) {
            currentCoroutineContext().ensureActive()
            val shardFile = File(targetDir, shard.dataPath)
            if (shardFile.exists() && shard.nbytes > 0L && shardFile.length() == shard.nbytes) {
                completedBytes += shardFile.length()
                emit(
                    ModelDownloadProgress.Downloading(
                        bytesDownloaded = completedBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = 0L
                    )
                )
                continue
            }

            val partFile = File(targetDir, "${shard.dataPath}.part")
            val url = buildHfResolveUrl(repoId, shard.dataPath)
            val req = buildRequest(url, hfToken)
            val call = client.newCall(req)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Failed to fetch MLC shard ${shard.dataPath}: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty MLC shard body: ${shard.dataPath}")
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read == -1) break
                                output.write(buf, 0, read)
                                completedBytes += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs >= 180L) {
                                    lastEmitMs = now
                                    val elapsedSec = ((now - startTimeMs) / 1000.0).coerceAtLeast(0.1)
                                    emit(
                                        ModelDownloadProgress.Downloading(
                                            bytesDownloaded = completedBytes,
                                            totalBytes = totalBytes,
                                            speedBytesPerSec = (completedBytes / elapsedSec).toLong()
                                        )
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                }
                if (shardFile.exists()) shardFile.delete()
                if (!partFile.renameTo(shardFile)) {
                    throw IOException("Could not finalize MLC shard ${shard.dataPath}")
                }
            } catch (e: Throwable) {
                call.cancel()
                if (partFile.exists()) partFile.delete()
                throw e
            }
        }

        val finalTotalSize = targetDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        emit(
            ModelDownloadProgress.Completed(
                file = targetDir,
                sizeBytes = finalTotalSize
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun downloadSingleFile(
        repoId: String,
        path: String,
        dest: File,
        hfToken: String
    ) {
        val url = buildHfResolveUrl(repoId, path)
        val req = buildRequest(url, hfToken)
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                val hint = if (response.code == 401 || response.code == 403) {
                    " (Gated repo: add Hugging Face token in Model Hub)"
                } else ""
                throw IOException("Failed to fetch $path: HTTP ${response.code}$hint")
            }
            val body = response.body ?: throw IOException("Empty response for $path")
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                body.byteStream().copyTo(out, bufferSize = 32 * 1024)
                out.flush()
            }
        }
    }

    private fun buildHfResolveUrl(repoId: String, filePath: String): String {
        val encodedRepo = repoId.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val encodedPath = filePath.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "https://huggingface.co/$encodedRepo/resolve/main/$encodedPath"
    }

    private fun buildRequest(url: String, hfToken: String): Request {
        val builder = Request.Builder().url(url).get()
        if (hfToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${hfToken.trim()}")
        }
        return builder.build()
    }
}
