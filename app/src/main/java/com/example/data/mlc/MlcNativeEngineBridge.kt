package com.example.data.mlc

import android.content.Context
import com.example.R
import com.example.data.network.ChatMessageDto
import com.example.data.network.StreamEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class MlcAppModelEntry(
    val modelId: String,
    val modelLib: String,
    val modelUrl: String
)

data class MlcRuntimeStatus(
    val isTvmRuntimeBundled: Boolean,
    val isMlc4jClassesAvailable: Boolean,
    val activeGpuBackend: String,
    val hasOpenCl: Boolean,
    val hasVulkan: Boolean,
    val bundledModelLibs: List<MlcAppModelEntry> = emptyList()
) {
    val isNativeMlcReady: Boolean
        get() = isTvmRuntimeBundled && isMlc4jClassesAvailable
}

/**
 * Native MLC-LLM (`mlc4j` / Apache TVM `JSONFFIEngine`) runtime bridge for Android,
 * faithfully implementing `mlc-ai/mlc-llm`'s dual-thread background loop architecture:
 *
 * - Background Engine Thread (`runBackgroundLoop`) drives TVM OpenCL/Vulkan GPU kernels
 *   with PagedKVCache & chunked prefill.
 * - Stream-Back Thread (`runBackgroundStreamBackLoop`) delivers OpenAI JSON-FFI token chunks
 *   and `usage.extra` prefill/decode metrics without stalling GPU decode.
 * - Resolves compiled `model_lib` symbols from APK-bundled `assets/mlc-app-config.json`
 *   (produced by `mlc_llm package` during CI build).
 *
 * Google Play Policy Compliant: strictly loads only APK-bundled native libraries from
 * `nativeLibraryDir` (never downloads `.so`/`.jar`/`.dex` at runtime).
 */
class MlcNativeEngineBridge(private val context: Context) {

    @Volatile
    private var jsonFfiEngine: Any? = null
    @Volatile
    private var loadedModelDir: String? = null
    @Volatile
    private var loadedModelLib: String? = null
    @Volatile
    private var loopsStarted = false
    @Volatile
    private var cachedAppModels: List<MlcAppModelEntry>? = null

