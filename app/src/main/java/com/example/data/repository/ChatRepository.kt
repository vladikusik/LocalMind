package com.example.data.repository

import android.content.ContentResolver
import android.net.Uri
import com.example.data.attachments.AttachmentProcessor
import com.example.data.attachments.MessageAttachment
import com.example.data.huggingface.HfModelFile
import com.example.data.huggingface.HfRepoSummary
import com.example.data.huggingface.HuggingFaceClient
import com.example.data.huggingface.ModelDownloadProgress
import com.example.data.imagegen.GeneratedImageDao
import com.example.data.imagegen.GeneratedImageEntity
import com.example.data.imagegen.LocalImageGenerator
import com.example.data.local.ChatDao
import com.example.data.local.ConversationEntity
import com.example.data.local.ConversationSummary
import com.example.data.local.DownloadedModelDao
import com.example.data.local.DownloadedModelEntity
import com.example.data.local.MessageEntity
import com.example.data.local.StorageStats
import com.example.data.localmodel.OnDeviceLlmEngine
import com.example.data.mcp.AgentNoteEntity
import com.example.data.mcp.AgentOrchestrator
import com.example.data.mcp.AgentProfileEntity
import com.example.data.mcp.BuiltInMcpTools
import com.example.data.mcp.DefaultAgents
import com.example.data.mcp.McpAgentDao
import com.example.data.mcp.McpClient
import com.example.data.mcp.McpServerEntity
import com.example.data.mcp.McpToolDefinition
import com.example.data.network.ApiProtocol
import com.example.data.network.ApiProvider
import com.example.data.network.ChatMessageDto
import com.example.data.network.LmStudioHealth
import com.example.data.network.StreamEvent
import com.example.data.network.UniversalLlmClient
import com.example.data.preferences.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.UUID

