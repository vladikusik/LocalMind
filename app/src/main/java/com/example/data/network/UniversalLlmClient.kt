package com.example.data.network

import com.example.data.attachments.AttachmentProcessor
import com.example.data.attachments.MessageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class UniversalLlmClient(
    private val attachmentProcessor: AttachmentProcessor
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val probeHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val streamingHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    fun normalizeBaseUrl(provider: ApiProvider, rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return provider.defaultBaseUrl
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return when (provider.protocol) {
            ApiProtocol.GEMINI, ApiProtocol.ANTHROPIC -> withScheme
            ApiProtocol.ON_DEVICE_MEDIAPIPE -> "local://device"
            ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OLLAMA -> {
                if (withScheme.endsWith("/v1", ignoreCase = true) ||
                    withScheme.contains("/v1/", ignoreCase = true) ||
                    withScheme.endsWith("/openai", ignoreCase = true)
                ) {
                    withScheme
                } else {
                    "$withScheme/v1"
                }
            }
        }
    }

    private fun validateUrl(fullUrl: String): String {
        val parsed = fullUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid server URL: $fullUrl")
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw IllegalArgumentException("Only http:// and https:// URLs are supported.")
        }
        return parsed.toString()
    }

    suspend fun fetchModels(
        provider: ApiProvider,
        baseUrl: String,
        apiKey: String
    ): Result<LmStudioHealth> = withContext(Dispatchers.IO) {
        runCatching {
            when (provider.protocol) {
                ApiProtocol.GEMINI -> fetchGeminiModels(baseUrl, apiKey)
                ApiProtocol.ANTHROPIC -> fetchAnthropicModels(baseUrl, apiKey)
                ApiProtocol.OLLAMA -> fetchOllamaModels(baseUrl, apiKey)
                ApiProtocol.OPENAI_COMPATIBLE -> fetchOpenAiModels(provider, baseUrl, apiKey)
                ApiProtocol.ON_DEVICE_MEDIAPIPE -> LmStudioHealth(emptyList(), 1L)
            }
        }
    }

    private fun fetchOpenAiModels(
        provider: ApiProvider,
        baseUrl: String,
        apiKey: String
    ): LmStudioHealth {
        val normalized = normalizeBaseUrl(provider, baseUrl)
        val endpoint = validateUrl("$normalized/models")
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .get()
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        if (provider == ApiProvider.OPENROUTER || provider == ApiProvider.OPENROUTER_FREE) {
            reqBuilder.header("HTTP-Referer", "https://localmind.app")
            reqBuilder.header("X-Title", "LocalMind")
        }

        val startNs = System.nanoTime()
        probeHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = extractErrorMessage(body) ?: response.message
                throw IOException("HTTP ${response.code}: $msg")
            }
            val models = parseOpenAiModelIds(body)
            return LmStudioHealth(
                models = models.ifEmpty { provider.suggestedModels },
                latencyMs = elapsedMs
            )
        }
    }

    private fun fetchOllamaModels(baseUrl: String, apiKey: String): LmStudioHealth {
        val openAiAttempt = runCatching { fetchOpenAiModels(ApiProvider.OLLAMA, baseUrl, apiKey) }
        if (openAiAttempt.isSuccess && openAiAttempt.getOrThrow().models.isNotEmpty()) {
            return openAiAttempt.getOrThrow()
        }
        val root = normalizeBaseUrl(ApiProvider.OLLAMA, baseUrl).removeSuffix("/v1")
        val endpoint = validateUrl("$root/api/tags")
        val req = Request.Builder().url(endpoint).get().build()
        val startNs = System.nanoTime()
        probeHttpClient.newCall(req).execute().use { response ->
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Ollama HTTP ${response.code}: ${response.message}")
            }
            val json = JSONObject(body)
            val arr = json.optJSONArray("models")
            val models = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                        if (name.isNotBlank()) add(name)
                    }
                }
            }
            return LmStudioHealth(models = models, latencyMs = elapsedMs)
        }
    }

    private fun fetchAnthropicModels(baseUrl: String, apiKey: String): LmStudioHealth {
        if (apiKey.isBlank()) throw IllegalArgumentException("Anthropic API key is required.")
        val normalized = normalizeBaseUrl(ApiProvider.ANTHROPIC, baseUrl)
        val endpoint = validateUrl("$normalized/models")
        val req = Request.Builder()
            .url(endpoint)
            .get()
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "application/json")
            .build()

        val startNs = System.nanoTime()
        probeHttpClient.newCall(req).execute().use { response ->
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = extractErrorMessage(body) ?: response.message
                throw IOException("Anthropic HTTP ${response.code}: $msg")
            }
            val models = parseOpenAiModelIds(body)
            return LmStudioHealth(
                models = models.ifEmpty { ApiProvider.ANTHROPIC.suggestedModels },
                latencyMs = elapsedMs
            )
        }
    }

    private fun fetchGeminiModels(baseUrl: String, apiKey: String): LmStudioHealth {
        if (apiKey.isBlank()) throw IllegalArgumentException("Google Gemini API key is required.")
        val normalized = normalizeBaseUrl(ApiProvider.GEMINI, baseUrl)
        val encodedKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
        val endpoint = validateUrl("$normalized/models?key=$encodedKey")
        val req = Request.Builder().url(endpoint).get().build()

        val startNs = System.nanoTime()
        probeHttpClient.newCall(req).execute().use { response ->
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = extractErrorMessage(body) ?: response.message
                throw IOException("Gemini HTTP ${response.code}: $msg")
            }
            val json = JSONObject(body)
            val arr = json.optJSONArray("models")
            val models = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val methods = obj.optJSONArray("supportedGenerationMethods")
                        var supportsGenerate = false
                        if (methods != null) {
                            for (m in 0 until methods.length()) {
                                if (methods.optString(m) == "generateContent") {
                                    supportsGenerate = true
                                    break
                                }
                            }
                        }
                        if (supportsGenerate) {
                            val name = obj.optString("name").removePrefix("models/")
                            if (name.isNotBlank()) add(name)
                        }
                    }
                }
            }
            return LmStudioHealth(
                models = models.ifEmpty { ApiProvider.GEMINI.suggestedModels },
                latencyMs = elapsedMs
            )
        }
    }

    fun streamChatCompletion(
        provider: ApiProvider,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> = when (provider.protocol) {
        ApiProtocol.ANTHROPIC -> streamAnthropic(baseUrl, apiKey, model, messages, temperature, maxTokens)
        ApiProtocol.GEMINI -> streamGemini(baseUrl, apiKey, model, messages, temperature, maxTokens)
        ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.OLLAMA ->
            streamOpenAiCompatible(provider, baseUrl, apiKey, model, messages, temperature, maxTokens)
        ApiProtocol.ON_DEVICE_MEDIAPIPE -> callbackFlow {
            close(IllegalStateException("On-device requests must be routed to OnDeviceLlmEngine."))
        }
    }

    private fun buildTextWithFileContents(baseText: String, attachments: List<MessageAttachment>): String {
        val fileAttachments = attachments.filterNot { it.isImage }
        if (fileAttachments.isEmpty()) return baseText
        return buildString {
            if (baseText.isNotBlank()) {
                appendLine(baseText)
                appendLine()
            }
            fileAttachments.forEach { file ->
                appendLine("[Attached File: ${file.displayName} (${file.mimeType})]")
                if (!file.extractedText.isNullOrBlank()) {
                    appendLine("```")
                    appendLine(file.extractedText)
                    appendLine("```")
                } else {
                    appendLine("(Binary file attached: ${file.sizeBytes} bytes)")
                }
                appendLine()
            }
        }.trim()
    }

    private fun streamOpenAiCompatible(
        provider: ApiProvider,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> = callbackFlow {
        val normalized = normalizeBaseUrl(provider, baseUrl)
        val endpoint = try {
            validateUrl("$normalized/chat/completions")
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        if (provider.requiresApiKey && apiKey.isBlank()) {
            close(IllegalArgumentException("${provider.displayName} requires an API key in Settings."))
            return@callbackFlow
        }

        val effectiveModel = model.ifBlank {
            provider.suggestedModels.firstOrNull() ?: "local-model"
        }

        val messagesJson = JSONArray().apply {
            messages.forEach { msg ->
                val combinedText = buildTextWithFileContents(msg.content, msg.attachments)
                val images = msg.attachments.filter { it.isImage }
                val messageObj = JSONObject().apply {
                    put("role", msg.role)
                    if (images.isEmpty()) {
                        put("content", combinedText)
                    } else {
                        val contentArray = JSONArray()
                        if (combinedText.isNotBlank()) {
                            contentArray.put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", combinedText)
                                }
                            )
                        }
                        images.forEach { img ->
                            val b64 = attachmentProcessor.readBase64(img)
                            if (b64 != null) {
                                contentArray.put(
                                    JSONObject().apply {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            JSONObject().put("url", "data:${img.mimeType};base64,$b64")
                                        )
                                    }
                                )
                            }
                        }
                        put("content", contentArray)
                    }
                }
                put(messageObj)
            }
        }

        val bodyJson = JSONObject().apply {
            put("model", effectiveModel)
            put("messages", messagesJson)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            put("stream", true)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        if (provider == ApiProvider.OPENROUTER || provider == ApiProvider.OPENROUTER_FREE) {
            reqBuilder.header("HTTP-Referer", "https://localmind.app")
            reqBuilder.header("X-Title", "LocalMind")
        }

        val call = streamingHttpClient.newCall(reqBuilder.build())
        val startNs = System.nanoTime()
        var chunkCount = 0
        var resolvedModel: String? = effectiveModel

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) close() else close(
                    IOException("Could not reach ${provider.displayName} at $endpoint (${e.localizedMessage ?: "connection failed"}).", e)
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        val errBody = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                        val detail = extractErrorMessage(errBody) ?: res.message
                        close(IOException("${provider.displayName} HTTP ${res.code}: $detail"))
                        return
                    }
                    val body = res.body ?: run {
                        close(IOException("Empty response body"))
                        return
                    }
                    val contentType = res.header("Content-Type").orEmpty()
                    if (contentType.contains("application/json", ignoreCase = true) &&
                        !contentType.contains("text/event-stream", ignoreCase = true)
                    ) {
                        val text = body.string()
                        runCatching {
                            val obj = JSONObject(text)
                            resolvedModel = obj.optString("model").ifBlank { resolvedModel }
                            val msg = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                            val content = msg?.optString("content").orEmpty()
                            val reasoning = msg?.optString("reasoning_content").orEmpty()
                            if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                                trySend(StreamEvent.Chunk(content, reasoning, resolvedModel))
                            }
                        }
                        trySend(StreamEvent.Completed(null, resolvedModel))
                        close()
                        return
                    }

                    val source = body.source()
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                            val data = when {
                                trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                                trimmed.startsWith("{") -> trimmed
                                else -> continue
                            }
                            if (data == "[DONE]") break
                            runCatching {
                                val obj = JSONObject(data)
                                obj.optString("model").takeIf { it.isNotBlank() }?.let { resolvedModel = it }
                                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                                val contentDelta = delta?.optString("content").orEmpty()
                                val reasoningDelta = delta?.optString("reasoning_content").orEmpty()
                                    .ifEmpty { delta?.optString("reasoning").orEmpty() }
                                if (contentDelta.isNotEmpty() || reasoningDelta.isNotEmpty()) {
                                    chunkCount++
                                    trySend(StreamEvent.Chunk(contentDelta, reasoningDelta, resolvedModel))
                                }
                            }
                        }
                        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
                        val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
                        trySend(StreamEvent.Completed(tps, resolvedModel))
                        close()
                    } catch (e: IOException) {
                        if (call.isCanceled()) close() else close(e)
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun streamAnthropic(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            close(IllegalArgumentException("Anthropic Claude API key is required in Settings."))
            return@callbackFlow
        }
        val normalized = normalizeBaseUrl(ApiProvider.ANTHROPIC, baseUrl)
        val endpoint = try {
            validateUrl("$normalized/messages")
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        val effectiveModel = model.ifBlank { ApiProvider.ANTHROPIC.suggestedModels.first() }
        val systemPrompt = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val chatMessages = JSONArray().apply {
            messages.filter { it.role != "system" }.forEach { msg ->
                val textFilesOnly = msg.attachments.filter { !it.isImage && !it.mimeType.equals("application/pdf", true) }
                val combinedText = buildTextWithFileContents(msg.content, textFilesOnly)
                val binaryAttachments = msg.attachments.filter { it.isImage || it.mimeType.equals("application/pdf", true) }

                put(JSONObject().apply {
                    put("role", if (msg.role == "user") "user" else "assistant")
                    if (binaryAttachments.isEmpty()) {
                        put("content", combinedText)
                    } else {
                        val contentBlocks = JSONArray()
                        binaryAttachments.forEach { att ->
                            val b64 = attachmentProcessor.readBase64(att)
                            if (b64 != null) {
                                val blockType = if (att.isImage) "image" else "document"
                                contentBlocks.put(
                                    JSONObject().apply {
                                        put("type", blockType)
                                        put(
                                            "source",
                                            JSONObject().apply {
                                                put("type", "base64")
                                                put("media_type", att.mimeType)
                                                put("data", b64)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        if (combinedText.isNotBlank()) {
                            contentBlocks.put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", combinedText)
                                }
                            )
                        }
                        put("content", contentBlocks)
                    }
                })
            }
        }

        val bodyJson = JSONObject().apply {
            put("model", effectiveModel)
            put("max_tokens", maxTokens)
            put("temperature", temperature.coerceIn(0f, 1f).toDouble())
            put("stream", true)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", chatMessages)
        }

        val req = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .build()

        val call = streamingHttpClient.newCall(req)
        val startNs = System.nanoTime()
        var chunkCount = 0

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) close() else close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        val errBody = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                        close(IOException("Anthropic HTTP ${res.code}: ${extractErrorMessage(errBody) ?: res.message}"))
                        return
                    }
                    val source = res.body?.source() ?: run {
                        close(IOException("Empty Anthropic response"))
                        return
                    }
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val trimmed = line.trim()
                            if (!trimmed.startsWith("data:")) continue
                            val data = trimmed.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            runCatching {
                                val obj = JSONObject(data)
                                if (obj.optString("type") == "content_block_delta") {
                                    val delta = obj.optJSONObject("delta")
                                    when (delta?.optString("type")) {
                                        "text_delta" -> {
                                            val text = delta.optString("text")
                                            if (text.isNotEmpty()) {
                                                chunkCount++
                                                trySend(StreamEvent.Chunk(contentDelta = text, modelName = effectiveModel))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            val thought = delta.optString("thinking")
                                            if (thought.isNotEmpty()) {
                                                chunkCount++
                                                trySend(StreamEvent.Chunk(reasoningDelta = thought, modelName = effectiveModel))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
                        val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
                        trySend(StreamEvent.Completed(tps, effectiveModel))
                        close()
                    } catch (e: IOException) {
                        if (call.isCanceled()) close() else close(e)
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun streamGemini(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int
    ): Flow<StreamEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            close(IllegalArgumentException("Google Gemini API key is required in Settings."))
            return@callbackFlow
        }
        val normalized = normalizeBaseUrl(ApiProvider.GEMINI, baseUrl)
        val effectiveModel = model.ifBlank { ApiProvider.GEMINI.suggestedModels.first() }
            .removePrefix("models/")
        val encodedKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
        val endpoint = try {
            validateUrl("$normalized/models/$effectiveModel:streamGenerateContent?alt=sse&key=$encodedKey")
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        val systemPrompt = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val contentsArray = JSONArray().apply {
            messages.filter { it.role != "system" }.forEach { msg ->
                val parts = JSONArray()
                val textFilesOnly = msg.attachments.filter { !it.isImage && !it.mimeType.equals("application/pdf", true) }
                val combinedText = buildTextWithFileContents(msg.content, textFilesOnly)
                if (combinedText.isNotBlank()) {
                    parts.put(JSONObject().put("text", combinedText))
                }
                msg.attachments.filter { it.isImage || it.mimeType.equals("application/pdf", true) }.forEach { att ->
                    val b64 = attachmentProcessor.readBase64(att)
                    if (b64 != null) {
                        parts.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject().apply {
                                    put("mimeType", att.mimeType)
                                    put("data", b64)
                                }
                            )
                        )
                    }
                }
                if (parts.length() > 0) {
                    put(JSONObject().apply {
                        put("role", if (msg.role == "user") "user" else "model")
                        put("parts", parts)
                    })
                }
            }
        }

        val bodyJson = JSONObject().apply {
            if (systemPrompt.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                )
            }
            put("contents", contentsArray)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", temperature.toDouble())
                    put("maxOutputTokens", maxTokens)
                }
            )
        }

        val req = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .header("Accept", "text/event-stream")
            .build()

        val call = streamingHttpClient.newCall(req)
        val startNs = System.nanoTime()
        var chunkCount = 0

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) close() else close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        val errBody = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                        close(IOException("Gemini HTTP ${res.code}: ${extractErrorMessage(errBody) ?: res.message}"))
                        return
                    }
                    val source = res.body?.source() ?: run {
                        close(IOException("Empty Gemini response"))
                        return
                    }
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val trimmed = line.trim()
                            if (!trimmed.startsWith("data:")) continue
                            val data = trimmed.removePrefix("data:").trim()
                            runCatching {
                                val obj = JSONObject(data)
                                val parts = obj.optJSONArray("candidates")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("content")
                                    ?.optJSONArray("parts")
                                if (parts != null) {
                                    for (i in 0 until parts.length()) {
                                        val part = parts.optJSONObject(i) ?: continue
                                        val text = part.optString("text")
                                        val isThought = part.optBoolean("thought", false)
                                        if (text.isNotEmpty()) {
                                            chunkCount++
                                            if (isThought) {
                                                trySend(StreamEvent.Chunk(reasoningDelta = text, modelName = effectiveModel))
                                            } else {
                                                trySend(StreamEvent.Chunk(contentDelta = text, modelName = effectiveModel))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000f
                        val tps = if (chunkCount > 2 && elapsedSec > 0.1f) chunkCount / elapsedSec else null
                        trySend(StreamEvent.Completed(tps, effectiveModel))
                        close()
                    } catch (e: IOException) {
                        if (call.isCanceled()) close() else close(e)
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun parseOpenAiModelIds(body: String): List<String> {
        val trimmed = body.trim()
        return runCatching {
            val arr = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("data")
            } ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("name") }
                    if (id.isNotBlank()) add(id)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
