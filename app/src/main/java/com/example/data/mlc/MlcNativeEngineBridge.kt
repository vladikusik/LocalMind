package com.example.data.mlc

import android.content.Context
import android.util.Log
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * - Resolves compiled `model_lib` symbols strictly from APK-bundled `assets/mlc-app-config.json`
 *   (extracted from official prebuilt `mlc-chat.apk` during CI build).
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
    private var activeDeviceLabel: String? = null
    @Volatile
    private var loopsStarted = false
    @Volatile
    private var backgroundLoopError: Throwable? = null
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
    private val activeErrorCallbacks = mutableMapOf<String, (Throwable) -> Unit>()
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

        val gpuBackend = activeDeviceLabel ?: when {
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
            ensureReloaded(modelDir, config, status)
        } catch (t: Throwable) {
            val formatted = if (t is MlcUserFacingException) {
                t.message.orEmpty()
            } else {
                "MLC TVM engine reload failed: ${formatThrowable("reload", t)}"
            }
            Log.e(TAG, formatted, t)
            close(IOException(formatted, unwrapThrowable(t)))
            return@callbackFlow
        }

        val requestId = "mlc_${UUID.randomUUID().toString().take(12)}"
        val finished = AtomicBoolean(false)
        val requestStartNs = System.nanoTime()
        var firstTokenNs = 0L
        var decodeChunks = 0
        val backendName = activeDeviceLabel ?: status.activeGpuBackend
        val modelLabel = "$displayName (MLC · $backendName)"

        synchronized(activeCallbackLock) {
            activeErrorCallbacks[requestId] = { bgErr ->
                if (finished.compareAndSet(false, true)) {
                    close(
                        IOException(
                            "MLC TVM background engine error: ${formatThrowable("bgLoop", bgErr)}",
                            unwrapThrowable(bgErr)
                        )
                    )
                }
            }
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
                            close(
                                IOException(
                                    "MLC stream parse error: ${formatThrowable("streamParse", t)}",
                                    unwrapThrowable(t)
                                )
                            )
                        }
                    }
                }
            }
        }

        backgroundLoopError?.let { bgErr ->
            synchronized(activeCallbackLock) {
                activeRequestCallbacks.remove(requestId)
                activeErrorCallbacks.remove(requestId)
            }
            if (finished.compareAndSet(false, true)) {
                close(
                    IOException(
                        "MLC TVM background loop stopped: ${formatThrowable("bgLoop", bgErr)}",
                        unwrapThrowable(bgErr)
                    )
                )
            }
            return@callbackFlow
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
            synchronized(activeCallbackLock) {
                activeRequestCallbacks.remove(requestId)
                activeErrorCallbacks.remove(requestId)
            }
            if (finished.compareAndSet(false, true)) {
                close(
                    IOException(
                        "MLC chatCompletion failed: ${formatThrowable("chatCompletion", t)}",
                        unwrapThrowable(t)
                    )
                )
            }
        }

        awaitClose {
            synchronized(activeCallbackLock) {
                activeRequestCallbacks.remove(requestId)
                activeErrorCallbacks.remove(requestId)
            }
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
        status: MlcRuntimeStatus
    ): Any {
        synchronized(lock) {
            // 1. Validate downloaded MLC bundle integrity BEFORE touching native TVM runtime
            validateModelBundle(modelDir, config)

            // 2. Strictly resolve compiled model_lib symbol BEFORE initializing TVM
            val modelLib = resolveCompiledModelLib(modelDir, config, status.bundledModelLibs)

            // 3. Explicitly load libtvm4j_runtime_packed.so and surface any dlopen/linker error
            try {
                System.loadLibrary("tvm4j_runtime_packed")
            } catch (e: UnsatisfiedLinkError) {
                throw IOException(formatThrowable("loadLibrary", e), e)
            }

            val engineClass = try {
                Class.forName("ai.mlc.mlcllm.JSONFFIEngine")
            } catch (t: Throwable) {
                throw IOException(formatThrowable("loadClass", t), unwrapThrowable(t))
            }

            // Reset engine reference if background loop previously crashed
            if (backgroundLoopError != null) {
                jsonFfiEngine = null
                loopsStarted = false
                loadedModelDir = null
                loadedModelLib = null
                backgroundLoopError = null
            }

            var engine = jsonFfiEngine
            if (engine == null) {
                engine = try {
                    engineClass.getDeclaredConstructor().newInstance()
                } catch (t: Throwable) {
                    throw IOException(formatThrowable("createEngine", t), unwrapThrowable(t))
                }

                val callbackInterface = try {
                    Class.forName("ai.mlc.mlcllm.JSONFFIEngine\$KotlinFunction")
                } catch (t: Throwable) {
                    throw IOException(formatThrowable("loadCallbackInterface", t), unwrapThrowable(t))
                }

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

                initEngineWithDeviceFallback(engineClass, engine, callbackInterface, callbackProxy, status)

                if (!loopsStarted) {
                    loopsStarted = true
                    backgroundLoopError = null
                    val startLatch = CountDownLatch(2)
                    bgExecutor.execute {
                        startLatch.countDown()
                        try {
                            engineClass.getMethod("runBackgroundLoop").invoke(engine)
                        } catch (t: Throwable) {
                            val root = unwrapThrowable(t)
                            Log.e(TAG, "TVM runBackgroundLoop failed", root)
                            backgroundLoopError = root
                            notifyBackgroundError(root)
                        }
                    }
                    bgExecutor.execute {
                        startLatch.countDown()
                        try {
                            engineClass.getMethod("runBackgroundStreamBackLoop").invoke(engine)
                        } catch (t: Throwable) {
                            val root = unwrapThrowable(t)
                            Log.e(TAG, "TVM runBackgroundStreamBackLoop failed", root)
                            backgroundLoopError = root
                            notifyBackgroundError(root)
                        }
                    }
                    runCatching { startLatch.await(500, TimeUnit.MILLISECONDS) }
                    runCatching { Thread.sleep(25) }
                }
                jsonFfiEngine = engine
            }

            if (loadedModelDir != modelDir.absolutePath || loadedModelLib != modelLib) {
                invokeReloadResilient(engineClass, engine, modelDir.absolutePath, modelLib)
                loadedModelDir = modelDir.absolutePath
                loadedModelLib = modelLib
            }
            return engine
        }
    }

    private fun initEngineWithDeviceFallback(
        engineClass: Class<*>,
        engine: Any,
        callbackInterface: Class<*>,
        callbackProxy: Any,
        status: MlcRuntimeStatus
    ) {
        val init3Arg = runCatching {
            engineClass.getMethod(
                "initBackgroundEngine",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                callbackInterface
            )
        }.getOrNull()

        if (init3Arg != null) {
            // TVM DLDeviceType: kDLOpenCL = 4, kDLVulkan = 7, kDLCPU = 1
            val candidates = buildList {
                if (status.hasOpenCl) add(4 to "TVM OpenCL GPU")
                if (status.hasVulkan) add(7 to "TVM Vulkan GPU")
                if (!status.hasOpenCl) add(4 to "TVM OpenCL GPU")
                add(1 to "TVM CPU")
            }.distinctBy { it.first }

            var lastError: Throwable? = null
            for ((deviceType, label) in candidates) {
                try {
                    init3Arg.invoke(engine, deviceType, 0, callbackProxy)
                    activeDeviceLabel = label
                    return
                } catch (t: Throwable) {
                    val root = unwrapThrowable(t)
                    Log.w(TAG, "initBackgroundEngine($label, type=$deviceType) failed: ${root.message}", root)
                    lastError = root
                }
            }
            throw IOException(
                formatThrowable("initBackgroundEngine", lastError ?: RuntimeException("No TVM device backend initialized")),
                lastError
            )
        }

        // Standard 1-arg upstream JSONFFIEngine.initBackgroundEngine(KotlinFunction)
        try {
            engineClass.getMethod("initBackgroundEngine", callbackInterface)
                .invoke(engine, callbackProxy)
            activeDeviceLabel = status.activeGpuBackend
        } catch (t: Throwable) {
            throw IOException(formatThrowable("initBackgroundEngine", t), unwrapThrowable(t))
        }
    }

    private fun invokeReloadResilient(
        engineClass: Class<*>,
        engine: Any,
        modelPath: String,
        modelLib: String
    ) {
        val cleanLib = modelLib.removePrefix("system://")
        val systemLibUri = "system://$cleanLib"
        val reload1Arg = runCatching {
            engineClass.getMethod("reload", String::class.java)
        }.getOrNull()
        val reload2Arg = runCatching {
            engineClass.getMethod("reload", String::class.java, String::class.java)
        }.getOrNull()

        val reloadPayload = JSONObject().apply {
            put("model", modelPath)
            put("model_lib", systemLibUri)
            put("mode", "interactive")
        }.toString()

        if (reload1Arg != null) {
            try {
                reload1Arg.invoke(engine, reloadPayload)
                return
            } catch (t: Throwable) {
                val root = unwrapThrowable(t)
                val msg = root.message.orEmpty()
                // Older mlc-chat.apk TVM runtime may expect 2-arg reload(modelPath, modelLib)
                val looksLikeArityMismatch = msg.contains("args.size()", ignoreCase = true) ||
                    msg.contains("expected 2", ignoreCase = true) ||
                    msg.contains("expects 2", ignoreCase = true)
                if (looksLikeArityMismatch && reload2Arg != null) {
                    try {
                        reload2Arg.invoke(engine, modelPath, systemLibUri)
                        return
                    } catch (t2: Throwable) {
                        try {
                            reload2Arg.invoke(engine, modelPath, cleanLib)
                            return
                        } catch (t3: Throwable) {
                            throw IOException(
                                formatThrowable("reload(2-arg, lib=$cleanLib)", t3),
                                unwrapThrowable(t3)
                            )
                        }
                    }
                }
                throw IOException(
                    formatThrowable("reload(lib=$cleanLib)", root),
                    root
                )
            }
        }

        if (reload2Arg != null) {
            try {
                reload2Arg.invoke(engine, modelPath, systemLibUri)
                return
            } catch (t: Throwable) {
                try {
                    reload2Arg.invoke(engine, modelPath, cleanLib)
                    return
                } catch (t2: Throwable) {
                    throw IOException(
                        formatThrowable("reload(2-arg, lib=$cleanLib)", t2),
                        unwrapThrowable(t2)
                    )
                }
            }
        }

        throw IOException("[reload] JSONFFIEngine exposes neither reload(String) nor reload(String, String)")
    }

    private fun validateModelBundle(modelDir: File, config: MlcChatConfig?) {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            throw MlcUserFacingException("MLC model folder not found: ${modelDir.absolutePath}")
        }
        val configFile = File(modelDir, "mlc-chat-config.json")
        if (!configFile.exists() || configFile.length() == 0L) {
            throw MlcUserFacingException(
                "Missing mlc-chat-config.json in ${modelDir.name}. Please re-download this model in Model Hub."
            )
        }
        val cacheFile = File(modelDir, "ndarray-cache.json")
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            throw MlcUserFacingException(
                "Missing ndarray-cache.json in ${modelDir.name}. Please re-download this model in Model Hub."
            )
        }
        val tokFiles = config?.tokenizerFiles.orEmpty().ifEmpty { listOf("tokenizer.json", "tokenizer.model") }
        val hasTokenizer = tokFiles.any { name ->
            val f = File(modelDir, name)
            f.exists() && f.length() > 0L
        } || File(modelDir, "tokenizer.json").length() > 0L || File(modelDir, "tokenizer.model").length() > 0L
        if (!hasTokenizer) {
            throw MlcUserFacingException(
                "Missing tokenizer files (${tokFiles.joinToString()}) in ${modelDir.name}. Please re-download this model in Model Hub."
            )
        }
        val manifest = runCatching { MlcNdArrayCache.parse(cacheFile.readText()) }.getOrNull()
        if (manifest != null && manifest.records.isNotEmpty()) {
            val missingShards = manifest.records.count { rec ->
                val shardFile = File(modelDir, rec.dataPath)
                !shardFile.exists() || shardFile.length() == 0L
            }
            if (missingShards > 0) {
                throw MlcUserFacingException(
                    "Incomplete MLC model download (${missingShards}/${manifest.records.size} shards missing). Please re-download this model in Model Hub."
                )
            }
        }
    }

    fun getBundledRepoIds(): List<String> {
        return loadBundledAppConfig().mapNotNull { entry ->
            extractRepoIdFromEntry(entry)
        }.distinct()
    }

    fun hasCompiledKernelForRepo(repoId: String): Boolean {
        val bundled = loadBundledAppConfig()
        if (bundled.isEmpty()) return false
        val normalizedRepo = repoId.trim().lowercase()
        val slug = normalizedRepo.substringAfterLast('/')
        return bundled.any { entry ->
            val entryRepo = extractRepoIdFromEntry(entry)?.lowercase().orEmpty()
            val entryId = entry.modelId.lowercase()
            val urlSlug = entry.modelUrl.trimEnd('/').substringAfterLast('/').lowercase()
            (entryRepo.isNotBlank() && entryRepo == normalizedRepo) ||
                (entryId.isNotBlank() && (entryId == slug || normalizedRepo.endsWith("/$entryId"))) ||
                (urlSlug.isNotBlank() && urlSlug == slug)
        }
    }

    private fun extractRepoIdFromEntry(entry: MlcAppModelEntry): String? {
        val url = entry.modelUrl.trim().trimEnd('/')
        return when {
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
    }

    /**
     * Strictly resolves the exact TVM `model_lib` symbol compiled into `libtvm4j_runtime_packed.so`.
     *
     * IMPORTANT: In TVM/MLC-LLM, compiled GPU kernels bake exact model layer dimensions
     * (hidden_size, num_layers, vocab_size). Matching a different-sized model (e.g. Llama-3.2-3B
     * against a Llama-3.2-1B or Llama-3-8B kernel) always crashes native `reload()`.
     * Therefore we match ONLY exact `model_id` / `model_url` / explicit compiled `model_lib`.
     */
    private fun resolveCompiledModelLib(
        modelDir: File,
        config: MlcChatConfig?,
        bundledModels: List<MlcAppModelEntry>
    ): String {
        val dirNameLower = modelDir.name.lowercase()
        val slugLower = dirNameLower.removePrefix("mlc-ai_").removePrefix("mlc-ai/")

        // 1. Exact match by model_id or repo URL segment in APK-bundled mlc-app-config.json
        bundledModels.firstOrNull { entry ->
            val idLower = entry.modelId.lowercase()
            val urlSlug = entry.modelUrl.trimEnd('/').substringAfterLast('/').lowercase()
            val repoFromUrl = extractRepoIdFromEntry(entry)?.replace('/', '_')?.lowercase().orEmpty()
            (idLower.isNotBlank() && (slugLower == idLower || dirNameLower == idLower || dirNameLower.endsWith("_$idLower"))) ||
                (urlSlug.isNotBlank() && (slugLower == urlSlug || dirNameLower.endsWith("_$urlSlug"))) ||
                (repoFromUrl.isNotBlank() && dirNameLower == repoFromUrl)
        }?.let { return it.modelLib }

        // 2. Explicit model_lib inside mlc-chat-config.json (only if bundled in APK or config list unavailable)
        val explicit = config?.modelLib?.trim()?.removePrefix("system://").orEmpty()
        if (explicit.isNotBlank()) {
            if (bundledModels.isEmpty() || bundledModels.any { it.modelLib.removePrefix("system://").equals(explicit, ignoreCase = true) }) {
                return explicit
            }
        }

        if (bundledModels.isNotEmpty()) {
            val supportedList = bundledModels.joinToString("\n") { entry ->
                val name = entry.modelId.ifBlank { entry.modelLib }
                "• $name"
            }
            throw MlcUserFacingException(
                context.getString(R.string.mlc_model_lib_not_bundled_error, modelDir.name, supportedList)
            )
        }

        val modelType = config?.modelType?.lowercase()?.replace("-", "").orEmpty()
        val quant = config?.quantization?.lowercase().orEmpty()
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

    private fun notifyBackgroundError(err: Throwable) {
        val callbacks = synchronized(activeCallbackLock) { activeErrorCallbacks.values.toList() }
        callbacks.forEach { cb -> runCatching { cb(err) } }
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

    private fun unwrapThrowable(t: Throwable): Throwable {
        var cur: Throwable = t
        var depth = 0
        while (depth < 8) {
            val next = when (cur) {
                is InvocationTargetException -> cur.targetException ?: cur.cause
                is ExceptionInInitializerError -> cur.exception ?: cur.cause
                else -> null
            }
            if (next == null || next === cur) break
            cur = next
            depth++
        }
        return cur
    }

    private fun formatThrowable(stage: String, t: Throwable): String {
        val root = unwrapThrowable(t)
        val rootName = root.javaClass.simpleName.ifBlank { root.javaClass.name }
        val rawMsg = root.message?.takeIf { it.isNotBlank() }
            ?: root.cause?.let { c ->
                val cName = c.javaClass.simpleName.ifBlank { c.javaClass.name }
                "$cName: ${c.message.orEmpty()}"
            }?.takeIf { it.isNotBlank() }
            ?: root.toString()
        val compactMsg = rawMsg.trim().take(600)
        return "[$stage] $rootName: $compactMsg"
    }

    private class MlcUserFacingException(message: String) : IOException(message)

    companion object {
        private const val TAG = "LocalMind-MLC"
    }
}
