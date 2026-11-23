package com.example.data.localmodel

import android.content.Context
import android.os.Build
import com.example.data.attachments.MessageAttachment
import com.example.data.local.DownloadedModelEntity
import com.example.data.network.ChatMessageDto
import com.example.data.network.StreamEvent
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException

class OnDeviceLlmEngine(private val context: Context) {

    @Volatile
    private var loadedMediaPipePath: String? = null
    @Volatile
    private var loadedMediaPipeMaxTokens: Int = 0
    @Volatile
    private var mediaPipeInference: LlmInference? = null

    @Volatile
    private var loadedLlamaPath: String? = null

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

        val resolvedEngine = LocalInferenceEngine.resolveForFormat(preferredEngine, model.format)

        return when (resolvedEngine) {
            LocalInferenceEngine.LLAMA_CPP -> streamWithLlamaCpp(
                model = model,
                modelFile = modelFile,
                messages = messages,
                threads = threads,
                temperature = temperature,
                maxTokens = maxTokens
            )

            LocalInferenceEngine.LITERT_LM -> streamWithLiteRtLm(
                model = model,
                modelFile = modelFile,
                messages = messages,
                hardwareBackend = hardwareBackend
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

    private fun streamWithLlamaCpp(
        model: DownloadedModelEntity,
        modelFile: File,
        messages: List<ChatMessageDto>,
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
                        "Switch engine to Auto or download a GGUF model from Hugging Face."
                )
            )
            return@callbackFlow
        }

        try {
            synchronized(lock) {
                if (loadedLlamaPath != modelFile.absolutePath) {
                    unloadMediaPipeLocked()
                    unloadLiteRtLocked()
                    runCatching { LlamaBridge.shutdown() }
                    val initialized = LlamaBridge.initGenerateModel(modelFile.absolutePath)
                    if (!initialized) {
                        throw IOException("llama.cpp failed to load GGUF model: ${model.fileName}")
                    }
                    loadedLlamaPath = modelFile.absolutePath
                }
            }
        } catch (t: Throwable) {
            close(IOException("llama.cpp init error: ${t.localizedMessage ?: t.javaClass.simpleName}", t))
            return@callbackFlow
        }

        val prompt = formatChatMlPrompt(messages)
        val modelLabel = "${model.displayName} (llama.cpp)"
        val startNs = System.nanoTime()
        var chunkCount = 0

        try {
            LlamaBridge.updateGenerateParams(
                temperature = temperature.coerceIn(0.05f, 2.0f),
                maxTokens = maxTokens.coerceIn(128, 4096),
                topP = 0.9f,
                topK = 40,
                repeatPenalty = 1.1f,
                contextLength = 4096,
                numThreads = threads.coerceIn(1, 8),
                useMmap = true,
                flashAttention = false,
                batchSize = 512
            )
        } catch (_: Throwable) {
            // Fall back to default llama.cpp sampling params if unsupported
        }

        val streamThread = Thread {
            try {
                LlamaBridge.generateStream(
                    prompt = prompt,
                    callback = object : GenStream {
                        override fun onDelta(text: String) {
                            if (text.isNotEmpty()) {
                                chunkCount++
                                trySend(
                                    StreamEvent.Chunk(
                                        contentDelta = text,
                                        modelName = modelLabel
                                    )
                                )
                            }
                        }

                        override fun onComplete() {
                            val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
                            val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
                            trySend(StreamEvent.Completed(tokensPerSecond = tps, modelName = modelLabel))
                            close()
                        }

                        override fun onError(message: String) {
                            close(IOException("llama.cpp error: $message"))
                        }
                    }
                )
            } catch (t: Throwable) {
                close(IOException("llama.cpp generation failed: ${t.localizedMessage}", t))
            }
        }
        streamThread.start()

