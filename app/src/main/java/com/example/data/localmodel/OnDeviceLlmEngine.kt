package com.example.data.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.example.data.attachments.MessageAttachment
import com.example.data.local.DownloadedModelEntity
import com.example.data.mlc.MlcNativeEngineBridge
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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance on-device LLM inference engine integrating MLC Chat's core performance patterns:
 * - Persistent multi-turn KV-cache sessions (avoids re-prefilling conversation history on every turn)
 * - Background model pre-warming (`preloadModel` = MLC Chat `requestReloadChat`)
 * - Configurable `contextWindowSize` and `prefillChunkSize` (batch size) to control RAM & speed
 * - Separate TTFT (ms) and decode throughput (tok/s) measurement
 * - Native MLC-LLM (`mlc4j` / TVM JSONFFIEngine), LiteRT-LM (QNN NPU / OpenCL-Vulkan GPU),
 *   MediaPipe GenAI (GPU/CPU), and llama.cpp (ARM64 NEON / Vulkan GGUF).
 */
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

    private val mlcBridge = MlcNativeEngineBridge(context)

    @Volatile
    private var loadedMediaPipePath: String? = null
    @Volatile
    private var loadedMediaPipeMaxTokens: Int = 0
    @Volatile
    private var loadedMediaPipeBackend: LocalHardwareBackend? = null
    @Volatile
    private var mediaPipeInference: LlmInference? = null
    @Volatile
    private var persistentMediaPipeSession: LlmInferenceSession? = null
    @Volatile
    private var mediaPipeSessionKey: String? = null
    @Volatile
    private var mediaPipeSyncedTurns: Int = 0

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
    private var loadedLiteRtContextWindow: Int = 0
    @Volatile
    private var activeLiteRtBackendLabel: String = "CPU"
    @Volatile
    private var liteRtEngine: Engine? = null
    @Volatile
    private var persistentLiteRtConversation: Conversation? = null
    @Volatile
    private var liteRtSessionKey: String? = null
    @Volatile
    private var liteRtSyncedTurns: Int = 0

    @Volatile
    private var activeSpeculativeLabel: String? = null

    private val lock = Any()

    /**
     * MLC Chat `requestReloadChat` equivalent: pre-warms the selected on-device model
     * on the dedicated background inference thread so the first user message responds immediately.
     */
    suspend fun preloadModel(
        model: DownloadedModelEntity,
        preferredEngine: LocalInferenceEngine,
        hardwareBackend: LocalHardwareBackend,
        threads: Int,
        gpuLayers: Int,
        enableVulkanGpu: Boolean,
        contextWindowSize: Int,
        prefillChunkSize: Int
    ): Result<String> = withContext(nativeDispatcher) {
        runCatching {
            val modelFile = File(model.localPath)
            if (!modelFile.exists()) {
                throw IOException("Model file not found on disk: ${model.fileName}")
            }
            val resolved = LocalInferenceEngine.resolveForFormat(
                preferred = preferredEngine,
                format = model.format,
                hardwareBackend = hardwareBackend
            )
            when (resolved) {
                LocalInferenceEngine.LITERT_LM -> {
                    getOrCreateLiteRtEngine(
                        modelPath = modelFile.absolutePath,
                        hardwareBackend = hardwareBackend,
                        enableVulkanGpu = enableVulkanGpu,
                        contextWindowSize = contextWindowSize
                    )
                    "LiteRT-LM · $activeLiteRtBackendLabel"
                }

                LocalInferenceEngine.MEDIAPIPE_GENAI -> {
                    getOrCreateMediaPipeInference(
                        modelPath = modelFile.absolutePath,
                        maxTokens = contextWindowSize.coerceIn(1024, 4096),
                        hardwareBackend = hardwareBackend
                    )
                    "MediaPipe · ${hardwareBackend.id.uppercase()}"
                }

                LocalInferenceEngine.LLAMA_CPP -> {
                    ensureLlamaLoaded(
                        model = model,
                        modelFile = modelFile,
                        hardwareBackend = hardwareBackend,
                        threads = threads,
                        temperature = 0.7f,
                        maxTokens = 1024,
                        requestedGpuLayers = gpuLayers,
                        enableVulkanGpu = enableVulkanGpu,
                        enableSpeculativeDecoding = false,
                        speculativeDraftTokens = 4,
                        validDraftPath = null,
                        contextWindowSize = contextWindowSize,
                        prefillChunkSize = prefillChunkSize
                    )
                    activeLlamaProfileLabel
                }

                LocalInferenceEngine.MLC_LLM -> {
                    val status = mlcBridge.inspectStatus()
                    if (status.isNativeMlcReady) "MLC · ${status.activeGpuBackend}" else "MLC Bundle Ready"
                }

                LocalInferenceEngine.AUTO -> "Ready"
            }
        }
    }

    /**
     * MLC Chat `requestResetChat` equivalent: resets persistent KV-cache sessions
     * when starting a new chat, switching conversations, or regenerating a reply.
     */
    fun resetConversationCache() {
        synchronized(lock) {
            closeLiteRtConversationLocked()
            closeMediaPipeSessionLocked()
            mlcBridge.resetConversation()
        }
    }

    fun streamCompletion(
        model: DownloadedModelEntity,
        messages: List<ChatMessageDto>,
        preferredEngine: LocalInferenceEngine,
        hardwareBackend: LocalHardwareBackend,
        threads: Int,
        temperature: Float,
        maxTokens: Int,
        gpuLayers: Int = -1,
        enableVulkanGpu: Boolean = true,
        enableSpeculativeDecoding: Boolean = false,
        speculativeDraftTokens: Int = 4,
        speculativeDraftModelPath: String? = null,
        contextWindowSize: Int = 2048,
        prefillChunkSize: Int = 512,
        enablePersistentKvCache: Boolean = true,
        conversationKey: String = "default"
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
            LocalInferenceEngine.MLC_LLM -> mlcBridge.streamChatCompletion(
                modelDir = modelFile,
                displayName = model.displayName,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens
            )

            LocalInferenceEngine.LLAMA_CPP -> streamWithLlamaCpp(
                model = model,
                modelFile = modelFile,
                messages = messages,
                hardwareBackend = hardwareBackend,
                threads = threads,
                temperature = temperature,
                maxTokens = maxTokens,
                requestedGpuLayers = gpuLayers,
                enableVulkanGpu = enableVulkanGpu,
                enableSpeculativeDecoding = enableSpeculativeDecoding,
                speculativeDraftTokens = speculativeDraftTokens,
                speculativeDraftModelPath = speculativeDraftModelPath,
                contextWindowSize = contextWindowSize,
                prefillChunkSize = prefillChunkSize
            )

            LocalInferenceEngine.LITERT_LM -> streamWithLiteRtLm(
                model = model,
                modelFile = modelFile,
                messages = messages,
                hardwareBackend = hardwareBackend,
                temperature = temperature,
                enableVulkanGpu = enableVulkanGpu,
                contextWindowSize = contextWindowSize,
                enablePersistentKvCache = enablePersistentKvCache,
                conversationKey = conversationKey
            )

            LocalInferenceEngine.MEDIAPIPE_GENAI, LocalInferenceEngine.AUTO -> streamWithMediaPipe(
                model = model,
                modelFile = modelFile,
                messages = messages,
                hardwareBackend = hardwareBackend,
                temperature = temperature,
                maxTokens = maxTokens,
                contextWindowSize = contextWindowSize,
                enablePersistentKvCache = enablePersistentKvCache,
                conversationKey = conversationKey
            )
        }
    }

    // -------------------------------------------------------------------------
    // 1. GGUF / llama.cpp (Chunked Prefill, Honest Vulkan/CPU Detection,
    //    Selective MTP/Draft Speculative Decoding, Accurate TTFT + Decode tok/s)
    // -------------------------------------------------------------------------

    private data class LlamaInitProfile(
        val label: String,
        val contextLength: Int,
        val batchSize: Int,
        val threads: Int,
        val gpuLayers: Int,
        val useMmap: Boolean,
        val flashAttention: Boolean
    )

    private fun ensureLlamaLoaded(
        model: DownloadedModelEntity,
        modelFile: File,
        hardwareBackend: LocalHardwareBackend,
        threads: Int,
        temperature: Float,
        maxTokens: Int,
        requestedGpuLayers: Int,
        enableVulkanGpu: Boolean,
        enableSpeculativeDecoding: Boolean,
        speculativeDraftTokens: Int,
        validDraftPath: String?,
        contextWindowSize: Int,
        prefillChunkSize: Int
    ) {
        val validationError = validateGgufFile(modelFile)
        if (validationError != null) {
            throw IOException(validationError)
        }

        val vulkanStatus = VulkanGpuConfigurator.configureAndInspect(context, enableVulkan = enableVulkanGpu)
        val optimalThreads = computeOptimalLlamaThreads(threads)
        val availRamMb = queryAvailableRamMb()
        val modelMb = (modelFile.length() / (1024L * 1024L)).coerceAtLeast(1L)

        // Respect user's MLC context_window_size while guarding against low-RAM OOM
        val ramSafeCtxCap = when {
            availRamMb >= 3800L && modelMb <= 3200L -> 8192
            availRamMb >= 2400L && modelMb <= 4800L -> 4096
            else -> 2048
        }
        val primaryCtx = contextWindowSize.coerceIn(1024, ramSafeCtxCap)
        val primaryBatch = prefillChunkSize.coerceIn(128, minOf(1024, primaryCtx))

        // Only request GPU layers if an actual ggml-vulkan / ggml-opencl native backend is bundled;
        // otherwise skip pointless GPU probe cycles and run straight on optimal ARM64 NEON CPU.
        val effectiveGpuLayers = when {
            !enableVulkanGpu || !vulkanStatus.supported || !vulkanStatus.hasBundledVulkanBackend -> 0
            requestedGpuLayers == 0 -> 0
            requestedGpuLayers > 0 -> requestedGpuLayers.coerceIn(1, 99)
            hardwareBackend == LocalHardwareBackend.CPU -> 0
            availRamMb >= 3000L -> -1
            else -> vulkanStatus.recommendedHybridLayers
        }

        val safeTemp = temperature.coerceIn(0.05f, 2.0f)
        val safeMaxTokens = maxTokens.coerceIn(128, 2048)
        val safeDraftLen = speculativeDraftTokens.coerceIn(1, 8)
        val shouldUseMtp = enableSpeculativeDecoding &&
            (validDraftPath != null || supportsNativeMtpHeads(model.fileName, model.repoId))

        val requestedKey = listOf(
            modelFile.absolutePath,
            optimalThreads,
            primaryCtx,
            primaryBatch,
            effectiveGpuLayers,
            shouldUseMtp,
            safeDraftLen,
            validDraftPath.orEmpty()
        ).joinToString("|")

        synchronized(lock) {
            if (loadedLlamaPath != modelFile.absolutePath || loadedLlamaProfileKey != requestedKey) {
                unloadMediaPipeLocked()
                unloadLiteRtLocked()
                unloadLlamaLocked()

                val profiles = buildList {
                    if (effectiveGpuLayers != 0) {
                        val gpuTag = if (effectiveGpuLayers == -1) "Vulkan All-GPU" else "Vulkan ${effectiveGpuLayers}L+CPU"
                        add(
                            LlamaInitProfile(
                                label = "$gpuTag · FA · B$primaryBatch",
                                contextLength = primaryCtx,
                                batchSize = primaryBatch,
                                threads = optimalThreads,
                                gpuLayers = effectiveGpuLayers,
                                useMmap = true,
                                flashAttention = true
                            )
                        )
                        val hybridFallback = if (effectiveGpuLayers == -1) {
                            vulkanStatus.recommendedHybridLayers.coerceIn(8, 24)
                        } else {
                            (effectiveGpuLayers / 2).coerceAtLeast(4)
                        }
                        add(
                            LlamaInitProfile(
                                label = "Vulkan ${hybridFallback}L+CPU",
                                contextLength = minOf(primaryCtx, 2048),
                                batchSize = minOf(primaryBatch, 512),
                                threads = optimalThreads,
                                gpuLayers = hybridFallback,
                                useMmap = true,
                                flashAttention = false
                            )
                        )
                    }
                    // Primary fast ARM64 NEON CPU profile with Flash Attention & chunked prefill batching
                    add(
                        LlamaInitProfile(
                            label = "CPU ${optimalThreads}T · FA · B$primaryBatch",
                            contextLength = primaryCtx,
                            batchSize = primaryBatch,
                            threads = optimalThreads,
                            gpuLayers = 0,
                            useMmap = true,
                            flashAttention = true
                        )
                    )
                    add(
                        LlamaInitProfile(
                            label = "CPU ${optimalThreads}T · B256",
                            contextLength = minOf(primaryCtx, 2048),
                            batchSize = minOf(primaryBatch, 256),
                            threads = optimalThreads,
                            gpuLayers = 0,
                            useMmap = true,
                            flashAttention = false
                        )
                    )
                    add(
                        LlamaInitProfile(
                            label = "CPU Safe Low-RAM",
                            contextLength = 1024,
                            batchSize = 128,
                            threads = minOf(4, optimalThreads),
                            gpuLayers = 0,
                            useMmap = false,
                            flashAttention = false
                        )
                    )
                }

                var loadedProfile: LlamaInitProfile? = null
                var lastErr: Throwable? = null
                for (profile in profiles) {
                    try {
                        runCatching { LlamaBridge.shutdownMtp() }
                        runCatching { LlamaBridge.shutdown() }
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
                            batchSize = profile.batchSize,
                            gpuLayers = profile.gpuLayers
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
                            "Try lowering Context Window or choosing a Q4_K_M / Q4_0 quantization." +
                            (lastErr?.localizedMessage?.let { " Details: $it" } ?: ""),
                        lastErr
                    )
                }

                activeSpeculativeLabel = null
                if (shouldUseMtp) {
                    val mtpTargetPath = validDraftPath ?: modelFile.absolutePath
                    val mtpReady = runCatching {
                        LlamaBridge.initMtp(mtpTargetPath, draftLen = safeDraftLen)
                    }.getOrDefault(false)
                    if (mtpReady) {
                        activeSpeculativeLabel = if (validDraftPath != null) {
                            "Draft x$safeDraftLen"
                        } else {
                            "MTP x$safeDraftLen"
                        }
                    }
                }

                loadedLlamaPath = modelFile.absolutePath
                loadedLlamaProfileKey = requestedKey
                loadedLlamaProfile = loadedProfile
                activeLlamaProfileLabel = buildString {
                    append("llama.cpp · ")
                    append(loadedProfile.label)
                    activeSpeculativeLabel?.let {
                        append(" · ")
                        append(it)
                    }
                }
            } else {
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
                        batchSize = activeProfile?.batchSize ?: primaryBatch,
                        gpuLayers = activeProfile?.gpuLayers ?: 0
                    )
                }
            }
        }
    }

    private fun streamWithLlamaCpp(
        model: DownloadedModelEntity,
        modelFile: File,
        messages: List<ChatMessageDto>,
        hardwareBackend: LocalHardwareBackend,
        threads: Int,
        temperature: Float,
        maxTokens: Int,
        requestedGpuLayers: Int,
        enableVulkanGpu: Boolean,
        enableSpeculativeDecoding: Boolean,
        speculativeDraftTokens: Int,
        speculativeDraftModelPath: String?,
        contextWindowSize: Int,
        prefillChunkSize: Int
    ): Flow<StreamEvent> = callbackFlow {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            close(IOException("llama.cpp engine requires Android 8.0 (API 26) or newer."))
            return@callbackFlow
        }
        if (!model.format.equals("gguf", ignoreCase = true)) {
            close(
                IOException(
                    "llama.cpp requires a .gguf model file, but '${model.fileName}' is .${model.format}."
                )
            )
            return@callbackFlow
        }

        val validDraftPath = speculativeDraftModelPath
            ?.takeIf { it.isNotBlank() && it != modelFile.absolutePath }
            ?.let { path ->
                val f = File(path)
                if (f.exists() && validateGgufFile(f) == null) path else null
            }

        val finished = AtomicBoolean(false)
        val doneLatch = CountDownLatch(1)

        val workerJob = launch(nativeDispatcher) {
            try {
                ensureLlamaLoaded(
                    model = model,
                    modelFile = modelFile,
                    hardwareBackend = hardwareBackend,
                    threads = threads,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    requestedGpuLayers = requestedGpuLayers,
                    enableVulkanGpu = enableVulkanGpu,
                    enableSpeculativeDecoding = enableSpeculativeDecoding,
                    speculativeDraftTokens = speculativeDraftTokens,
                    validDraftPath = validDraftPath,
                    contextWindowSize = contextWindowSize,
                    prefillChunkSize = prefillChunkSize
                )
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
            val prompt = formatGgufPrompt(messages, templateStyle, maxContextChars = contextWindowSize * 3)
            val stopMarkers = templateStyle.stopMarkers
            val modelLabel = "${model.displayName} ($activeLlamaProfileLabel)"
            val requestStartNs = System.nanoTime()
            var firstTokenNs = 0L
            var chunkCount = 0
            val tailBuffer = StringBuilder(48)

            try {
                LlamaBridge.generateStream(
                    prompt = prompt,
                    callback = object : GenStream {
                        override fun onDelta(text: String) {
                            if (finished.get() || text.isEmpty()) return

                            if (firstTokenNs == 0L) {
                                firstTokenNs = System.nanoTime()
                            }

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
                                    emitCompletedStats(
                                        requestStartNs = requestStartNs,
                                        firstTokenNs = firstTokenNs,
                                        chunkCount = chunkCount,
                                        kvReused = false,
                                        modelLabel = modelLabel
                                    ) { trySend(it) }
                                    doneLatch.countDown()
                                    close()
                                }
                                return
                            }
                            if (tailBuffer.length > 36) {
                                tailBuffer.delete(0, tailBuffer.length - 36)
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
                                emitCompletedStats(
                                    requestStartNs = requestStartNs,
                                    firstTokenNs = firstTokenNs,
                                    chunkCount = chunkCount,
                                    kvReused = false,
                                    modelLabel = modelLabel
                                ) { trySend(it) }
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
    // 2. LiteRT-LM (Qualcomm QNN Hexagon HTP NPU + Adreno/Mali OpenCL/Vulkan GPU)
    //    MLC-Style Persistent Multi-Turn KV Cache: reuses live `Conversation`
    //    across turns so follow-up messages skip history re-prefill completely!
    // -------------------------------------------------------------------------

    private fun streamWithLiteRtLm(
        model: DownloadedModelEntity,
        modelFile: File,
        messages: List<ChatMessageDto>,
        hardwareBackend: LocalHardwareBackend,
        temperature: Float,
        enableVulkanGpu: Boolean,
        contextWindowSize: Int,
        enablePersistentKvCache: Boolean,
        conversationKey: String
    ): Flow<StreamEvent> = callbackFlow {
        val ext = model.format.lowercase()
        if (ext != "litertlm" && ext != "task") {
            close(
                IOException(
                    "LiteRT-LM NPU/GPU runs .litertlm or .task bundles, not .${model.format}. " +
                        "Download a .litertlm model from Model Hub, or select llama.cpp for .gguf."
                )
            )
            return@callbackFlow
        }

        val engine = try {
            getOrCreateLiteRtEngine(
                modelPath = modelFile.absolutePath,
                hardwareBackend = hardwareBackend,
                enableVulkanGpu = enableVulkanGpu,
                contextWindowSize = contextWindowSize
            )
        } catch (t: Throwable) {
            close(IOException("LiteRT-LM NPU/GPU init error: ${t.localizedMessage ?: t.javaClass.simpleName}", t))
            return@callbackFlow
        }

        val systemMsg = messages.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val turns = messages.filter { it.role != "system" }
        val desiredSessionKey = listOf(
            conversationKey,
            modelFile.absolutePath,
            hardwareBackend.id,
            contextWindowSize,
            systemMsg.hashCode()
        ).joinToString("|")

        val kvCacheHit: Boolean
        val conversation: Conversation
        val userPayload: String

        synchronized(lock) {
            val existingConv = persistentLiteRtConversation
            val canReuseKv = enablePersistentKvCache &&
                existingConv != null &&
                liteRtSessionKey == desiredSessionKey &&
                turns.size > liteRtSyncedTurns &&
                liteRtSyncedTurns > 0

            if (canReuseKv && existingConv != null) {
                // MLC Chat KV-Cache HIT: send ONLY the newest user message!
                kvCacheHit = true
                conversation = existingConv
                userPayload = turns.lastOrNull()?.let(::renderMessageWithAttachments).orEmpty()
            } else {
                closeLiteRtConversationLocked()
                kvCacheHit = false
                val convConfig = ConversationConfig(
                    systemInstruction = if (systemMsg.isNotBlank()) Contents.of(systemMsg) else null,
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = temperature.coerceIn(0.05f, 1.5f).toDouble()
                    )
                )
                val created = engine.createConversation(convConfig)
                if (enablePersistentKvCache) {
                    persistentLiteRtConversation = created
                    liteRtSessionKey = desiredSessionKey
                    liteRtSyncedTurns = 0
                }
                conversation = created
                userPayload = buildLiteRtConversationPrompt(messages)
            }
        }

        val kvBadge = if (kvCacheHit) " · KV Hit" else ""
        val modelLabel = "${model.displayName} (LiteRT-LM · $activeLiteRtBackendLabel$kvBadge)"
        val requestStartNs = System.nanoTime()
        var firstTokenNs = 0L
        var chunkCount = 0

        try {
            conversation.sendMessageAsync(userPayload).collect { response ->
                val token = response.toString()
                if (token.isNotEmpty()) {
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    chunkCount++
                    trySend(
                        StreamEvent.Chunk(
                            contentDelta = token,
                            modelName = modelLabel
                        )
                    )
                }
            }

            synchronized(lock) {
                if (enablePersistentKvCache && persistentLiteRtConversation === conversation) {
                    // User turn + newly generated assistant turn are now in KV cache
                    liteRtSyncedTurns = turns.size + 1
                } else {
                    runCatching { conversation.close() }
                }
            }

            emitCompletedStats(
                requestStartNs = requestStartNs,
                firstTokenNs = firstTokenNs,
                chunkCount = chunkCount,
                kvReused = kvCacheHit,
                modelLabel = modelLabel
            ) { trySend(it) }
            close()
        } catch (ce: CancellationException) {
            runCatching { conversation.cancelProcess() }
            synchronized(lock) {
                if (persistentLiteRtConversation === conversation) {
                    closeLiteRtConversationLocked()
                } else {
                    runCatching { conversation.close() }
                }
            }
            throw ce
        } catch (t: Throwable) {
            runCatching { conversation.cancelProcess() }
            synchronized(lock) {
                if (persistentLiteRtConversation === conversation) {
                    closeLiteRtConversationLocked()
                } else {
                    runCatching { conversation.close() }
                }
            }
            close(IOException("LiteRT-LM generation failed: ${t.localizedMessage}", t))
        }

        awaitClose {
            runCatching { conversation.cancelProcess() }
        }
    }.flowOn(nativeDispatcher)

    // -------------------------------------------------------------------------
    // 3. MediaPipe GenAI (.task / .bin GPU/CPU with Persistent KV Session Reuse)
    // -------------------------------------------------------------------------

    private fun streamWithMediaPipe(
        model: DownloadedModelEntity,
        modelFile: File,
        messages: List<ChatMessageDto>,
        hardwareBackend: LocalHardwareBackend,
        temperature: Float,
        maxTokens: Int,
        contextWindowSize: Int,
        enablePersistentKvCache: Boolean,
        conversationKey: String
    ): Flow<StreamEvent> = callbackFlow {
        val ext = model.format.lowercase()
        if (ext != "task" && ext != "bin") {
            close(
                IOException(
                    "MediaPipe GenAI runs .task / .bin bundles, not .${model.format}."
                )
            )
            return@callbackFlow
        }

        val effectiveMaxTokens = maxOf(maxTokens, contextWindowSize).coerceIn(512, 4096)
        val engine = try {
            getOrCreateMediaPipeInference(modelFile.absolutePath, effectiveMaxTokens, hardwareBackend)
        } catch (t: Throwable) {
            close(IOException("MediaPipe init error: ${t.localizedMessage ?: t.javaClass.simpleName}", t))
            return@callbackFlow
        }

        val systemMsg = messages.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val turns = messages.filter { it.role != "system" }
        val desiredKey = listOf(
            conversationKey,
            modelFile.absolutePath,
            hardwareBackend.id,
            effectiveMaxTokens,
            systemMsg.hashCode()
        ).joinToString("|")

        val kvCacheHit: Boolean
        val session: LlmInferenceSession
        val promptChunk: String

        synchronized(lock) {
            val existingSession = persistentMediaPipeSession
            val canReuse = enablePersistentKvCache &&
                existingSession != null &&
                mediaPipeSessionKey == desiredKey &&
                turns.size > mediaPipeSyncedTurns &&
                mediaPipeSyncedTurns > 0

            if (canReuse && existingSession != null) {
                kvCacheHit = true
                session = existingSession
                val latest = turns.lastOrNull()?.let(::renderMessageWithAttachments).orEmpty()
                promptChunk = "<start_of_turn>user\n$latest\n<end_of_turn>\n<start_of_turn>model\n"
            } else {
                closeMediaPipeSessionLocked()
                kvCacheHit = false
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(temperature.coerceIn(0.05f, 1.5f))
                    .setTopK(40)
                    .build()
                val created = LlmInferenceSession.createFromOptions(engine, sessionOptions)
                if (enablePersistentKvCache) {
                    persistentMediaPipeSession = created
                    mediaPipeSessionKey = desiredKey
                    mediaPipeSyncedTurns = 0
                }
                session = created
                promptChunk = formatGgufPrompt(messages, GgufTemplateStyle.GEMMA, maxContextChars = effectiveMaxTokens * 3)
            }
        }

        val backendTag = when (hardwareBackend) {
            LocalHardwareBackend.NPU -> "GPU/NPU"
            LocalHardwareBackend.GPU -> "GPU"
            LocalHardwareBackend.CPU -> "CPU"
        }
        val kvBadge = if (kvCacheHit) " · KV Hit" else ""
        val modelLabel = "${model.displayName} (MediaPipe · $backendTag$kvBadge)"

        val requestStartNs = System.nanoTime()
        var firstTokenNs = 0L
        var chunkCount = 0

        try {
            session.addQueryChunk(promptChunk)
            session.generateResponseAsync { partialResult, done ->
                if (!partialResult.isNullOrEmpty()) {
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    chunkCount++
                    trySend(
                        StreamEvent.Chunk(
                            contentDelta = partialResult,
                            modelName = modelLabel
                        )
                    )
                }
                if (done) {
                    synchronized(lock) {
                        if (enablePersistentKvCache && persistentMediaPipeSession === session) {
                            mediaPipeSyncedTurns = turns.size + 1
                        } else {
                            runCatching { session.close() }
                        }
                    }
                    emitCompletedStats(
                        requestStartNs = requestStartNs,
                        firstTokenNs = firstTokenNs,
                        chunkCount = chunkCount,
                        kvReused = kvCacheHit,
                        modelLabel = modelLabel
                    ) { trySend(it) }
                    close()
                }
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                if (persistentMediaPipeSession === session) {
                    closeMediaPipeSessionLocked()
                } else {
                    runCatching { session.close() }
                }
            }
            close(IOException("MediaPipe generation failed: ${t.localizedMessage}", t))
            return@callbackFlow
        }

        awaitClose {
            runCatching { session.cancelGenerateResponseAsync() }
            if (!enablePersistentKvCache) {
                runCatching { session.close() }
            }
        }
    }.flowOn(nativeDispatcher)

    private fun getOrCreateLiteRtEngine(
        modelPath: String,
        hardwareBackend: LocalHardwareBackend,
        enableVulkanGpu: Boolean,
        contextWindowSize: Int
    ): Engine {
        synchronized(lock) {
            val safeMaxTokens = contextWindowSize.coerceIn(1024, 8192)
            val existing = liteRtEngine
            if (existing != null &&
                loadedLiteRtPath == modelPath &&
                loadedLiteRtBackend == hardwareBackend &&
                loadedLiteRtContextWindow == safeMaxTokens
            ) {
                return existing
            }
            unloadMediaPipeLocked()
            unloadLlamaLocked()
            unloadLiteRtLocked()

            val qnnStatus = QnnRuntimeConfigurator.configureAndInspect(context)
            val vulkanStatus = VulkanGpuConfigurator.configureAndInspect(context, enableVulkan = enableVulkanGpu)
            val nativeDir = qnnStatus.nativeLibDir
            val cacheDir = qnnStatus.qnnCacheDir
            val npuLabel = if (qnnStatus.isQnnHtpBundled || qnnStatus.isLiteRtDispatchBundled) {
                "QNN ${qnnStatus.hexagonArch}"
            } else {
                "NPU"
            }
            val gpuLabel = if (enableVulkanGpu && vulkanStatus.supported) {
                "GPU ${vulkanStatus.versionLabel}"
            } else {
                "GPU OpenCL"
            }

            val candidates: List<Pair<String, Backend>> = when (hardwareBackend) {
                LocalHardwareBackend.NPU -> listOf(
                    npuLabel to Backend.NPU(nativeLibraryDir = nativeDir),
                    "$gpuLabel (NPU fallback)" to Backend.GPU(),
                    "CPU (NPU fallback)" to Backend.CPU()
                )

                LocalHardwareBackend.GPU -> listOf(
                    gpuLabel to Backend.GPU(),
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
                        maxNumTokens = safeMaxTokens,
                        cacheDir = cacheDir
                    )
                    val created = Engine(config).apply { initialize() }
                    liteRtEngine = created
                    loadedLiteRtPath = modelPath
                    loadedLiteRtBackend = hardwareBackend
                    loadedLiteRtContextWindow = safeMaxTokens
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
            if (existing != null &&
                loadedMediaPipePath == modelPath &&
                loadedMediaPipeMaxTokens == maxTokens &&
                loadedMediaPipeBackend == hardwareBackend
            ) {
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
            loadedMediaPipeBackend = hardwareBackend
            return created
        }
    }

    private fun closeLiteRtConversationLocked() {
        runCatching { persistentLiteRtConversation?.close() }
        persistentLiteRtConversation = null
        liteRtSessionKey = null
        liteRtSyncedTurns = 0
    }

    private fun closeMediaPipeSessionLocked() {
        runCatching { persistentMediaPipeSession?.close() }
        persistentMediaPipeSession = null
        mediaPipeSessionKey = null
        mediaPipeSyncedTurns = 0
    }

    private fun unloadMediaPipeLocked() {
        closeMediaPipeSessionLocked()
        runCatching { mediaPipeInference?.close() }
        mediaPipeInference = null
        loadedMediaPipePath = null
        loadedMediaPipeBackend = null
    }

    private fun unloadLiteRtLocked() {
        closeLiteRtConversationLocked()
        runCatching { liteRtEngine?.close() }
        liteRtEngine = null
        loadedLiteRtPath = null
        loadedLiteRtBackend = null
        loadedLiteRtContextWindow = 0
    }

    private fun unloadLlamaLocked() {
        if (loadedLlamaPath != null) {
            runCatching { LlamaBridge.nativeCancelGenerate() }
            runCatching { LlamaBridge.shutdownMtp() }
            runCatching { LlamaBridge.shutdown() }
            loadedLlamaPath = null
            loadedLlamaProfileKey = null
            loadedLlamaProfile = null
            activeSpeculativeLabel = null
        }
    }

    fun unloadModel() {
        synchronized(lock) {
            unloadMediaPipeLocked()
            unloadLiteRtLocked()
            unloadLlamaLocked()
            mlcBridge.unload()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: MLC Runtime Telemetry, GGUF check, thread tuning, chat templates
    // -------------------------------------------------------------------------

    private fun emitCompletedStats(
        requestStartNs: Long,
        firstTokenNs: Long,
        chunkCount: Int,
        kvReused: Boolean,
        modelLabel: String,
        send: (StreamEvent.Completed) -> Unit
    ) {
        val endNs = System.nanoTime()
        val ttftMs = if (firstTokenNs > 0L) ((firstTokenNs - requestStartNs) / 1_000_000L).coerceAtLeast(1L) else null
        // Measure true DECODE tok/s (post-TTFT), matching MLC Chat's RuntimeStats!
        val decodeTps = if (firstTokenNs > 0L && chunkCount > 2) {
            val decodeSec = (endNs - firstTokenNs) / 1_000_000_000f
            if (decodeSec > 0.05f) (chunkCount - 1) / decodeSec else null
        } else {
            val totalSec = (endNs - requestStartNs) / 1_000_000_000f
            if (chunkCount > 2 && totalSec > 0.1f) chunkCount / totalSec else null
        }
        send(
            StreamEvent.Completed(
                tokensPerSecond = decodeTps,
                modelName = modelLabel,
                ttftMs = ttftMs,
                kvCacheReused = kvReused
            )
        )
    }

    private fun supportsNativeMtpHeads(fileName: String, repoId: String): Boolean {
        val lower = "$fileName $repoId".lowercase()
        return lower.contains("mtp") ||
            lower.contains("qwen3") ||
            lower.contains("glm-4") ||
            lower.contains("deepseek-v3")
    }

    private fun validateGgufFile(file: File): String? {
        if (!file.exists()) return "GGUF file not found on disk: ${file.name}"
        val size = file.length()
        if (size < 1024L * 1024L) {
            return "GGUF file '${file.name}' is too small (${size / 1024} KB) — download was likely interrupted or gated. Delete and re-download."
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
            return "File '${file.name}' is not a valid GGUF binary (missing GGUF magic header)."
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
        // On 8-core big.LITTLE mobile SoCs, pinning to performance cores (4-6 threads) avoids E-core stragglers
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
            else -> GgufTemplateStyle.CHATML
        }
    }

    private fun buildLiteRtConversationPrompt(messages: List<ChatMessageDto>): String {
        val turns = messages.filter { it.role != "system" }.takeLast(8)
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
            appendLine(att.extractedText.take(6000))
            appendLine("```")
        } else {
            appendLine("\n[Attached file: ${att.displayName} (${att.mimeType})]")
        }
    }

    private fun formatGgufPrompt(
        messages: List<ChatMessageDto>,
        style: GgufTemplateStyle,
        maxContextChars: Int = 6000
    ): String {
        val systemMsg = messages.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        // MLC-style sliding window trim: keep most recent turns fitting within context char budget
        val nonSystem = messages.filter { it.role != "system" }
        var charBudget = (maxContextChars - systemMsg.length).coerceAtLeast(1024)
        val selectedReversed = mutableListOf<ChatMessageDto>()
        for (msg in nonSystem.asReversed()) {
            val renderedLen = msg.content.length + 64
            if (selectedReversed.isNotEmpty() && renderedLen > charBudget) break
            selectedReversed.add(msg)
            charBudget -= renderedLen
        }
        val turns = selectedReversed.asReversed()

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