    private val bgExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "LocalMind-MLC-TVM").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

    private val activeCallbackLock = Any()
    private val activeRequestCallbacks = mutableMapOf<String, (String) -> Unit>()
    private val lock = Any()

    fun inspectStatus(): MlcRuntimeStatus {
        val nativeDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        val nativeFiles = if (nativeDir.isNotBlank()) {
            runCatching { File(nativeDir).listFiles()?.map { it.name }.orEmpty() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val hasTvmLib = nativeFiles.any {
            it.contains("tvm4j_runtime_packed", ignoreCase = true) ||
                it.contains("mlc_llm", ignoreCase = true)
        }
        val hasMlc4jClass = runCatching {
            Class.forName("ai.mlc.mlcllm.JSONFFIEngine")
            true
        }.getOrDefault(false)

        val hasOpenCl = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so"
        ).any { runCatching { File(it).exists() }.getOrDefault(false) }

        val hasVulkan = listOf(
            "/system/lib64/libvulkan.so",
            "/vendor/lib64/libvulkan.so"
        ).any { runCatching { File(it).exists() }.getOrDefault(false) }

        val gpuBackend = when {
            hasOpenCl -> "TVM OpenCL GPU (Adreno/Mali)"
            hasVulkan -> "TVM Vulkan GPU"
            else -> "TVM CPU"
        }

        return MlcRuntimeStatus(
            isTvmRuntimeBundled = hasTvmLib,
            isMlc4jClassesAvailable = hasMlc4jClass,
            activeGpuBackend = gpuBackend,
            hasOpenCl = hasOpenCl,
            hasVulkan = hasVulkan,
            bundledModelLibs = loadBundledAppConfig()
        )
    }

    fun streamChatCompletion(
        modelDir: File,
        displayName: String,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> = callbackFlow {
        val status = inspectStatus()
        val config = MlcChatConfig.loadFromDir(modelDir)

        if (!status.isNativeMlcReady) {
            val quant = config?.quantization ?: "q4f16_1"
            close(
                IOException(
                    context.getString(R.string.mlc_runtime_missing_error, displayName, quant)
                )
            )
            return@callbackFlow
        }

        val engine = try {
            ensureReloaded(modelDir, config, status.bundledModelLibs)
        } catch (t: Throwable) {
            close(IOException("MLC TVM engine reload failed: ${t.localizedMessage}", t))
            return@callbackFlow
        }

        val requestId = "mlc_${UUID.randomUUID().toString().take(12)}"
        val finished = AtomicBoolean(false)
        val requestStartNs = System.nanoTime()
        var firstTokenNs = 0L
        var decodeChunks = 0
        val modelLabel = "$displayName (MLC · ${status.activeGpuBackend})"

        synchronized(activeCallbackLock) {
            activeRequestCallbacks[requestId] = { responsesJson ->
                if (!finished.get()) {
                    try {
                        val arr = JSONArray(responsesJson)
                        for (i in 0 until arr.length()) {
                            val resp = arr.optJSONObject(i) ?: continue
                            val choices = resp.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val choice = choices.optJSONObject(0)
                                val deltaObj = choice?.optJSONObject("delta")
                                val delta = if (deltaObj != null && !deltaObj.isNull("content")) {
                                    deltaObj.optString("content", "")
                                } else {
                                    ""
                                }
                                if (delta.isNotEmpty()) {
                                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                                    decodeChunks++
                                    trySend(StreamEvent.Chunk(contentDelta = delta, modelName = modelLabel))
                                }
                                val finishReason = if (choice != null && !choice.isNull("finish_reason")) {
                                    choice.optString("finish_reason", "")
                                } else {
                                    ""
                                }
                                if (finishReason.isNotBlank() && finishReason != "null") {
                                    val usage = resp.optJSONObject("usage")
                                    val extra = usage?.optJSONObject("extra")
                                    val decodeTps = extra?.optDouble("decode_tokens_per_s", Double.NaN)
                                        ?.takeIf { !it.isNaN() && it > 0.0 }?.toFloat()
                                        ?: computeDecodeTps(firstTokenNs, decodeChunks)
                                    val prefillTps = extra?.optDouble("prefill_tokens_per_s", Double.NaN)
                                        ?.takeIf { !it.isNaN() && it > 0.0 }?.toFloat()
                                    val ttftMs = if (firstTokenNs > 0L) {
                                        (firstTokenNs - requestStartNs) / 1_000_000L
                                    } else 0L

                                    if (finished.compareAndSet(false, true)) {
                                        trySend(
                                            StreamEvent.Completed(
                                                tokensPerSecond = decodeTps,
                                                modelName = modelLabel,
                                                ttftMs = ttftMs,
                                                prefillTokensPerSec = prefillTps,
                                                kvCacheReused = true
                                            )
                                        )
                                        close()
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        if (finished.compareAndSet(false, true)) {
                            close(IOException("MLC stream parse error: ${t.localizedMessage}", t))
                        }
                    }
                }
            }
        }

        val payload = MlcModelCatalog.buildJsonFfiRequest(
            messages = messages.map { it.role to it.content },
            temperature = temperature,
            maxTokens = maxTokens
        )

        try {
            val chatCompletionMethod = engine.javaClass.getMethod(
                "chatCompletion",
                String::class.java,
                String::class.java
            )
            chatCompletionMethod.invoke(engine, payload, requestId)
        } catch (t: Throwable) {
            synchronized(activeCallbackLock) { activeRequestCallbacks.remove(requestId) }
            if (finished.compareAndSet(false, true)) {
                close(IOException("MLC chatCompletion invocation failed: ${t.localizedMessage}", t))
            }
        }

        awaitClose {
            synchronized(activeCallbackLock) { activeRequestCallbacks.remove(requestId) }
            if (finished.compareAndSet(false, true)) {
                runCatching {
                    engine.javaClass.getMethod("abort", String::class.java).invoke(engine, requestId)
                }
            }
        }
    }

    private fun ensureReloaded(
        modelDir: File,
        config: MlcChatConfig?,
        bundledModels: List<MlcAppModelEntry>
    ): Any {
        synchronized(lock) {
            runCatching { System.loadLibrary("tvm4j_runtime_packed") }

            val engineClass = Class.forName("ai.mlc.mlcllm.JSONFFIEngine")
            var engine = jsonFfiEngine
            if (engine == null) {
                engine = engineClass.getDeclaredConstructor().newInstance()
                val callbackInterface = Class.forName("ai.mlc.mlcllm.JSONFFIEngine\$KotlinFunction")
                val callbackProxy = Proxy.newProxyInstance(
                    callbackInterface.classLoader,
                    arrayOf(callbackInterface)
                ) { _, method, args ->
                    if (method.name == "invoke" && args != null && args.isNotEmpty()) {
                        val responseJson = args[0] as? String ?: return@newProxyInstance null
                        dispatchStreamResponses(responseJson)
                    }
                    null
                }
                engineClass.getMethod("initBackgroundEngine", callbackInterface)
                    .invoke(engine, callbackProxy)

                if (!loopsStarted) {
                    loopsStarted = true
                    bgExecutor.execute {
                        runCatching { engineClass.getMethod("runBackgroundLoop").invoke(engine) }
                    }
                    bgExecutor.execute {
                        runCatching { engineClass.getMethod("runBackgroundStreamBackLoop").invoke(engine) }
                    }
                }
                jsonFfiEngine = engine
            }

            val modelLib = resolveCompiledModelLib(modelDir, config, bundledModels)
            if (loadedModelDir != modelDir.absolutePath || loadedModelLib != modelLib) {
                val cleanLib = modelLib.removePrefix("system://")
                val systemLibUri = "system://$cleanLib"
                val reloadSingleArg = runCatching {
                    engineClass.getMethod("reload", String::class.java)
                }.getOrNull()

                if (reloadSingleArg != null) {
                    val reloadPayload = JSONObject().apply {
                        put("model", modelDir.absolutePath)
                        put("model_lib", systemLibUri)
                        put("mode", "interactive")
                    }.toString()
                    reloadSingleArg.invoke(engine, reloadPayload)
                } else {
                    engineClass.getMethod("reload", String::class.java, String::class.java)
                        .invoke(engine, modelDir.absolutePath, cleanLib)
                }
                loadedModelDir = modelDir.absolutePath
                loadedModelLib = modelLib
            }
            return engine
        }
    }

    fun getBundledRepoIds(): List<String> {
        return loadBundledAppConfig().mapNotNull { entry ->
            val url = entry.modelUrl.trim().trimEnd('/')
            when {
                url.contains("huggingface.co/") -> {
                    val tail = url.substringAfter("huggingface.co/").removeSuffix("/resolve/main")
                    val parts = tail.split('/').filter { it.isNotBlank() }
                    if (parts.size >= 2) "${parts[0]}/${parts[1]}" else null
                }
                url.startsWith("HF://", ignoreCase = true) -> {
                    val tail = url.substring(5).trim('/')
                    if (tail.contains('/')) tail else null
                }
                entry.modelId.isNotBlank() -> "mlc-ai/${entry.modelId}"
                else -> null
            }
        }.distinct()
    }

    /**
     * Resolves the exact TVM `model_lib` symbol compiled into `libtvm4j_runtime_packed.so`.
     * Hugging Face `mlc-chat-config.json` typically omits `model_lib`, so we match against
     * APK-bundled `assets/mlc-app-config.json` (generated by `mlc_llm package`) by model_id,
     * repo URL, or architecture + quantization prefix.
     */
    private fun resolveCompiledModelLib(
        modelDir: File,
        config: MlcChatConfig?,
        bundledModels: List<MlcAppModelEntry>
    ): String {
        val explicit = config?.modelLib?.trim().orEmpty()
        if (explicit.isNotBlank()) return explicit

        val dirNameLower = modelDir.name.lowercase()
        // 1. Exact match by model_id or repo URL segment in bundled mlc-app-config.json
        bundledModels.firstOrNull { entry ->
            val idLower = entry.modelId.lowercase()
            val urlSlug = entry.modelUrl.trimEnd('/').substringAfterLast('/').lowercase()
            (idLower.isNotBlank() && dirNameLower.contains(idLower)) ||
                (urlSlug.isNotBlank() && dirNameLower.contains(urlSlug))
        }?.let { return it.modelLib }

        // 2. Architecture + quantization prefix match (e.g. llama_q4f16_1_...)
        val modelType = config?.modelType?.lowercase()?.replace("-", "").orEmpty()
        val quant = config?.quantization?.lowercase().orEmpty()
        if (modelType.isNotBlank() && quant.isNotBlank()) {
            val prefix = "${modelType}_${quant}"
            bundledModels.firstOrNull { entry ->
                entry.modelLib.lowercase().startsWith(prefix)
            }?.let { return it.modelLib }
        }

        if (bundledModels.isNotEmpty()) {
            val supported = bundledModels.joinToString(", ") { it.modelId.ifBlank { it.modelLib } }
            throw IOException(
                "No compiled TVM model_lib found in this APK for '${modelDir.name}'. " +
                    "Bundled MLC kernels: $supported."
            )
        }

        return "${modelType.ifBlank { "llama" }}_${quant.ifBlank { "q4f16_1" }}"
    }

    private fun loadBundledAppConfig(): List<MlcAppModelEntry> {
        cachedAppModels?.let { return it }
        val parsed = runCatching {
            val jsonText = context.assets.open("mlc-app-config.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val arr = root.optJSONArray("model_list") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val lib = item.optString("model_lib").trim()
                    if (lib.isNotBlank()) {
                        add(
                            MlcAppModelEntry(
                                modelId = item.optString("model_id").trim(),
                                modelLib = lib,
                                modelUrl = item.optString("model_url").trim()
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
        cachedAppModels = parsed
        return parsed
    }

    private fun dispatchStreamResponses(responsesJson: String) {
        val snapshot = synchronized(activeCallbackLock) { activeRequestCallbacks.toMap() }
        if (snapshot.isEmpty()) return
        val firstId = runCatching {
            val arr = JSONArray(responsesJson)
            arr.optJSONObject(0)?.optString("id").orEmpty()
        }.getOrDefault("")
        val cb = if (firstId.isNotBlank()) snapshot[firstId] else snapshot.values.firstOrNull()
        cb?.invoke(responsesJson)
    }

    fun resetConversation() {
        synchronized(lock) {
            val engine = jsonFfiEngine ?: return
            runCatching { engine.javaClass.getMethod("reset").invoke(engine) }
        }
    }

    fun unload() {
        synchronized(lock) {
            val engine = jsonFfiEngine ?: return
            runCatching { engine.javaClass.getMethod("unload").invoke(engine) }
            loadedModelDir = null
            loadedModelLib = null
        }
    }

    private fun computeDecodeTps(firstTokenNs: Long, decodeChunks: Int): Float? {
        if (firstTokenNs == 0L || decodeChunks <= 2) return null
        val decodeSec = (System.nanoTime() - firstTokenNs) / 1_000_000_000f
        return if (decodeSec > 0.05f) (decodeChunks - 1) / decodeSec else null
    }
}