class ChatRepository(
    private val chatDao: ChatDao,
    private val downloadedModelDao: DownloadedModelDao,
    private val mcpAgentDao: McpAgentDao,
    private val generatedImageDao: GeneratedImageDao,
    private val llmClient: UniversalLlmClient,
    private val onDeviceEngine: OnDeviceLlmEngine,
    private val localImageGenerator: LocalImageGenerator,
    private val huggingFaceClient: HuggingFaceClient,
    private val attachmentProcessor: AttachmentProcessor,
    private val mcpClient: McpClient,
    private val builtInMcpTools: BuiltInMcpTools,
    private val agentOrchestrator: AgentOrchestrator
) {
    fun observeConversations(query: String): Flow<List<ConversationSummary>> =
        chatDao.observeConversations(query.trim())

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        chatDao.observeMessages(conversationId)

    fun observeStorageStats(): Flow<StorageStats> =
        chatDao.observeStorageStats()

    fun observeMcpServers(): Flow<List<McpServerEntity>> =
        mcpAgentDao.observeMcpServers()

    fun observeAgents(): Flow<List<AgentProfileEntity>> =
        mcpAgentDao.observeAgents()

    fun observeAgentNotes(): Flow<List<AgentNoteEntity>> =
        mcpAgentDao.observeNotes()

    fun getBuiltInToolDefinitions(): List<McpToolDefinition> =
        builtInMcpTools.definitions

    suspend fun ensureDefaultAgentsSeeded() {
        mcpAgentDao.insertAgentsIfAbsent(DefaultAgents.builtInList())
    }

    suspend fun addMcpServer(name: String, endpointUrl: String, authHeader: String): McpServerEntity {
        val entity = McpServerEntity(
            id = "mcp_${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifEmpty { "MCP Server" },
            endpointUrl = endpointUrl.trim(),
            authHeader = authHeader.trim(),
            isEnabled = true,
            cachedToolsJson = "[]",
            lastStatus = "Not synced yet",
            updatedAt = System.currentTimeMillis()
        )
        mcpAgentDao.upsertMcpServer(entity)
        return entity
    }

    suspend fun toggleMcpServer(serverId: String, enabled: Boolean) {
        mcpAgentDao.setMcpServerEnabled(serverId, enabled, System.currentTimeMillis())
    }

    suspend fun testAndSyncMcpServer(serverId: String): Result<List<McpToolDefinition>> {
        val server = mcpAgentDao.getMcpServerById(serverId)
            ?: return Result.failure(IllegalArgumentException("MCP server not found"))
        val result = mcpClient.discoverTools(server)
        result.fold(
            onSuccess = { tools ->
                mcpAgentDao.upsertMcpServer(
                    server.copy(
                        cachedToolsJson = McpToolDefinition.encodeList(tools),
                        lastStatus = "Connected • ${tools.size} tools",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            },
            onFailure = { err ->
                mcpAgentDao.upsertMcpServer(
                    server.copy(
                        lastStatus = "Error: ${err.localizedMessage ?: "Unreachable"}",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        )
        return result
    }

    suspend fun deleteMcpServer(serverId: String) {
        mcpAgentDao.deleteMcpServer(serverId)
    }

    suspend fun createCustomAgent(
        name: String,
        description: String,
        systemPrompt: String,
        maxSteps: Int,
        enableBuiltInTools: Boolean,
        enableMcpServers: Boolean
    ): AgentProfileEntity {
        val agent = AgentProfileEntity(
            id = "agent_custom_${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifEmpty { "Custom Agent" },
            description = description.trim().ifEmpty { "Autonomous assistant" },
            systemPrompt = systemPrompt.trim(),
            iconKey = "custom",
            maxSteps = maxSteps.coerceIn(1, 8),
            enableBuiltInTools = enableBuiltInTools,
            enableMcpServers = enableMcpServers,
            isBuiltIn = false,
            createdAt = System.currentTimeMillis()
        )
        mcpAgentDao.upsertAgent(agent)
        return agent
    }

    suspend fun deleteCustomAgent(agentId: String) {
        mcpAgentDao.deleteCustomAgent(agentId)
    }

    suspend fun deleteAgentNote(noteId: Long) {
        mcpAgentDao.deleteNote(noteId)
    }

    fun observeDownloadedModels(): Flow<List<DownloadedModelEntity>> =
        downloadedModelDao.observeDownloadedModels()

    suspend fun getDownloadedModel(id: String): DownloadedModelEntity? =
        downloadedModelDao.getById(id)

    suspend fun saveDownloadedModel(model: DownloadedModelEntity) {
        downloadedModelDao.upsert(model)
    }

    suspend fun deleteDownloadedModel(model: DownloadedModelEntity) {
        onDeviceEngine.unloadModel()
        huggingFaceClient.deleteLocalFile(model.localPath)
        downloadedModelDao.deleteById(model.id)
    }

    fun observeGeneratedImages(): Flow<List<GeneratedImageEntity>> =
        generatedImageDao.observeImages()

    suspend fun saveGeneratedImage(entity: GeneratedImageEntity): Long =
        generatedImageDao.insert(entity)

    suspend fun deleteGeneratedImage(entity: GeneratedImageEntity) {
        localImageGenerator.deleteFile(entity.localPath)
        generatedImageDao.deleteById(entity.id)
    }

    suspend fun importAttachment(uri: Uri, forceImage: Boolean): Result<MessageAttachment> =
        attachmentProcessor.importFromUri(uri, forceImage)

    suspend fun getMessagesSnapshot(conversationId: Long): List<MessageEntity> =
        chatDao.getMessagesSnapshot(conversationId)

    suspend fun getConversation(conversationId: Long): ConversationEntity? =
        chatDao.getConversationById(conversationId)

    suspend fun createConversation(
        firstUserMessage: String,
        modelId: String,
        systemPrompt: String
    ): Long {
        val now = System.currentTimeMillis()
        val title = generateConversationTitle(firstUserMessage)
        return chatDao.insertConversation(
            ConversationEntity(
                title = title,
                modelId = modelId,
                systemPrompt = systemPrompt,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun insertMessage(
        conversationId: Long,
        role: String,
        content: String,
        thinkingContent: String? = null,
        modelName: String? = null,
        isError: Boolean = false,
        attachments: List<MessageAttachment> = emptyList()
    ): Long {
        val now = System.currentTimeMillis()
        val id = chatDao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                thinkingContent = thinkingContent,
                modelName = modelName,
                timestamp = now,
                isError = isError,
                attachmentsJson = MessageAttachment.encodeList(attachments)
            )
        )
        chatDao.touchConversation(conversationId, now, modelName.orEmpty())
        return id
    }

    suspend fun updateMessage(
        messageId: Long,
        content: String,
        thinkingContent: String?,
        modelName: String?,
        isError: Boolean = false,
        tokensPerSecond: Float? = null
    ) {
        chatDao.updateMessageContent(
            messageId = messageId,
            content = content,
            thinkingContent = thinkingContent,
            modelName = modelName,
            isError = isError,
            tokensPerSecond = tokensPerSecond
        )
    }

    suspend fun renameConversation(conversationId: Long, newTitle: String) {
        val cleaned = newTitle.trim().take(80)
        if (cleaned.isNotEmpty()) {
            chatDao.renameConversation(conversationId, cleaned, System.currentTimeMillis())
        }
    }

    suspend fun togglePin(conversationId: Long, currentlyPinned: Boolean) {
        chatDao.setPinned(conversationId, !currentlyPinned)
    }

    suspend fun deleteMessage(messageId: Long) {
        chatDao.deleteMessageById(messageId)
    }

    suspend fun deleteConversation(conversationId: Long) {
        chatDao.deleteConversationById(conversationId)
    }

    suspend fun clearAllHistory() {
        chatDao.deleteAllConversations()
    }

    suspend fun probeProvider(
        provider: ApiProvider,
        baseUrl: String,
        apiKey: String
    ): Result<LmStudioHealth> = llmClient.fetchModels(provider, baseUrl, apiKey)

    fun normalizeUrl(provider: ApiProvider, baseUrl: String): String =
        llmClient.normalizeBaseUrl(provider, baseUrl)

    fun streamCompletion(
        settings: AppSettings,
        historyMessages: List<MessageEntity>,
        effectiveModel: String,
        localModel: DownloadedModelEntity?
    ): Flow<StreamEvent> = flow {
        val conversationDtos = historyMessages
            .filter { !it.isError && (it.content.isNotBlank() || !it.attachmentsJson.isNullOrBlank()) }
            .takeLast(settings.contextMessageLimit)
            .map { msg ->
                ChatMessageDto(
                    role = msg.role,
                    content = msg.content,
                    attachments = MessageAttachment.decodeList(msg.attachmentsJson)
                )
            }

        val draftModelPath = if (
            settings.activeProvider.protocol == ApiProtocol.ON_DEVICE_MEDIAPIPE &&
            localModel != null &&
            settings.speculativeDraftModelId.isNotBlank() &&
            settings.speculativeDraftModelId != localModel.id
        ) {
            downloadedModelDao.getById(settings.speculativeDraftModelId)?.localPath
        } else {
            null
        }

        val stepRunner: (List<ChatMessageDto>) -> Flow<StreamEvent> = { stepMessages ->
            if (settings.activeProvider.protocol == ApiProtocol.ON_DEVICE_MEDIAPIPE) {
                if (localModel == null) {
                    flow {
                        throw IllegalStateException(
                            "No on-device model selected. Open Hugging Face Models Hub to download a GGUF (llama.cpp), .litertlm, or .task model."
                        )
                    }
                } else {
                    onDeviceEngine.streamCompletion(
                        model = localModel,
                        messages = stepMessages,
                        preferredEngine = settings.localEngine,
                        hardwareBackend = settings.localBackend,
                        threads = settings.localThreads,
                        temperature = settings.temperature,
                        maxTokens = settings.maxTokens,
                        gpuLayers = settings.localGpuLayers,
                        enableVulkanGpu = settings.enableVulkanGpu,
                        enableSpeculativeDecoding = settings.enableSpeculativeDecoding,
                        speculativeDraftTokens = settings.speculativeDraftTokens,
                        speculativeDraftModelPath = draftModelPath
                    )
                }
            } else {
                llmClient.streamChatCompletion(
                    provider = settings.activeProvider,
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = effectiveModel,
                    messages = stepMessages,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens
                )
            }
        }

        val toolContext = agentOrchestrator.resolveToolContext(
            activeAgentId = settings.activeAgentId,
            globalBuiltInToolsEnabled = settings.enableBuiltInTools
        )

        if (toolContext.agent != null || toolContext.tools.isNotEmpty()) {
            emitAll(
                agentOrchestrator.runAgentStream(
                    toolContext = toolContext,
                    baseSystemPrompt = settings.systemPrompt,
                    conversationMessages = conversationDtos,
                    streamStep = stepRunner
                )
            )
        } else {
            val directMessages = buildList {
                if (settings.systemPrompt.isNotBlank()) {
                    add(ChatMessageDto(role = "system", content = settings.systemPrompt.trim()))
                }
                addAll(conversationDtos)
            }
            emitAll(stepRunner(directMessages))
        }
    }

    fun streamDirectCompletion(
        settings: AppSettings,
        messages: List<ChatMessageDto>,
        effectiveModel: String,
        localModel: DownloadedModelEntity?
    ): Flow<StreamEvent> {
        if (settings.activeProvider.protocol == ApiProtocol.ON_DEVICE_MEDIAPIPE) {
            if (localModel == null) {
                return flow {
                    throw IllegalStateException(
                        "No on-device model selected. Open Hugging Face Models Hub to download a local model or switch to a Free Cloud model."
                    )
                }
            }
            return onDeviceEngine.streamCompletion(
                model = localModel,
                messages = messages,
                preferredEngine = settings.localEngine,
                hardwareBackend = settings.localBackend,
                threads = settings.localThreads,
                temperature = settings.temperature.coerceAtMost(0.45f),
                maxTokens = settings.maxTokens,
                gpuLayers = settings.localGpuLayers,
                enableVulkanGpu = settings.enableVulkanGpu,
                enableSpeculativeDecoding = settings.enableSpeculativeDecoding,
                speculativeDraftTokens = settings.speculativeDraftTokens
            )
        }

        return llmClient.streamChatCompletion(
            provider = settings.activeProvider,
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = effectiveModel,
            messages = messages,
            temperature = settings.temperature.coerceAtMost(0.45f),
            maxTokens = settings.maxTokens
        )
    }

    suspend fun searchHuggingFace(
        query: String,
        hfToken: String,
        authorFilter: String?
    ): Result<List<HfRepoSummary>> =
        huggingFaceClient.searchRepositories(query, hfToken, authorFilter)

    suspend fun fetchHuggingFaceFiles(
        repoId: String,
        hfToken: String
    ): Result<List<HfModelFile>> =
        huggingFaceClient.fetchModelFiles(repoId, hfToken)

    fun downloadHuggingFaceFile(
        modelFile: HfModelFile,
        hfToken: String
    ): Flow<ModelDownloadProgress> =
        huggingFaceClient.downloadModelFile(modelFile, hfToken)

    suspend fun importLocalModel(
        contentResolver: ContentResolver,
        uri: Uri
    ): Result<DownloadedModelEntity> {
        return huggingFaceClient.importLocalModelUri(contentResolver, uri).map { (fileName, file) ->
            val ext = fileName.substringAfterLast('.', "gguf").lowercase()
            val entity = DownloadedModelEntity(
                id = "local/${file.name}",
                repoId = "Local Import",
                fileName = fileName,
                displayName = fileName.substringBeforeLast('.'),
                localPath = file.absolutePath,
                sizeBytes = file.length(),
                format = ext,
                downloadedAt = System.currentTimeMillis()
            )
            downloadedModelDao.upsert(entity)
            entity
        }
    }

    suspend fun exportConversationAsMarkdown(conversationId: Long): String? {
        val convo = chatDao.getConversationById(conversationId) ?: return null
        val messages = chatDao.getMessagesSnapshot(conversationId)
        return buildString {
            appendLine("# ${convo.title}")
            if (convo.modelId.isNotBlank()) {
                appendLine("_Model: `${convo.modelId}`_")
            }
            appendLine()
            messages.forEach { msg ->
                val speaker = if (msg.role == "user") "## You" else "## Assistant"
                appendLine(speaker)
                appendLine()
                val attachments = MessageAttachment.decodeList(msg.attachmentsJson)
                if (attachments.isNotEmpty()) {
                    attachments.forEach { att ->
                        appendLine("> Attachment: `${att.displayName}` (${att.mimeType})")
                    }
                    appendLine()
                }
                if (!msg.thinkingContent.isNullOrBlank()) {
                    appendLine("<details><summary>Thought process</summary>")
                    appendLine()
                    appendLine(msg.thinkingContent)
                    appendLine()
                    appendLine("</details>")
                    appendLine()
                }
                appendLine(msg.content)
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    fun releaseOnDeviceEngine() {
        onDeviceEngine.unloadModel()
        localImageGenerator.release()
    }

    private fun generateConversationTitle(prompt: String): String {
        val singleLine = prompt.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= 44) {
            singleLine.ifEmpty { "New Conversation" }
        } else {
            singleLine.take(44).substringBeforeLast(' ', singleLine.take(44)) + "…"
        }
    }
}
