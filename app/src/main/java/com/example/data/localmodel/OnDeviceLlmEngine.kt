package com.example.data.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.example.data.attachments.MessageAttachment
import com.example.data.local.DownloadedModelEntity
import com.example.data.network.ChatMessageDto
import com.example.data.network.StreamEvent
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OnDeviceLlmEngine(private val context: Context) {

    /**
     * Dedicated single-thread native executor.
     * Required by LiteRT-LM (initialize, sendMessageAsync, cancelProcess, close must stay on one thread)
     * and prevents LlamaBridge SIGSEGV race conditions between cancel/re-init/generate.
     */
    private val nativeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalMind-NativeInference").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }.asCoroutineDispatcher()

    @Volatile
    private var loadedMediaPipePath: String? = null
    @Volatile
    private var loadedMediaPipeMaxTokens: Int = 0
    @Volatile
    private var mediaPipeInference: LlmInference? = null

    @Volatile
    private var loadedLlamaPath: String? = null
    @Volatile
    private var loadedLlamaProfileKey: String? = null
    @Volatile
    private var loadedLlamaProfile: LlamaInitProfile? = null
    @Volatile
    private var activeLlamaProfileLabel: String = "llama.cpp"

    @Volatile
    private var loadedLiteRtPath: String? = null
    @Volatile
    private var loadedLiteRtBackend: LocalHardwareBackend? = null
    @Volatile
    private var activeLiteRtBackendLabel: String = "CPU"
    @Volatile
    private var liteRtEngine: Engine? = null

    private val lock = Any()

    fun streamCompletion(
        model: DownloadedModelEntity,
        messages: List<ChatMessageDto>,
        preferredEngine: LocalInferenceEngine,
        hardwareBackend: LocalHardwareBackend,
        threads: Int,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> {
        val modelFile = File(model.localPath)
        if (!modelFile.exists()) {
            return callbackFlow {
                close(IOException("Downloaded model file not found on disk: ${model.fileName}"))
            }
        }

        val resolvedEngine = LocalInferenceEngine.resolveForFormat(
            preferred = preferredEngine,
            format = model.format,
            hardwareBackend = hardwareBackend
        )

        return when (resolvedEngine) {
            LocalInferenceEngine.LLAMA_CPP -> streamWithLlamaCpp(
                model = model,
                modelFile = modelFile,
                messages = messages,
                hardwareBackend = hardwareBackend,
                threads = threads,
                temperature = temperature,
                maxTokens = maxTokens
            )

            LocalInferenceEngine.LITERT_LM -> streamWithLiteRtLm(
                model = model,
                modelFile = modelFile,
                messages = messages,
                hardwareBackend = hardwareBackend,
                temperature = temperature
            )

            LocalInferenceEngine.MEDIAPIPE_GENAI, LocalInferenceEngine.AUTO -> streamWithMediaPipe(
                model = model,
                modelFile = modelFile,
                messages = messages,
                hardwareBackend = hardwareBackend,
                temperature = temperature,
                maxTokens = maxTokens
            )
        }
    }

    // -------------------------------------------------------------------------
    // 1. GGUF / llama.cpp (Fixed param-before-init order, RAM-adaptive retry,
    //    GGUF magic verification, per-architecture chat templates, stop-token cut)
    // -------------------------------------------------------------------------

    private data class LlamaInitProfile(
        val label: String,
        val contextLength: Int,
        val batchSize: Int,
        val threads: Int,
        val useMmap: Boolean,
        val flashAttention: Boolean
    )

    private fun streamWithLlamaCpp(
        model: DownloadedModelEntity,
        modelFile: File,
        messages: List<ChatMessageDto>,
        hardwareBackend: LocalHardwareBackend,
        threads: Int,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> = callbackFlow {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            close(IOException("llama.cpp engine requires Android 8.0 (API 26) or newer."))
            return@callbackFlow
        }
        if (!model.format.equals("gguf", ignoreCase = true)) {
            close(
                IOException(
                    "llama.cpp requires a .gguf model file, but '${model.fileName}' is .${model.format}. " +
                        "Switch engine to Auto or download a GGUF / .litertlm model."
                )
            )
            return@callbackFlow
        }

        // Validate GGUF binary header before touching native C++ so corrupt/HTML files never SIGSEGV
        val validationError = validateGgufFile(modelFile)
        if (validationError != null) {
            close(IOException(validationError))
            return@callbackFlow
        }

        val optimalThreads = computeOptimalLlamaThreads(threads)
        val availRamMb = queryAvailableRamMb()
        val modelMb = (modelFile.length() / (1024L * 1024L)).coerceAtLeast(1L)
        val primaryCtx = when {
            availRamMb >= 3800L && modelMb <= 3200L -> 4096
            availRamMb >= 2200L && modelMb <= 4800L -> 2048
            else -> 1536
        }

        val requestedKey = "${modelFile.absolutePath}|$optimalThreads|$primaryCtx|${hardwareBackend.id}"
        val safeTemp = temperature.coerceIn(0.05f, 2.0f)
        val safeMaxTokens = maxTokens.coerceIn(128, 2048)
        val finished = AtomicBoolean(false)
        val doneLatch = CountDownLatch(1)

        val workerJob = launch(nativeDispatcher) {
            try {
                synchronized(lock) {
                    if (loadedLlamaPath != modelFile.absolutePath || loadedLlamaProfileKey != requestedKey) {
                        unloadMediaPipeLocked()
                        unloadLiteRtLocked()
                        unloadLlamaLocked()

                        val profiles = listOf(
                            LlamaInitProfile(
                                label = "llama.cpp · ${optimalThreads}T FA",
                                contextLength = primaryCtx,
                                batchSize = 256,
                                threads = optimalThreads,
                                useMmap = true,
                                flashAttention = true
                            ),
                            LlamaInitProfile(
                                label = "llama.cpp · ${optimalThreads}T",
                                contextLength = minOf(primaryCtx, 2048),
                                batchSize = 128,
                                threads = optimalThreads,
                                useMmap = true,
                                flashAttention = false
                            ),
                            LlamaInitProfile(
                                label = "llama.cpp · Safe Low-RAM",
                                contextLength = 1024,
                                batchSize = 64,
                                threads = minOf(4, optimalThreads),
                                useMmap = false,
                                flashAttention = false
                            )
                        )

                        var loadedProfile: LlamaInitProfile? = null
                        var lastErr: Throwable? = null
                        for (profile in profiles) {
                            try {
                                runCatching { LlamaBridge.shutdown() }
                                // CRITICAL: Always apply hardware/context params BEFORE initGenerateModel!
                                LlamaBridge.updateGenerateParams(
                                    temperature = safeTemp,
                                    maxTokens = safeMaxTokens,
                                    topP = 0.9f,
                                    topK = 40,
                                    repeatPenalty = 1.1f,
                                    contextLength = profile.contextLength,
                                    numThreads = profile.threads,
                                    useMmap = profile.useMmap,
                                    flashAttention = profile.flashAttention,
                                    batchSize = profile.batchSize
                                )
                                val ok = LlamaBridge.initGenerateModel(modelFile.absolutePath)
                                if (ok) {
                                    loadedProfile = profile
                                    break
                                }
                            } catch (t: Throwable) {
                                lastErr = t
                            }
                        }

                        if (loadedProfile == null) {
                            throw IOException(
                                "llama.cpp failed to load '${model.fileName}' (${modelMb} MB, free RAM ${availRamMb} MB). " +
                                    "Try a smaller quantization (Q4_K_M / Q4_0, 1B–3B) or a QNN NPU .litertlm model." +
                                    (lastErr?.localizedMessage?.let { " Details: $it" } ?: ""),
                                lastErr
                            )
                        }

                        loadedLlamaPath = modelFile.absolutePath
                        loadedLlamaProfileKey = requestedKey
                        loadedLlamaProfile = loadedProfile
                        activeLlamaProfileLabel = loadedProfile.label
                    } else {
                        // Model already loaded: preserve active profile's exact memory/context settings
                        val activeProfile = loadedLlamaProfile
                        runCatching {
                            LlamaBridge.updateGenerateParams(
                                temperature = safeTemp,
                                maxTokens = safeMaxTokens,
                                topP = 0.9f,
                                topK = 40,
                                repeatPenalty = 1.1f,
                                contextLength = activeProfile?.contextLength ?: primaryCtx,
                                numThreads = activeProfile?.threads ?: optimalThreads,
                                useMmap = activeProfile?.useMmap ?: true,
                                flashAttention = activeProfile?.flashAttention ?: true,
                                batchSize = activeProfile?.batchSize ?: 256
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (finished.compareAndSet(false, true)) {
                    doneLatch.countDown()
                    close(IOException(t.localizedMessage ?: "llama.cpp init error", t))
                }
                return@launch
            }

            if (finished.get()) {
                doneLatch.countDown()
                return@launch
            }

            val templateStyle = detectGgufChatTemplate(model.fileName, model.repoId)
            val prompt = formatGgufPrompt(messages, templateStyle)
            val stopMarkers = templateStyle.stopMarkers
            val modelLabel = "${model.displayName} ($activeLlamaProfileLabel)"
            val startNs = System.nanoTime()
            var chunkCount = 0
            val tailBuffer = StringBuilder()

            try {
                LlamaBridge.generateStream(
                    prompt = prompt,
                    callback = object : GenStream {
                        override fun onDelta(text: String) {
                            if (finished.get() || text.isEmpty()) return

                            tailBuffer.append(text)
                            val recent = tailBuffer.toString()
                            val hitStop = stopMarkers.firstOrNull { marker -> recent.contains(marker) }
                            if (hitStop != null) {
                                val cleanTail = text.substringBefore(hitStop)
                                if (cleanTail.isNotEmpty()) {
                                    chunkCount++
                                    trySend(
                                        StreamEvent.Chunk(
                                            contentDelta = cleanTail,
                                            modelName = modelLabel
                                        )
                                    )
                                }
                                if (finished.compareAndSet(false, true)) {
                                    runCatching { LlamaBridge.nativeCancelGenerate() }
                                    val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
                                    val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
                                    trySend(StreamEvent.Completed(tokensPerSecond = tps, modelName = modelLabel))
                                    doneLatch.countDown()
                                    close()
                                }
                                return
                            }
                            if (tailBuffer.length > 64) {
                                tailBuffer.delete(0, tailBuffer.length - 64)
                            }

                            chunkCount++
                            trySend(
                                StreamEvent.Chunk(
                                    contentDelta = text,
                                    modelName = modelLabel
                                )
                            )
                        }

                        override fun onComplete() {
                            if (finished.compareAndSet(false, true)) {
                                val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
                                val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
                                trySend(StreamEvent.Completed(tokensPerSecond = tps, modelName = modelLabel))
                                doneLatch.countDown()
                                close()
                            }
                        }

                        override fun onError(message: String) {
                            if (finished.compareAndSet(false, true)) {
                                doneLatch.countDown()
                                close(IOException("llama.cpp error: $message"))
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                if (finished.compareAndSet(false, true)) {
                    doneLatch.countDown()
                    close(IOException("llama.cpp generation failed: ${t.localizedMessage}", t))
                }
            }
        }

        awaitClose {
            if (finished.compareAndSet(false, true)) {
                runCatching { LlamaBridge.nativeCancelGenerate() }
                runCatching { doneLatch.await(800, TimeUnit.MILLISECONDS) }
            }
            workerJob.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // 2. Qualcomm QNN Hexagon HTP NPU / LiteRT-LM
    //    Single-thread executor, ADSP_LIBRARY_PATH configured, clean ConversationConfig,
    //    QNN HTP graph cache, proper cancelProcess() + close lifecycle
    // -------------------------------------------------------------------------

    private fun streamWithLiteRtLm(
        model: DownloadedModelEntity,
        modelFile: File,
        messages: List<ChatMessageDto>,
        hardwareBackend: LocalHardwareBackend,
        temperature: Float
    ): Flow<StreamEvent> = callbackFlow {
        val ext = model.format.lowercase()
        if (ext != "litertlm" && ext != "task") {
            close(
                IOException(
                    "LiteRT-LM QNN NPU runs .litertlm or .task bundles, not .${model.format}. " +
                        "Download a .litertlm model from the NPU QNN preset in Model Hub, or select llama.cpp for .gguf."
                )
            )
            return@callbackFlow
        }

        val engine = try {
            getOrCreateLiteRtEngine(modelFile.absolutePath, hardwareBackend)
        } catch (t: Throwable) {
            close(IOException("LiteRT-LM NPU init error: ${t.localizedMessage ?: t.javaClass.simpleName}", t))
            return@callbackFlow
        }

        val systemMsg = messages.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val userPayload = buildLiteRtConversationPrompt(messages)
        val modelLabel = "${model.displayName} (LiteRT-LM · $activeLiteRtBackendLabel)"
        val startNs = System.nanoTime()
        var chunkCount = 0
        var activeConversation: Conversation? = null

        try {
            val convConfig = ConversationConfig(
                systemInstruction = if (systemMsg.isNotBlank()) Contents.of(systemMsg) else null,
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = temperature.coerceIn(0.05f, 1.5f).toDouble()
                )
            )
            val conversation = engine.createConversation(convConfig)
            activeConversation = conversation
            conversation.use { conv ->
                conv.sendMessageAsync(userPayload).collect { response ->
                    val token = response.toString()
                    if (token.isNotEmpty()) {
                        chunkCount++
                        trySend(
                            StreamEvent.Chunk(
                                contentDelta = token,
                                modelName = modelLabel
                            )
                        )
                    }
                }
            }
            activeConversation = null
            val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
            val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
            trySend(StreamEvent.Completed(tokensPerSecond = tps, modelName = modelLabel))
            close()
        } catch (ce: CancellationException) {
            runCatching { activeConversation?.cancelProcess() }
            runCatching { activeConversation?.close() }
            throw ce
        } catch (t: Throwable) {
            runCatching { activeConversation?.cancelProcess() }
            runCatching { activeConversation?.close() }
            close(IOException("LiteRT-LM generation failed: ${t.localizedMessage}", t))
        }

        awaitClose {
            runCatching { activeConversation?.cancelProcess() }
        }
    }.flowOn(nativeDispatcher)

    // -------------------------------------------------------------------------
    // 3. MediaPipe GenAI (.task / .bin CPU/GPU fallback)
    // -------------------------------------------------------------------------

    private fun streamWithMediaPipe(
        model: DownloadedModelEntity,
        modelFile: File,
        messages: List<ChatMessageDto>,
        hardwareBackend: LocalHardwareBackend,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> = callbackFlow {
        val ext = model.format.lowercase()
        if (ext != "task" && ext != "bin") {
            close(
                IOException(
                    "MediaPipe GenAI runs .task / .bin bundles, not .${model.format}. " +
                        "Switch engine to Auto or llama.cpp for GGUF models."
                )
            )
            return@callbackFlow
        }

        val effectiveMaxTokens = maxTokens.coerceIn(256, 4096)
        val engine = try {
            getOrCreateMediaPipeInference(modelFile.absolutePath, effectiveMaxTokens, hardwareBackend)
        } catch (t: Throwable) {
            close(IOException("MediaPipe init error: ${t.localizedMessage ?: t.javaClass.simpleName}", t))
            return@callbackFlow
        }

        val prompt = formatGgufPrompt(messages, GgufTemplateStyle.GEMMA)
        val backendTag = when (hardwareBackend) {
            LocalHardwareBackend.NPU -> "GPU/NPU"
            LocalHardwareBackend.GPU -> "GPU"
            LocalHardwareBackend.CPU -> "CPU"
        }
        val modelLabel = "${model.displayName} (MediaPipe · $backendTag)"
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(temperature.coerceIn(0.05f, 1.5f))
            .setTopK(40)
            .build()

        val session = try {
            LlmInferenceSession.createFromOptions(engine, sessionOptions)
        } catch (t: Throwable) {
            close(IOException("Failed to create MediaPipe session: ${t.localizedMessage}", t))
            return@callbackFlow
        }

        val startNs = System.nanoTime()
        var chunkCount = 0

        try {
            session.addQueryChunk(prompt)
            session.generateResponseAsync { partialResult, done ->
                if (!partialResult.isNullOrEmpty()) {
                    chunkCount++
                    trySend(
                        StreamEvent.Chunk(
                            contentDelta = partialResult,
                            modelName = modelLabel
                        )
                    )
                }
                if (done) {
                    val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
                    val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
                    trySend(StreamEvent.Completed(tokensPerSecond = tps, modelName = modelLabel))
                    runCatching { session.close() }
                    close()
                }
            }
        } catch (t: Throwable) {
            runCatching { session.close() }
            close(IOException("MediaPipe generation failed: ${t.localizedMessage}", t))
            return@callbackFlow
        }

        awaitClose {
            runCatching { session.cancelGenerateResponseAsync() }
            runCatching { session.close() }
        }
    }.flowOn(nativeDispatcher)

    private fun getOrCreateLiteRtEngine(
        modelPath: String,
        hardwareBackend: LocalHardwareBackend
    ): Engine {
        synchronized(lock) {
            val existing = liteRtEngine
            if (existing != null &&
                loadedLiteRtPath == modelPath &&
                loadedLiteRtBackend == hardwareBackend
            ) {
                return existing
            }
            unloadMediaPipeLocked()
            unloadLlamaLocked()
            unloadLiteRtLocked()

            // CRITICAL: Set ADSP_LIBRARY_PATH & QNN HTP cache before touching Backend.NPU
            val qnnStatus = QnnRuntimeConfigurator.configureAndInspect(context)
            val nativeDir = qnnStatus.nativeLibDir
            val cacheDir = qnnStatus.qnnCacheDir
            val npuLabel = if (qnnStatus.isQnnHtpBundled || qnnStatus.isLiteRtDispatchBundled) {
                "QNN ${qnnStatus.hexagonArch}"
            } else {
                "NPU"
            }

            val candidates: List<Pair<String, Backend>> = when (hardwareBackend) {
                LocalHardwareBackend.NPU -> listOf(
                    npuLabel to Backend.NPU(nativeLibraryDir = nativeDir),
                    "GPU OpenCL (NPU fallback)" to Backend.GPU(),
                    "CPU (NPU fallback)" to Backend.CPU()
                )

                LocalHardwareBackend.GPU -> listOf(
                    "GPU OpenCL" to Backend.GPU(),
                    "CPU (GPU fallback)" to Backend.CPU()
                )

                LocalHardwareBackend.CPU -> listOf(
                    "CPU" to Backend.CPU()
                )
            }

            var lastError: Throwable? = null
            for ((label, backend) in candidates) {
                try {
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        cacheDir = cacheDir
                    )
                    val created = Engine(config).apply { initialize() }
                    liteRtEngine = created
                    loadedLiteRtPath = modelPath
                    loadedLiteRtBackend = hardwareBackend
                    activeLiteRtBackendLabel = label
                    return created
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw lastError ?: IOException("Failed to initialize LiteRT-LM engine on NPU/GPU/CPU.")
        }
    }

    private fun getOrCreateMediaPipeInference(
        modelPath: String,
        maxTokens: Int,
        hardwareBackend: LocalHardwareBackend
    ): LlmInference {
        synchronized(lock) {
            val existing = mediaPipeInference
            if (existing != null && loadedMediaPipePath == modelPath && loadedMediaPipeMaxTokens == maxTokens) {
                return existing
            }
            unloadLiteRtLocked()
            unloadLlamaLocked()
            unloadMediaPipeLocked()

            val preferredMpBackend = when (hardwareBackend) {
                LocalHardwareBackend.NPU, LocalHardwareBackend.GPU -> LlmInference.Backend.GPU
                LocalHardwareBackend.CPU -> LlmInference.Backend.CPU
            }

            val created = try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(maxTokens)
                    .setPreferredBackend(preferredMpBackend)
                    .build()
                LlmInference.createFromOptions(context, options)
            } catch (t: Throwable) {
                if (preferredMpBackend != LlmInference.Backend.CPU) {
                    val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTokens(maxTokens)
                        .setPreferredBackend(LlmInference.Backend.CPU)
                        .build()
                    LlmInference.createFromOptions(context, cpuOptions)
                } else {
                    throw t
                }
            }
            mediaPipeInference = created
            loadedMediaPipePath = modelPath
            loadedMediaPipeMaxTokens = maxTokens
            return created
        }
    }

    private fun unloadMediaPipeLocked() {
        runCatching { mediaPipeInference?.close() }
        mediaPipeInference = null
        loadedMediaPipePath = null
    }

    private fun unloadLiteRtLocked() {
        runCatching { liteRtEngine?.close() }
        liteRtEngine = null
        loadedLiteRtPath = null
        loadedLiteRtBackend = null
    }

    private fun unloadLlamaLocked() {
        if (loadedLlamaPath != null) {
            runCatching { LlamaBridge.nativeCancelGenerate() }
            runCatching { LlamaBridge.shutdown() }
            loadedLlamaPath = null
            loadedLlamaProfileKey = null
            loadedLlamaProfile = null
        }
    }

    fun unloadModel() {
        synchronized(lock) {
            unloadMediaPipeLocked()
            unloadLiteRtLocked()
            unloadLlamaLocked()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: GGUF integrity check, RAM query, thread tuning, chat templates
    // -------------------------------------------------------------------------

    private fun validateGgufFile(file: File): String? {
        if (!file.exists()) return "GGUF file not found on disk: ${file.name}"
        val size = file.length()
        if (size < 1024L * 1024L) {
            return "GGUF file '${file.name}' is too small (${size / 1024} KB) — download was likely interrupted or blocked by a Hugging Face login wall. Delete and re-download it."
        }
        val header = ByteArray(4)
        val read = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(0)
        if (read < 4 ||
            header[0] != 'G'.code.toByte() ||
            header[1] != 'G'.code.toByte() ||
            header[2] != 'U'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) {
            return "File '${file.name}' is not a valid GGUF binary (missing GGUF magic header). If downloaded from a gated Hugging Face repo, add your HF token and re-download."
        }
        return null
    }

    private fun queryAvailableRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 2048L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.availMem / (1024L * 1024L)).coerceAtLeast(512L)
    }

    private fun computeOptimalLlamaThreads(requested: Int): Int {
        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        // On 8-core Snapdragon/Dimensity big.LITTLE SoCs, 4-6 performance threads outperform 8 threads
        val perfCap = if (totalCores >= 8) 6 else (totalCores - 1).coerceAtLeast(2)
        return requested.coerceIn(1, perfCap)
    }

    private enum class GgufTemplateStyle(val stopMarkers: List<String>) {
        CHATML(listOf("<|im_end|>", "<|endoftext|>", "<|im_start|>user")),
        LLAMA3(listOf("<|eot_id|>", "<|end_of_text|>", "<|start_header_id|>user")),
        GEMMA(listOf("<end_of_turn>", "<eos>", "<start_of_turn>user")),
        PHI3(listOf("<|end|>", "<|endoftext|>", "<|user|>")),
        MISTRAL(listOf("</s>", "[INST]"))
    }

    private fun detectGgufChatTemplate(fileName: String, repoId: String): GgufTemplateStyle {
        val id = "$fileName $repoId".lowercase()
        return when {
            id.contains("llama-3") || id.contains("llama3") || id.contains("llama_3") -> GgufTemplateStyle.LLAMA3
            id.contains("gemma") -> GgufTemplateStyle.GEMMA
            id.contains("phi-3") || id.contains("phi3") || id.contains("phi-4") || id.contains("phi4") -> GgufTemplateStyle.PHI3
            id.contains("mistral") || id.contains("mixtral") || id.contains("zephyr") -> GgufTemplateStyle.MISTRAL
            else -> GgufTemplateStyle.CHATML // Qwen2.5, DeepSeek, SmolLM, Yi, OpenChat, etc.
        }
    }

    private fun buildLiteRtConversationPrompt(messages: List<ChatMessageDto>): String {
        val turns = messages.filter { it.role != "system" }.takeLast(10)
        if (turns.size <= 1) {
            return turns.lastOrNull()?.let(::renderMessageWithAttachments).orEmpty()
        }
        val history = turns.dropLast(1)
        val latest = turns.last()
        return buildString {
            appendLine("Previous conversation:")
            history.forEach { msg ->
                val who = if (msg.role == "user") "User" else "Assistant"
                appendLine("$who: ${renderMessageWithAttachments(msg)}")
            }
            appendLine()
            append("User: ")
            append(renderMessageWithAttachments(latest))
        }.trim()
    }

    private fun renderMessageWithAttachments(msg: ChatMessageDto): String {
        if (msg.attachments.isEmpty()) return msg.content.trim()
        return buildString {
            if (msg.content.isNotBlank()) {
                appendLine(msg.content.trim())
            }
            msg.attachments.forEach { att ->
                appendAttachmentSummary(att)
            }
        }.trim()
    }

    private fun StringBuilder.appendAttachmentSummary(att: MessageAttachment) {
        if (att.isImage) {
            appendLine("\n[Attached image: ${att.displayName}]")
        } else if (!att.extractedText.isNullOrBlank()) {
            appendLine("\n[Attached file: ${att.displayName}]")
            appendLine("```")
            appendLine(att.extractedText.take(8000))
            appendLine("```")
        } else {
            appendLine("\n[Attached file: ${att.displayName} (${att.mimeType})]")
        }
    }

    private fun formatGgufPrompt(
        messages: List<ChatMessageDto>,
        style: GgufTemplateStyle
    ): String {
        val systemMsg = messages.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val turns = messages.filter { it.role != "system" }.takeLast(10)

        return when (style) {
            GgufTemplateStyle.LLAMA3 -> buildString {
                append("<|begin_of_text|>")
                if (systemMsg.isNotEmpty()) {
                    append("<|start_header_id|>system<|end_header_id|>\n\n")
                    append(systemMsg)
                    append("<|eot_id|>")
                }
                turns.forEach { msg ->
                    val role = if (msg.role == "user") "user" else "assistant"
                    append("<|start_header_id|>$role<|end_header_id|>\n\n")
                    append(renderMessageWithAttachments(msg))
                    append("<|eot_id|>")
                }
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }

            GgufTemplateStyle.GEMMA -> buildString {
                if (systemMsg.isNotEmpty()) {
                    append("<start_of_turn>user\nSystem instructions: ")
                    append(systemMsg)
                    append("\n<end_of_turn>\n<start_of_turn>model\nUnderstood.<end_of_turn>\n")
                }
                turns.forEach { msg ->
                    val tag = if (msg.role == "user") "user" else "model"
                    append("<start_of_turn>$tag\n")
                    append(renderMessageWithAttachments(msg))
                    append("<end_of_turn>\n")
                }
                append("<start_of_turn>model\n")
            }

            GgufTemplateStyle.PHI3 -> buildString {
                if (systemMsg.isNotEmpty()) {
                    append("<|system|>\n$systemMsg<|end|>\n")
                }
                turns.forEach { msg ->
                    val tag = if (msg.role == "user") "user" else "assistant"
                    append("<|$tag|>\n")
                    append(renderMessageWithAttachments(msg))
                    append("<|end|>\n")
                }
                append("<|assistant|>\n")
            }

            GgufTemplateStyle.MISTRAL -> buildString {
                append("<s>")
                var systemPending = systemMsg
                turns.forEach { msg ->
                    val body = renderMessageWithAttachments(msg)
                    if (msg.role == "user") {
                        append("[INST] ")
                        if (systemPending.isNotEmpty()) {
                            append(systemPending)
                            append("\n\n")
                            systemPending = ""
                        }
                        append(body)
                        append(" [/INST]")
                    } else {
                        append(" ")
                        append(body)
                        append("</s>")
                    }
                }
            }

            GgufTemplateStyle.CHATML -> buildString {
                if (systemMsg.isNotEmpty()) {
                    append("<|im_start|>system\n")
                    append(systemMsg)
                    append("<|im_end|>\n")
                }
                turns.forEach { msg ->
                    val role = if (msg.role == "user") "user" else "assistant"
                    append("<|im_start|>$role\n")
                    append(renderMessageWithAttachments(msg))
                    append("<|im_end|>\n")
                }
                append("<|im_start|>assistant\n")
            }
        }
    }
}