        awaitClose {
            runCatching { LlamaBridge.nativeCancelGenerate() }
        }
    }.flowOn(Dispatchers.IO)

    private fun streamWithLiteRtLm(
        model: DownloadedModelEntity,
        modelFile: File,
        messages: List<ChatMessageDto>,
        hardwareBackend: LocalHardwareBackend
    ): Flow<StreamEvent> = callbackFlow {
        val ext = model.format.lowercase()
        if (ext != "litertlm" && ext != "task") {
            close(
                IOException(
                    "LiteRT-LM runs .litertlm or .task bundles, not .${model.format}. " +
                        "Select Auto engine or llama.cpp for GGUF files."
                )
            )
            return@callbackFlow
        }

        val engine = try {
            getOrCreateLiteRtEngine(modelFile.absolutePath, hardwareBackend)
        } catch (t: Throwable) {
            close(IOException("LiteRT-LM init error: ${t.localizedMessage ?: t.javaClass.simpleName}", t))
            return@callbackFlow
        }

        val prompt = formatGemmaPrompt(messages)
        val modelLabel = "${model.displayName} (LiteRT-LM · $activeLiteRtBackendLabel)"
        val startNs = System.nanoTime()
        var chunkCount = 0

        try {
            engine.createConversation().use { conversation ->
                conversation.sendMessageAsync(prompt).collect { response ->
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
            val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
            val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
            trySend(StreamEvent.Completed(tokensPerSecond = tps, modelName = modelLabel))
            close()
        } catch (t: Throwable) {
            close(IOException("LiteRT-LM generation failed: ${t.localizedMessage}", t))
        }

        awaitClose { }
    }.flowOn(Dispatchers.IO)

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

        val prompt = formatGemmaPrompt(messages)
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
    }.flowOn(Dispatchers.IO)

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

            val nativeDir = context.applicationInfo.nativeLibraryDir.orEmpty()
            val candidates: List<Pair<String, Backend>> = when (hardwareBackend) {
                LocalHardwareBackend.NPU -> listOf(
                    "NPU" to Backend.NPU(nativeLibraryDir = nativeDir),
                    "GPU (NPU fallback)" to Backend.GPU(),
                    "CPU (NPU fallback)" to Backend.CPU()
                )

                LocalHardwareBackend.GPU -> listOf(
                    "GPU" to Backend.GPU(),
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
                        cacheDir = context.cacheDir.absolutePath
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
            throw lastError ?: IOException("Failed to initialize LiteRT-LM engine on any backend.")
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
            runCatching { LlamaBridge.shutdown() }
            loadedLlamaPath = null
        }
    }

    fun unloadModel() {
        synchronized(lock) {
            unloadMediaPipeLocked()
            unloadLiteRtLocked()
            unloadLlamaLocked()
        }
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

    private fun formatChatMlPrompt(messages: List<ChatMessageDto>): String {
        val systemMsg = messages.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val turns = messages.filter { it.role != "system" }.takeLast(10)
        return buildString {
            if (systemMsg.isNotEmpty()) {
                append("<|im_start|>system\n")
                append(systemMsg)
                append("<|im_end|>\n")
            }
            turns.forEach { msg ->
                val role = if (msg.role == "user") "user" else "assistant"
                append("<|im_start|>")
                append(role)
                append("\n")
                append(renderMessageWithAttachments(msg))
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    private fun formatGemmaPrompt(messages: List<ChatMessageDto>): String {
        val systemMsg = messages.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val turns = messages.filter { it.role != "system" }.takeLast(8)
        return buildString {
            if (systemMsg.isNotEmpty()) {
                append("<start_of_turn>user\nSystem instructions: ")
                append(systemMsg)
                append("\n<end_of_turn>\n<start_of_turn>model\nUnderstood.<end_of_turn>\n")
            }
            turns.forEach { msg ->
                val tag = if (msg.role == "user") "user" else "model"
                append("<start_of_turn>")
                append(tag)
                append("\n")
                append(renderMessageWithAttachments(msg))
                append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }
    }
}
