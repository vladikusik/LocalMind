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
 * Native MLC-LLM (`mlc4j` / Apache TVM) runtime bridge for Android.
 *
 * Supports both official `mlc-chat.apk` runtime interfaces:
 * 1. `ai.mlc.mlcllm.ChatModule` (classic TVM OpenCL GPU engine bundled in official `mlc-chat.apk`,
 *    running all GPU calls on a single dedicated TVM thread with KV-cache reuse and UTF-8 safe
 *    delta streaming).
 * 2. `ai.mlc.mlcllm.JSONFFIEngine` (dual-thread background loop engine when present).
 *
 * Resolves compiled `model_lib` symbols strictly from APK-bundled `assets/mlc-app-config.json`.
 * Google Play Policy Compliant: strictly loads only APK-bundled native libraries from
 * `nativeLibraryDir` (never downloads `.so`/`.jar`/`.dex` at runtime).
 */
class MlcNativeEngineBridge(private val context: Context) {

    @Volatile
    private var chatModule: Any? = null
    @Volatile
    private var chatModuleModelDir: String? = null
    @Volatile
    private var chatModuleModelLib: String? = null
    @Volatile
    private var lastChatHistory: List<Pair<String, String>> = emptyList()

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

    // Single dedicated thread for ChatModule so TVM OpenCL/Vulkan GPU contexts stay thread-affine
    private val chatModuleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalMind-MLC-ChatModule").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

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
        }.getOrElse {
            runCatching {
                Class.forName("ai.mlc.mlcllm.ChatModule")
                true
            }.getOrDefault(false)
        }

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
    ): Flow<StreamEvent> {
        val jsonFfiClass = runCatching { Class.forName("ai.mlc.mlcllm.JSONFFIEngine") }.getOrNull()
        if (jsonFfiClass != null) {
            return streamViaJsonFfiEngine(
                modelDir = modelDir,
                displayName = displayName,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens
            )
        }
        val chatModuleClass = runCatching { Class.forName("ai.mlc.mlcllm.ChatModule") }.getOrNull()
        if (chatModuleClass != null) {
            return streamViaChatModule(
                chatModuleClass = chatModuleClass,
                modelDir = modelDir,
                displayName = displayName,
                messages = messages,
                maxTokens = maxTokens
            )
        }
        return streamViaJsonFfiEngine(
            modelDir = modelDir,
            displayName = displayName,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens
        )
    }

    private fun streamViaChatModule(
        chatModuleClass: Class<*>,
        modelDir: File,
        displayName: String,
        messages: List<ChatMessageDto>,
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

        val cancelled = AtomicBoolean(false)
        val future = chatModuleExecutor.submit {
            try {
                // 1. Validate downloaded MLC bundle integrity BEFORE touching native TVM runtime
                validateModelBundle(modelDir, config)

                // 2. Strictly resolve compiled model_lib symbol BEFORE initializing TVM
                val modelLib = resolveCompiledModelLib(modelDir, config, status.bundledModelLibs)
                val cleanLib = modelLib.removePrefix("system://")

                // 3. Explicitly load libtvm4j_runtime_packed.so
                try {
                    System.loadLibrary("tvm4j_runtime_packed")
                } catch (e: UnsatisfiedLinkError) {
                    throw IOException(formatThrowable("loadLibrary", e), e)
                }

                // 4. Create or reuse ChatModule on this dedicated TVM GPU thread
                var module = chatModule
                if (module == null) {
                    module = try {
                        chatModuleClass.getDeclaredConstructor().newInstance()
                    } catch (t: Throwable) {
                        throw IOException(formatThrowable("createChatModule", t), unwrapThrowable(t))
                    }
                    chatModule = module
                    activeDeviceLabel = if (status.hasOpenCl) "TVM OpenCL GPU" else "TVM GPU"
                }

                // 5. Ensure mlc-chat-config.json has all classic llm_chat.cc required fields & reload
                if (chatModuleModelDir != modelDir.absolutePath || chatModuleModelLib != cleanLib) {
                    if (chatModuleModelDir != null) {
                        runCatching { findMethod(chatModuleClass, "unload")?.invoke(module) }
                    }
                    ensureClassicChatModuleConfig(modelDir, cleanLib)
                    reloadChatModuleResilient(chatModuleClass, module, cleanLib, modelDir.absolutePath)
                    chatModuleModelDir = modelDir.absolutePath
                    chatModuleModelLib = cleanLib
                    lastChatHistory = emptyList()
                }

                if (cancelled.get()) return@submit

                val backendName = activeDeviceLabel ?: status.activeGpuBackend
                val modelLabel = "$displayName (MLC · $backendName)"

                // 6. Synchronize conversation KV cache
                val normalizedHistory = messages.map { it.role.trim().lowercase() to it.content }
                val canReuseKv = lastChatHistory.isNotEmpty() &&
                    normalizedHistory.size == lastChatHistory.size + 1 &&
                    normalizedHistory.dropLast(1) == lastChatHistory &&
                    normalizedHistory.last().first == "user"

                val promptToPrefill = if (canReuseKv) {
                    normalizedHistory.last().second
                } else {
                    if (lastChatHistory.isNotEmpty()) {
                        runCatching {
                            findMethod(chatModuleClass, "resetChat", "reset_chat")?.invoke(module)
                        }
                        lastChatHistory = emptyList()
                    }
                    buildInitialChatModulePrompt(normalizedHistory)
                }

                val prefillMethod = findMethod(chatModuleClass, "prefill", String::class.java)
                    ?: throw IOException("[ChatModule] Missing prefill(String) method")
                val decodeMethod = findMethod(chatModuleClass, "decode")
                    ?: throw IOException("[ChatModule] Missing decode() method")
                val getMessageMethod = findMethod(chatModuleClass, "getMessage", "get_message")
                    ?: throw IOException("[ChatModule] Missing getMessage() method")
                val stoppedMethod = findMethod(chatModuleClass, "stopped")
                    ?: throw IOException("[ChatModule] Missing stopped() method")
                val statsMethod = findMethod(chatModuleClass, "runtimeStatsText", "runtime_stats_text")

                val requestStartNs = System.nanoTime()
                var firstTokenNs = 0L

                // 7. Prefill prompt on TVM GPU
                try {
                    prefillMethod.invoke(module, promptToPrefill)
                } catch (t: Throwable) {
                    throw IOException(formatThrowable("prefill", t), unwrapThrowable(t))
                }

                val tokenCap = maxTokens.coerceIn(32, 4096)
                var emittedText = ""
                var decodeSteps = 0

                // 8. Stream token-by-token decode loop with UTF-8 multi-byte guard
                while (!cancelled.get() && decodeSteps < tokenCap) {
                    val isStopped = (stoppedMethod.invoke(module) as? Boolean) == true
                    if (isStopped) break

                    decodeMethod.invoke(module)
                    decodeSteps++

                    val rawFull = (getMessageMethod.invoke(module) as? String).orEmpty()
                    val safeFull = rawFull.trimEnd('\uFFFD')
                    val delta = when {
                        safeFull.length > emittedText.length && safeFull.startsWith(emittedText) ->
                            safeFull.substring(emittedText.length)
                        emittedText.isEmpty() && safeFull.isNotEmpty() ->
                            safeFull
                        else -> ""
                    }
                    if (delta.isNotEmpty()) {
                        if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                        emittedText = safeFull
                        trySend(StreamEvent.Chunk(contentDelta = delta, modelName = modelLabel))
                    }
                }

                if (cancelled.get()) {
                    lastChatHistory = emptyList()
                    return@submit
                }

                // Flush any trailing characters after stop
                val finalFull = (getMessageMethod.invoke(module) as? String).orEmpty()
                if (finalFull.length > emittedText.length && finalFull.startsWith(emittedText)) {
                    val tail = finalFull.substring(emittedText.length)
                    if (tail.isNotEmpty()) {
                        if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                        emittedText = finalFull
                        trySend(StreamEvent.Chunk(contentDelta = tail, modelName = modelLabel))
                    }
                }

                lastChatHistory = normalizedHistory + ("assistant" to emittedText)

                val statsText = runCatching { statsMethod?.invoke(module) as? String }.getOrNull().orEmpty()
                val parsedDecodeTps = parseStatsRate(statsText, "decode")
                    ?: computeDecodeTps(firstTokenNs, decodeSteps)
                val parsedPrefillTps = parseStatsRate(statsText, "prefill")
                val ttftMs = if (firstTokenNs > 0L) {
                    (firstTokenNs - requestStartNs) / 1_000_000L
                } else 0L

                trySend(
                    StreamEvent.Completed(
                        tokensPerSecond = parsedDecodeTps,
                        modelName = modelLabel,
                        ttftMs = ttftMs,
                        prefillTokensPerSec = parsedPrefillTps,
                        kvCacheReused = canReuseKv
                    )
                )
                close()
            } catch (t: Throwable) {
                lastChatHistory = emptyList()
                val formatted = when {
                    t is MlcUserFacingException -> t.message.orEmpty()
                    t is IOException && t.message?.startsWith("[") == true ->
                        "MLC TVM engine error: ${t.message}"
                    else -> "MLC TVM engine error: ${formatThrowable("chatModule", t)}"
                }
                Log.e(TAG, formatted, t)
                close(IOException(formatted, unwrapThrowable(t)))
            }
        }

        awaitClose {
            cancelled.set(true)
            future.cancel(false)
        }
    }

    /**
     * Ensures `mlc-chat-config.json` contains every key required by classic `mlc-llm/cpp/llm_chat.cc`
     * (`ChatModule.reload` with `partial_update == false`), while keeping an untouched backup in
     * `mlc-chat-config.orig.json`.
     *
     * Newer HuggingFace MLC repos (e.g. `Llama-3.2-3B-Instruct-q4f16_1-MLC`) omit legacy fields
     * (`mean_gen_len`, `max_gen_len`, `shift_fill_factor`, `max_window_size`, penalties) and store
     * `conv_template` as a nested JSON object instead of a string + `conv_config`.
     */
    private fun ensureClassicChatModuleConfig(
        modelDir: File,
        cleanLib: String,
        useMinimalLmTemplate: Boolean = false
    ) {
        val configFile = File(modelDir, "mlc-chat-config.json")
        if (!configFile.exists()) return
        val backupFile = File(modelDir, "mlc-chat-config.orig.json")
        if (!backupFile.exists()) {
            runCatching { configFile.copyTo(backupFile, overwrite = false) }
        }
        val sourceFile = if (backupFile.exists() && backupFile.length() > 0L) backupFile else configFile
        val root = runCatching { JSONObject(sourceFile.readText()) }.getOrNull() ?: return
        val modelCfg = root.optJSONObject("model_config")

        if (root.optString("model_lib").isBlank()) {
            root.put("model_lib", cleanLib)
        }
        if (root.optString("local_id").isBlank()) {
            root.put("local_id", modelDir.name)
        }
        if (!root.has("temperature") || root.isNull("temperature")) {
            root.put("temperature", 0.7)
        }
        if (!root.has("repetition_penalty") || root.isNull("repetition_penalty")) {
            root.put("repetition_penalty", 1.0)
        }
        if (!root.has("presence_penalty") || root.isNull("presence_penalty")) {
            root.put("presence_penalty", 0.0)
        }
        if (!root.has("frequency_penalty") || root.isNull("frequency_penalty")) {
            root.put("frequency_penalty", 0.0)
        }
        if (!root.has("top_p") || root.isNull("top_p")) {
            root.put("top_p", 0.95)
        }
        if (!root.has("mean_gen_len") || root.isNull("mean_gen_len")) {
            root.put("mean_gen_len", 128)
        }
        if (!root.has("max_gen_len") || root.isNull("max_gen_len")) {
            root.put("max_gen_len", 512)
        }
        if (!root.has("shift_fill_factor") || root.isNull("shift_fill_factor")) {
            root.put("shift_fill_factor", 0.3)
        }

        val rawSlidingWindow = when {
            root.has("sliding_window_size") && !root.isNull("sliding_window_size") ->
                root.optInt("sliding_window_size", -1)
            root.has("sliding_window") && !root.isNull("sliding_window") ->
                root.optInt("sliding_window", -1)
            else -> modelCfg?.optInt("sliding_window_size", -1) ?: -1
        }
        val rawCtxWindow = when {
            root.has("max_window_size") && !root.isNull("max_window_size") ->
                root.optInt("max_window_size", 4096)
            root.has("context_window_size") && !root.isNull("context_window_size") ->
                root.optInt("context_window_size", 4096)
            else -> modelCfg?.optInt("context_window_size", 4096) ?: 4096
        }
        val ctxWindow = rawCtxWindow.coerceIn(512, 4096)
        if (root.has("context_window_size")) {
            root.put("context_window_size", ctxWindow)
        }
        // Classic llm_chat.cc (line 512) forbids specifying both sliding_window(_size) and
        // max_window_size—even when sliding_window_size is -1—and rejects attention_sink_size
        // without sliding_window. Enforce strict mutual exclusion.
        if (rawSlidingWindow > 0) {
            root.remove("max_window_size")
            root.remove("sliding_window")
            root.put("sliding_window_size", rawSlidingWindow.coerceIn(512, 4096))
            val sinkSize = if (root.has("attention_sink_size") && !root.isNull("attention_sink_size")) {
                root.optInt("attention_sink_size", 4)
            } else {
                modelCfg?.optInt("attention_sink_size", 4) ?: 4
            }.coerceAtLeast(0)
            root.put("attention_sink_size", sinkSize)
        } else {
            root.remove("sliding_window")
            root.remove("sliding_window_size")
            root.remove("attention_sink_size")
            root.put("max_window_size", ctxWindow)
        }

        val prefillChunk = root.optInt(
            "prefill_chunk_size",
            modelCfg?.optInt("prefill_chunk_size", 512) ?: 512
        ).coerceIn(128, minOf(512, ctxWindow))
        root.put("prefill_chunk_size", prefillChunk)

        if (!root.has("vocab_size") && modelCfg != null && modelCfg.has("vocab_size")) {
            root.put("vocab_size", modelCfg.optInt("vocab_size"))
        }
        if (!root.has("pad_token_id") || root.isNull("pad_token_id")) {
            root.put("pad_token_id", modelCfg?.optInt("pad_token_id", 0) ?: 0)
        }
        if (!root.has("bos_token_id") || root.isNull("bos_token_id")) {
            root.put("bos_token_id", modelCfg?.optInt("bos_token_id", 1) ?: 1)
        }
        if (!root.has("tokenizer_files") || root.optJSONArray("tokenizer_files") == null) {
            val tokArr = JSONArray()
            listOf("tokenizer.json", "tokenizer.model", "tokenizer_config.json").forEach { name ->
                if (File(modelDir, name).exists()) tokArr.put(name)
            }
            if (tokArr.length() > 0) root.put("tokenizer_files", tokArr)
        }

        // Classic llm_chat.cc expects `conv_template` to be a String (and optional `conv_config` Object).
        val convObj = root.optJSONObject("conv_template")
        if (useMinimalLmTemplate) {
            root.put("conv_template", "LM")
            root.remove("conv_config")
        } else if (convObj != null) {
            val rawSeps = convObj.optJSONArray("seps")
            val sepsArr = if (rawSeps != null && rawSeps.length() > 0) rawSeps else JSONArray().put("\n")
            val sep0 = sepsArr.optString(0, "")
            val sysTemplate = convObj.optString("system_template", "{system_message}")
            val sysMessage = convObj.optString("system_message", "")
            var resolvedSys = sysTemplate.replace("{system_message}", sysMessage)
            if (sep0.isNotEmpty() && resolvedSys.endsWith(sep0)) {
                resolvedSys = resolvedSys.removeSuffix(sep0)
            }

            val rolesArray = JSONArray()
            val rolesObj = convObj.optJSONObject("roles")
            val existingRolesArr = convObj.optJSONArray("roles")
            if (rolesObj != null) {
                rolesArray.put(rolesObj.optString("user", "User"))
                rolesArray.put(rolesObj.optString("assistant", "Assistant"))
            } else if (existingRolesArr != null && existingRolesArr.length() >= 2) {
                rolesArray.put(existingRolesArr.optString(0, "User"))
                rolesArray.put(existingRolesArr.optString(1, "Assistant"))
            } else {
                rolesArray.put("User")
                rolesArray.put("Assistant")
            }

            val stopStrArr = convObj.optJSONArray("stop_str")
            val stopStr = when {
                stopStrArr != null && stopStrArr.length() > 0 -> stopStrArr.optString(0, "")
                else -> convObj.optString("stop_str", "")
            }

            val stopTokens = convObj.optJSONArray("stop_token_ids")
                ?: convObj.optJSONArray("stop_tokens")
                ?: run {
                    val eosArr = root.optJSONArray("eos_token_id")
                    if (eosArr != null && eosArr.length() > 0) {
                        eosArr
                    } else {
                        val eosInt = root.optInt("eos_token_id", -1)
                        if (eosInt >= 0) JSONArray().put(eosInt) else JSONArray()
                    }
                }

            val addBos = (convObj.optJSONArray("system_prefix_token_ids")?.length() ?: 0) > 0 ||
                convObj.optBoolean("add_bos", true)

            val convConfig = JSONObject().apply {
                put("system", resolvedSys)
                put("roles", rolesArray)
                put("seps", sepsArr)
                put("role_msg_sep", convObj.optString("role_content_sep", convObj.optString("role_msg_sep", ": ")))
                put("role_empty_sep", convObj.optString("role_empty_sep", ":"))
                if (stopStr.isNotEmpty()) put("stop_str", stopStr)
                if (stopTokens.length() > 0) put("stop_tokens", stopTokens)
                put("add_bos", addBos)
                put("offset", 0)
                put("messages", JSONArray())
            }

            root.put("conv_template", "vicuna_v1.1")
            root.put("conv_config", convConfig)
        } else if (root.optString("conv_template").isBlank()) {
            root.put("conv_template", "LM")
        }

        configFile.writeText(root.toString(2))
    }

    private fun reloadChatModuleResilient(
        chatModuleClass: Class<*>,
        module: Any,
        cleanLib: String,
        modelPath: String
    ) {
        val reloadMethod = findMethod(chatModuleClass, "reload", String::class.java, String::class.java)
            ?: throw IOException("[ChatModule] Missing reload(String, String) method")

        val firstArgIsModelLib = runCatching {
            val metaClass = Class.forName("ai.mlc.mlcllm.ChatModuleMeta")
            metaClass.getField("RELOAD_FIRST_ARG_IS_MODEL_LIB").getBoolean(null)
        }.getOrDefault(true)

        val primaryFirst = if (firstArgIsModelLib) cleanLib else modelPath
        val primarySecond = if (firstArgIsModelLib) modelPath else cleanLib

        try {
            reloadMethod.invoke(module, primaryFirst, primarySecond)
            return
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            val msg = root.message.orEmpty()

            // If classic Conversation::LoadJSONOverride rejected a field in conv_config, retry with minimal LM template
            val looksLikeConvConfigError = msg.contains("conv_template", ignoreCase = true) ||
                msg.contains("conv_config", ignoreCase = true) ||
                msg.contains("conversation", ignoreCase = true) ||
                msg.contains("stop_tokens", ignoreCase = true) ||
                msg.contains("role_msg_sep", ignoreCase = true) ||
                msg.contains("roles", ignoreCase = true) ||
                msg.contains("seps", ignoreCase = true)
            if (looksLikeConvConfigError) {
                try {
                    ensureClassicChatModuleConfig(File(modelPath), cleanLib, useMinimalLmTemplate = true)
                    reloadMethod.invoke(module, primaryFirst, primarySecond)
                    return
                } catch (tConv: Throwable) {
                    val convRoot = unwrapThrowable(tConv)
                    throw IOException(formatThrowable("ChatModule.reload(lib=$cleanLib, LM)", convRoot), convRoot)
                }
            }

            // Retry with swapped argument order only if SystemLib / file-open failed (not JSON key errors)
            val looksLikeSwappedArgs = !msg.contains("Key \"", ignoreCase = true) && (
                msg.contains("SystemLib", ignoreCase = true) ||
                    msg.contains("Cannot open", ignoreCase = true) ||
                    msg.contains("No such file", ignoreCase = true)
                )
            if (looksLikeSwappedArgs) {
                val altFirst = if (firstArgIsModelLib) modelPath else cleanLib
                val altSecond = if (firstArgIsModelLib) cleanLib else modelPath
                try {
                    reloadMethod.invoke(module, altFirst, altSecond)
                    return
                } catch (_: Throwable) {
                    val normalizedLib = cleanLib.replace('-', '_').trimEnd('_') + "_"
                    if (normalizedLib != cleanLib) {
                        try {
                            val normFirst = if (firstArgIsModelLib) normalizedLib else modelPath
                            val normSecond = if (firstArgIsModelLib) modelPath else normalizedLib
                            reloadMethod.invoke(module, normFirst, normSecond)
                            return
                        } catch (_: Throwable) {
                        }
                    }
                    throw IOException(formatThrowable("ChatModule.reload(lib=$cleanLib)", root), root)
                }
            }
            throw IOException(formatThrowable("ChatModule.reload(lib=$cleanLib)", root), root)
        }
    }

    private fun buildInitialChatModulePrompt(history: List<Pair<String, String>>): String {
        val nonBlank = history.filter { it.second.isNotBlank() }
        if (nonBlank.isEmpty()) return ""
        val systemText = nonBlank.filter { it.first == "system" }.joinToString("\n") { it.second.trim() }
        val turns = nonBlank.filter { it.first != "system" }
        if (turns.isEmpty()) return systemText
        if (turns.size == 1) {
            val userMsg = turns.first().second
            return if (systemText.isNotBlank()) "$systemText\n\n$userMsg" else userMsg
        }
        val sb = StringBuilder()
        if (systemText.isNotBlank()) {
            sb.append(systemText).append("\n\n")
        }
        val prior = turns.dropLast(1).takeLast(6)
        if (prior.isNotEmpty()) {
            sb.append("Previous conversation:\n")
            prior.forEach { (role, content) ->
                val label = if (role == "assistant") "Assistant" else "User"
                sb.append(label).append(": ").append(content.trim()).append("\n")
            }
            sb.append("\n")
        }
        sb.append(turns.last().second)
        return sb.toString()
    }

    private fun parseStatsRate(statsText: String, key: String): Float? {
        if (statsText.isBlank()) return null
        val match = Regex("$key\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(statsText)
        return match?.groupValues?.getOrNull(1)?.toFloatOrNull()?.takeIf { it > 0f }
    }

    private fun findMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method? {
        return runCatching { cls.getMethod(name, *paramTypes) }.getOrNull()
    }

    private fun findMethod(cls: Class<*>, primaryName: String, fallbackName: String): java.lang.reflect.Method? {
        return runCatching { cls.getMethod(primaryName) }.getOrNull()
            ?: runCatching { cls.getMethod(fallbackName) }.getOrNull()
    }

    /**
     * Restores the untouched original HuggingFace `mlc-chat-config.json` from
     * `mlc-chat-config.orig.json` when present (e.g. after upgrading from legacy ChatModule
     * to modern JSONFFIEngine without requiring the user to re-download model weights).
     */
    private fun restoreOriginalMlcChatConfig(modelDir: File) {
        val backupFile = File(modelDir, "mlc-chat-config.orig.json")
        val configFile = File(modelDir, "mlc-chat-config.json")
        if (backupFile.exists() && backupFile.length() > 0L) {
            runCatching {
                backupFile.copyTo(configFile, overwrite = true)
            }
        }
    }

    private fun streamViaJsonFfiEngine(
        modelDir: File,
        displayName: String,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> = callbackFlow {
        restoreOriginalMlcChatConfig(modelDir)
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
            val formatted = when {
                t is MlcUserFacingException -> t.message.orEmpty()
                t is IOException && t.message?.startsWith("[") == true ->
                    "MLC TVM engine error: ${t.message}"
                else -> "MLC TVM engine reload failed: ${formatThrowable("reload", t)}"
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
                        var sawFinish = false
                        var batchDecodeTps: Float? = null
                        var batchPrefillTps: Float? = null

                        for (i in 0 until arr.length()) {
                            val resp = arr.optJSONObject(i) ?: continue
                            val usage = resp.optJSONObject("usage")
                            val extra = usage?.optJSONObject("extra")
                            extra?.optDouble("decode_tokens_per_s", Double.NaN)
                                ?.takeIf { !it.isNaN() && it > 0.0 }
                                ?.let { batchDecodeTps = it.toFloat() }
                            extra?.optDouble("prefill_tokens_per_s", Double.NaN)
                                ?.takeIf { !it.isNaN() && it > 0.0 }
                                ?.let { batchPrefillTps = it.toFloat() }

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
                                    sawFinish = true
                                }
                            } else if (usage != null && (batchDecodeTps != null || decodeChunks > 0)) {
                                sawFinish = true
                            }
                        }

                        if (sawFinish && finished.compareAndSet(false, true)) {
                            val decodeTps = batchDecodeTps ?: computeDecodeTps(firstTokenNs, decodeChunks)
                            val ttftMs = if (firstTokenNs > 0L) {
                                (firstTokenNs - requestStartNs) / 1_000_000L
                            } else 0L
                            trySend(
                                StreamEvent.Completed(
                                    tokensPerSecond = decodeTps,
                                    modelName = modelLabel,
                                    ttftMs = ttftMs,
                                    prefillTokensPerSec = batchPrefillTps,
                                    kvCacheReused = true
                                )
                            )
                            close()
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

            // If switching models on a TVM engine that loaded the model directly in init (no separate reload),
            // cleanly exit the previous engine instance so initBackgroundEngineForModel runs for the new model.
            val existingEngine = jsonFfiEngine
            if (existingEngine != null &&
                (loadedModelDir != modelDir.absolutePath || loadedModelLib != modelLib)
            ) {
                val loadedByInitOnly = runCatching {
                    (existingEngine.javaClass.getMethod("isModelLoadedByInit").invoke(existingEngine) as? Boolean) == true
                }.getOrDefault(false)
                if (loadedByInitOnly) {
                    runCatching { existingEngine.javaClass.getMethod("exitBackgroundLoop").invoke(existingEngine) }
                    runCatching { existingEngine.javaClass.getMethod("unload").invoke(existingEngine) }
                    jsonFfiEngine = null
                    loopsStarted = false
                    loadedModelDir = null
                    loadedModelLib = null
                }
            }

            val cleanLib = modelLib.removePrefix("system://")
            val systemLibUri = "system://$cleanLib"
            val reloadPayload = JSONObject().apply {
                put("model", modelDir.absolutePath)
                put("model_lib", systemLibUri)
                put("mode", "interactive")
            }.toString()

            var engine = jsonFfiEngine
            var modelAlreadyLoadedByInit = false
            if (engine == null) {
                engine = try {
                    engineClass.getDeclaredConstructor().newInstance()
                } catch (t: Throwable) {
                    throw IOException(formatThrowable("createEngine", t), unwrapThrowable(t))
                }

                val callbackInterface = engineClass.methods
                    .firstOrNull { it.name == "initBackgroundEngine" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface }
                    ?.parameterTypes?.get(0)
                    ?: try {
                        Class.forName("ai.mlc.mlcllm.JSONFFIEngine\$KotlinFunction")
                    } catch (t: Throwable) {
                        throw IOException(formatThrowable("loadCallbackInterface", t), unwrapThrowable(t))
                    }

                val callbackProxy = Proxy.newProxyInstance(
                    callbackInterface.classLoader,
                    arrayOf(callbackInterface)
                ) { _, method, args ->
                    if (args != null && args.size == 1 && args[0] is String) {
                        dispatchStreamResponses(args[0] as String)
                    } else if (method.name == "toString") {
                        return@newProxyInstance "LocalMind-MLC-StreamCallback"
                    }
                    null
                }

                modelAlreadyLoadedByInit = initEngineWithDeviceFallback(
                    engineClass = engineClass,
                    engine = engine,
                    callbackInterface = callbackInterface,
                    callbackProxy = callbackProxy,
                    status = status,
                    modelPath = modelDir.absolutePath,
                    modelLib = systemLibUri,
                    engineConfigJson = reloadPayload
                )

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
                if (!modelAlreadyLoadedByInit) {
                    invokeReloadResilient(engineClass, engine, modelDir.absolutePath, modelLib)
                }
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
        status: MlcRuntimeStatus,
        modelPath: String,
        modelLib: String,
        engineConfigJson: String
    ): Boolean {
        val initForModel = runCatching {
            engineClass.getMethod(
                "initBackgroundEngineForModel",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
                callbackInterface
            )
        }.getOrNull()

        val init3Arg = runCatching {
            engineClass.getMethod(
                "initBackgroundEngine",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                callbackInterface
            )
        }.getOrNull()

        if (initForModel != null || init3Arg != null) {
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
                    val loaded = if (initForModel != null) {
                        (initForModel.invoke(
                            engine,
                            deviceType,
                            0,
                            modelPath,
                            modelLib,
                            engineConfigJson,
                            callbackProxy
                        ) as? Boolean) ?: false
                    } else {
                        init3Arg!!.invoke(engine, deviceType, 0, callbackProxy)
                        false
                    }
                    activeDeviceLabel = label
                    return loaded
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
            return false
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
        val cb = snapshot[firstId]
            ?: snapshot[firstId.removePrefix("chatcmpl-")]
            ?: snapshot.values.firstOrNull()
        cb?.invoke(responsesJson)
    }

    private fun notifyBackgroundError(err: Throwable) {
        val callbacks = synchronized(activeCallbackLock) { activeErrorCallbacks.values.toList() }
        callbacks.forEach { cb -> runCatching { cb(err) } }
    }

    fun resetConversation() {
        chatModule?.let { module ->
            chatModuleExecutor.execute {
                runCatching { findMethod(module.javaClass, "resetChat", "reset_chat")?.invoke(module) }
                lastChatHistory = emptyList()
            }
        }
        synchronized(lock) {
            val engine = jsonFfiEngine ?: return
            runCatching { engine.javaClass.getMethod("reset").invoke(engine) }
        }
    }

    fun unload() {
        chatModule?.let { module ->
            chatModuleExecutor.execute {
                runCatching { findMethod(module.javaClass, "unload")?.invoke(module) }
                chatModuleModelDir = null
                chatModuleModelLib = null
                lastChatHistory = emptyList()
            }
        }
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
        val compactMsg = rawMsg.trim().take(2400)
        return "[$stage] $rootName: $compactMsg"
    }

    private class MlcUserFacingException(message: String) : IOException(message)

    companion object {
        private const val TAG = "LocalMind-MLC"
    }
}
