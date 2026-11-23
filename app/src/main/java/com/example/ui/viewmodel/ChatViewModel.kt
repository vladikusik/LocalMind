package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.attachments.AttachmentProcessor
import com.example.data.attachments.MessageAttachment
import com.example.data.huggingface.HfModelFile
import com.example.data.huggingface.HfRepoSummary
import com.example.data.huggingface.HuggingFaceClient
import com.example.data.huggingface.ModelDownloadProgress
import com.example.data.imagegen.GeneratedImageEntity
import com.example.data.imagegen.ImageGenBackendMode
import com.example.data.imagegen.ImageGenRequest
import com.example.data.imagegen.ImageStudioStateHolder
import com.example.data.imagegen.ImageStudioUiState
import com.example.data.imagegen.LocalImageGenerator
import com.example.data.local.AppDatabase
import com.example.data.local.ConversationSummary
import com.example.data.local.DownloadedModelEntity
import com.example.data.local.MessageEntity
import com.example.data.local.StorageStats
import com.example.data.localmodel.HardwareAcceleratorInfo
import com.example.data.localmodel.HardwareAcceleratorInspector
import com.example.data.localmodel.LocalHardwareBackend
import com.example.data.localmodel.LocalInferenceEngine
import com.example.data.localmodel.OnDeviceLlmEngine
import com.example.data.mcp.AgentNoteEntity
import com.example.data.mcp.AgentOrchestrator
import com.example.data.mcp.AgentProfileEntity
import com.example.data.mcp.BuiltInMcpTools
import com.example.data.mcp.McpClient
import com.example.data.mcp.McpServerEntity
import com.example.data.mcp.McpToolDefinition
import com.example.data.network.ApiProtocol
import com.example.data.network.ApiProvider
import com.example.data.network.ChatMessageDto
import com.example.data.network.StreamEvent
import com.example.data.network.UniversalLlmClient
import com.example.data.preferences.AppLanguage
import com.example.data.preferences.AppSettings
import com.example.data.preferences.SettingsRepository
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.VoiceLocale
import com.example.data.repository.ChatRepository
import com.example.data.sandbox.PythonMiniInterpreter
import com.example.data.sandbox.SandboxLanguage
import com.example.data.sandbox.SandboxLogLevel
import com.example.data.sandbox.SandboxStateHolder
import com.example.data.sandbox.SandboxUiState
import com.example.data.sandbox.SqlSandboxRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface ConnectionUiState {
    data object Checking : ConnectionUiState
    data class Connected(
        val models: List<String>,
        val latencyMs: Long
    ) : ConnectionUiState

    data class Offline(
        val reason: String
    ) : ConnectionUiState
}

data class StreamingMessageOverlay(
    val messageId: Long,
    val content: String,
    val thinkingContent: String?,
    val isThinkingPhase: Boolean,
    val modelName: String?
)

data class ActiveDownloadState(
    val fileKey: String,
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class ModelHubUiState(
    val searchQuery: String = "",
    val selectedPreset: String = "gguf",
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val repositories: List<HfRepoSummary> = emptyList(),
    val expandedRepoId: String? = null,
    val loadingFilesRepoId: String? = null,
    val repoFiles: Map<String, List<HfModelFile>> = emptyMap(),
    val repoFileErrors: Map<String, String> = emptyMap(),
    val activeDownloads: Map<String, ActiveDownloadState> = emptyMap(),
    val statusMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val attachmentProcessor = AttachmentProcessor(application)
    private val llmClient = UniversalLlmClient(attachmentProcessor)
    private val onDeviceEngine = OnDeviceLlmEngine(application)
    private val localImageGenerator = LocalImageGenerator(application)
    private val huggingFaceClient = HuggingFaceClient(application)
    private val mcpClient = McpClient()
    private val sandboxHolder = SandboxStateHolder()
    private val imageStudioHolder = ImageStudioStateHolder()
    val hardwareInfo: HardwareAcceleratorInfo = HardwareAcceleratorInspector.inspect(application)

    private val builtInMcpTools = BuiltInMcpTools(
        dao = database.mcpAgentDao(),
        sandboxHolder = sandboxHolder,
        onGenerateLocalImage = { prompt, negative ->
            generateImageFromAgentTool(prompt, negative)
        }
    )
    private val agentOrchestrator = AgentOrchestrator(
        mcpAgentDao = database.mcpAgentDao(),
        mcpClient = mcpClient,
        builtInTools = builtInMcpTools
    )
    private val chatRepository = ChatRepository(
        chatDao = database.chatDao(),
        downloadedModelDao = database.downloadedModelDao(),
        mcpAgentDao = database.mcpAgentDao(),
        generatedImageDao = database.generatedImageDao(),
        llmClient = llmClient,
        onDeviceEngine = onDeviceEngine,
        localImageGenerator = localImageGenerator,
        huggingFaceClient = huggingFaceClient,
        attachmentProcessor = attachmentProcessor,
        mcpClient = mcpClient,
        builtInMcpTools = builtInMcpTools,
        agentOrchestrator = agentOrchestrator
    )
    private val settingsRepository = SettingsRepository(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val conversations: StateFlow<List<ConversationSummary>> = _searchQuery
        .flatMapLatest { query -> chatRepository.observeConversations(query) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val downloadedModels: StateFlow<List<DownloadedModelEntity>> = chatRepository
        .observeDownloadedModels()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _activeConversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val storageStats: StateFlow<StorageStats> = chatRepository.observeStorageStats()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StorageStats(0, 0)
        )

    private val _connectionState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Checking)
    val connectionState: StateFlow<ConnectionUiState> = _connectionState.asStateFlow()

    private val _streamingOverlay = MutableStateFlow<StreamingMessageOverlay?>(null)
    val streamingOverlay: StateFlow<StreamingMessageOverlay?> = _streamingOverlay.asStateFlow()

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<MessageAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<MessageAttachment>> = _pendingAttachments.asStateFlow()

    private val _uiNotice = MutableStateFlow<String?>(null)
    val uiNotice: StateFlow<String?> = _uiNotice.asStateFlow()

    private val _modelHubState = MutableStateFlow(ModelHubUiState())
    val modelHubState: StateFlow<ModelHubUiState> = _modelHubState.asStateFlow()

    val mcpServers: StateFlow<List<McpServerEntity>> = chatRepository.observeMcpServers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val agents: StateFlow<List<AgentProfileEntity>> = chatRepository.observeAgents()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val agentNotes: StateFlow<List<AgentNoteEntity>> = chatRepository.observeAgentNotes()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val builtInTools: List<McpToolDefinition> = chatRepository.getBuiltInToolDefinitions()

    val sandboxState: StateFlow<SandboxUiState> = sandboxHolder.state
    val imageStudioState: StateFlow<ImageStudioUiState> = imageStudioHolder.state

    val generatedImages: StateFlow<List<GeneratedImageEntity>> = chatRepository.observeGeneratedImages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _mcpBusyServerId = MutableStateFlow<String?>(null)
    val mcpBusyServerId: StateFlow<String?> = _mcpBusyServerId.asStateFlow()

    private var generationJob: Job? = null
    private var sandboxAiJob: Job? = null
    private var imageGenJob: Job? = null
    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            chatRepository.ensureDefaultAgentsSeeded()
            val initial = settingsRepository.settingsFlow.first()
            refreshConnection(
                providerOverride = initial.activeProvider,
                baseUrlOverride = initial.baseUrl,
                apiKeyOverride = initial.apiKey
            )
        }
        searchHuggingFacePreset("gguf")
    }

    override fun onCleared() {
        super.onCleared()
        downloadJobs.values.forEach { it.cancel() }
        chatRepository.releaseOnDeviceEngine()
    }

    fun updateComposerText(text: String) {
        _composerText.value = text
    }

    fun appendVoiceTranscript(transcript: String) {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) return
        _composerText.update { current ->
            if (current.isBlank()) cleaned else "${current.trimEnd()} $cleaned"
        }
    }

    fun addAttachmentUri(uri: Uri, forceImage: Boolean) {
        viewModelScope.launch {
            val result = chatRepository.importAttachment(uri, forceImage)
            result.fold(
                onSuccess = { attachment ->
                    _pendingAttachments.update { list -> (list + attachment).take(6) }
                },
                onFailure = { err ->
                    _uiNotice.value = err.localizedMessage ?: "Failed to attach file."
                }
            )
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        _pendingAttachments.update { list -> list.filterNot { it.id == attachmentId } }
    }

    fun clearUiNotice() {
        _uiNotice.value = null
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectConversation(conversationId: Long) {
        if (_activeConversationId.value != conversationId) {
            stopGeneration()
            _activeConversationId.value = conversationId
        }
    }

    fun startNewChat() {
        stopGeneration()
        _activeConversationId.value = null
        _pendingAttachments.value = emptyList()
    }

    fun selectProvider(provider: ApiProvider) {
        viewModelScope.launch {
            settingsRepository.updateActiveProvider(provider)
            val updated = settingsRepository.settingsFlow.first()
            refreshConnection(
                providerOverride = provider,
                baseUrlOverride = updated.baseUrl,
                apiKeyOverride = updated.apiKey
            )
        }
    }

    fun refreshConnection(
        providerOverride: ApiProvider? = null,
        baseUrlOverride: String? = null,
        apiKeyOverride: String? = null
    ) {
        viewModelScope.launch {
            val current = settings.value
            val targetProvider = providerOverride ?: current.activeProvider
            val cfg = current.providerConfigs[targetProvider]
            val targetUrl = baseUrlOverride ?: cfg?.baseUrl ?: targetProvider.defaultBaseUrl
            val targetKey = apiKeyOverride ?: cfg?.apiKey ?: ""

            if (targetProvider.protocol == ApiProtocol.ON_DEVICE_MEDIAPIPE) {
                val validLocal = downloadedModels.value.filter { File(it.localPath).exists() }
                _connectionState.value = if (validLocal.isNotEmpty()) {
                    ConnectionUiState.Connected(
                        models = validLocal.map { it.displayName },
                        latencyMs = 0L
                    )
                } else {
                    ConnectionUiState.Offline("No local GGUF / LiteRT / Task model downloaded yet.")
                }
                return@launch
            }

            if (targetProvider.requiresApiKey && targetKey.isBlank()) {
                _connectionState.value = ConnectionUiState.Offline(
                    "Enter your ${targetProvider.displayName} API key in Settings."
                )
                return@launch
            }

            _connectionState.value = ConnectionUiState.Checking
            val result = chatRepository.probeProvider(targetProvider, targetUrl, targetKey)
            result.fold(
                onSuccess = { health ->
                    _connectionState.value = ConnectionUiState.Connected(
                        models = health.models,
                        latencyMs = health.latencyMs
                    )
                    val currentModel = cfg?.selectedModel.orEmpty()
                    if (currentModel.isBlank() && health.models.isNotEmpty()) {
                        settingsRepository.updateProviderModel(targetProvider, health.models.first())
                    }
                },
                onFailure = { error ->
                    _connectionState.value = ConnectionUiState.Offline(
                        reason = error.localizedMessage ?: "${targetProvider.displayName} unreachable."
                    )
                }
            )
        }
    }

    fun sendMessage(overrideText: String? = null) {
        val text = (overrideText ?: _composerText.value).trim()
        val attachmentsToSend = if (overrideText == null) _pendingAttachments.value else emptyList()
        if ((text.isEmpty() && attachmentsToSend.isEmpty()) || _streamingOverlay.value != null) return

        if (overrideText == null) {
            _composerText.value = ""
            _pendingAttachments.value = emptyList()
        }

        val fallbackTitleSeed = text.ifBlank {
            attachmentsToSend.firstOrNull()?.displayName ?: "Attachment"
        }

        viewModelScope.launch {
            val currentSettings = settings.value
            val localModel = resolveActiveLocalModel(currentSettings)
            val effectiveModel = resolveEffectiveModel(currentSettings, localModel)
            val conversationId = _activeConversationId.value ?: chatRepository.createConversation(
                firstUserMessage = fallbackTitleSeed,
                modelId = effectiveModel,
                systemPrompt = currentSettings.systemPrompt
            ).also { newId ->
                _activeConversationId.value = newId
            }

            chatRepository.insertMessage(
                conversationId = conversationId,
                role = "user",
                content = text,
                modelName = effectiveModel,
                attachments = attachmentsToSend
            )

            launchAssistantGeneration(conversationId, currentSettings, effectiveModel, localModel)
        }
    }

    fun regenerateLastResponse() {
        val conversationId = _activeConversationId.value ?: return
        if (_streamingOverlay.value != null) return

        viewModelScope.launch {
            val history = chatRepository.getMessagesSnapshot(conversationId)
            val lastMessage = history.lastOrNull() ?: return@launch
            if (lastMessage.role == "assistant") {
                chatRepository.deleteMessage(lastMessage.id)
            }
            val currentSettings = settings.value
            val localModel = resolveActiveLocalModel(currentSettings)
            val effectiveModel = resolveEffectiveModel(currentSettings, localModel)
            launchAssistantGeneration(conversationId, currentSettings, effectiveModel, localModel)
        }
    }

    fun retryErrorMessage(errorMessageId: Long) {
        val conversationId = _activeConversationId.value ?: return
        if (_streamingOverlay.value != null) return

        viewModelScope.launch {
            chatRepository.deleteMessage(errorMessageId)
            val currentSettings = settings.value
            val localModel = resolveActiveLocalModel(currentSettings)
            val effectiveModel = resolveEffectiveModel(currentSettings, localModel)
            launchAssistantGeneration(conversationId, currentSettings, effectiveModel, localModel)
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    private fun launchAssistantGeneration(
        conversationId: Long,
        currentSettings: AppSettings,
        effectiveModel: String,
        localModel: DownloadedModelEntity?
    ) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            val history = chatRepository.getMessagesSnapshot(conversationId)
            val assistantMsgId = chatRepository.insertMessage(
                conversationId = conversationId,
                role = "assistant",
                content = "",
                modelName = effectiveModel
            )

            val rawContentBuffer = StringBuilder()
            val explicitReasoningBuffer = StringBuilder()
            var resolvedModelName: String? = effectiveModel.takeIf { it.isNotBlank() }
            var finalTps: Float? = null
            var lastDbFlushMs = 0L

            _streamingOverlay.value = StreamingMessageOverlay(
                messageId = assistantMsgId,
                content = "",
                thinkingContent = null,
                isThinkingPhase = false,
                modelName = resolvedModelName
            )

            try {
                chatRepository.streamCompletion(
                    settings = currentSettings,
                    historyMessages = history,
                    effectiveModel = effectiveModel,
                    localModel = localModel
                )
                    .catch { throwable -> throw throwable }
                    .collect { event ->
                        when (event) {
                            is StreamEvent.Chunk -> {
                                if (!event.modelName.isNullOrBlank()) {
                                    resolvedModelName = event.modelName
                                }
                                if (event.reasoningDelta.isNotEmpty()) {
                                    explicitReasoningBuffer.append(event.reasoningDelta)
                                }
                                if (event.contentDelta.isNotEmpty()) {
                                    rawContentBuffer.append(event.contentDelta)
                                }

                                val split = extractThinkingAndAnswer(
                                    rawContent = rawContentBuffer.toString(),
                                    explicitReasoning = explicitReasoningBuffer.toString()
                                )

                                _streamingOverlay.value = StreamingMessageOverlay(
                                    messageId = assistantMsgId,
                                    content = split.answer,
                                    thinkingContent = split.thinking,
                                    isThinkingPhase = split.isStillThinking,
                                    modelName = resolvedModelName
                                )

                                val now = System.currentTimeMillis()
                                if (now - lastDbFlushMs >= 450L) {
                                    lastDbFlushMs = now
                                    chatRepository.updateMessage(
                                        messageId = assistantMsgId,
                                        content = split.answer,
                                        thinkingContent = split.thinking,
                                        modelName = resolvedModelName,
                                        isError = false,
                                        tokensPerSecond = null
                                    )
                                }
                            }

                            is StreamEvent.Completed -> {
                                finalTps = event.tokensPerSecond
                                if (!event.modelName.isNullOrBlank()) {
                                    resolvedModelName = event.modelName
                                }
                            }
                        }
                    }

                val finalSplit = extractThinkingAndAnswer(
                    rawContent = rawContentBuffer.toString(),
                    explicitReasoning = explicitReasoningBuffer.toString()
                )
                val finalAnswer = finalSplit.answer.ifBlank {
                    if (finalSplit.thinking.isNullOrBlank()) "(Empty response from model)" else ""
                }
                chatRepository.updateMessage(
                    messageId = assistantMsgId,
                    content = finalAnswer,
                    thinkingContent = finalSplit.thinking,
                    modelName = resolvedModelName,
                    isError = false,
                    tokensPerSecond = finalTps
                )
            } catch (_: CancellationException) {
                val partialSplit = extractThinkingAndAnswer(
                    rawContent = rawContentBuffer.toString(),
                    explicitReasoning = explicitReasoningBuffer.toString()
                )
                if (partialSplit.answer.isBlank() && partialSplit.thinking.isNullOrBlank()) {
                    chatRepository.deleteMessage(assistantMsgId)
                } else {
                    chatRepository.updateMessage(
                        messageId = assistantMsgId,
                        content = partialSplit.answer,
                        thinkingContent = partialSplit.thinking,
                        modelName = resolvedModelName,
                        isError = false,
                        tokensPerSecond = null
                    )
                }
            } catch (e: Exception) {
                val provider = currentSettings.activeProvider
                val errorText = buildString {
                    append(e.localizedMessage ?: "Failed to generate response.")
                    if (provider.protocol != ApiProtocol.ON_DEVICE_MEDIAPIPE) {
                        append("\n\nProvider: **")
                        append(provider.displayName)
                        append("** (`")
                        append(chatRepository.normalizeUrl(provider, currentSettings.baseUrl))
                        append("`)")
                    }
                }
                chatRepository.updateMessage(
                    messageId = assistantMsgId,
                    content = errorText,
                    thinkingContent = null,
                    modelName = resolvedModelName,
                    isError = true,
                    tokensPerSecond = null
                )
            } finally {
                _streamingOverlay.value = null
            }
        }
    }

    private suspend fun resolveActiveLocalModel(currentSettings: AppSettings): DownloadedModelEntity? {
        if (currentSettings.activeProvider.protocol != ApiProtocol.ON_DEVICE_MEDIAPIPE) return null
        if (currentSettings.activeLocalModelId.isNotBlank()) {
            chatRepository.getDownloadedModel(currentSettings.activeLocalModelId)
                ?.takeIf { File(it.localPath).exists() }
                ?.let { return it }
        }
        return downloadedModels.value.firstOrNull { File(it.localPath).exists() }
    }

    private fun resolveEffectiveModel(
        currentSettings: AppSettings,
        localModel: DownloadedModelEntity?
    ): String {
        if (currentSettings.activeProvider.protocol == ApiProtocol.ON_DEVICE_MEDIAPIPE) {
            return localModel?.displayName ?: "On-Device Model"
        }
        if (currentSettings.selectedModel.isNotBlank()) return currentSettings.selectedModel
        val connected = _connectionState.value as? ConnectionUiState.Connected
        return connected?.models?.firstOrNull()
            ?: currentSettings.activeProvider.suggestedModels.firstOrNull()
            ?: ""
    }

    // --- Hugging Face Model Hub ---

    fun updateHfSearchQuery(query: String) {
        _modelHubState.update { it.copy(searchQuery = query) }
    }

    fun searchHuggingFacePreset(preset: String) {
        val (author, query) = when (preset) {
            "gguf" -> null to "Qwen2.5-1.5B-Instruct-GGUF"
            "litert" -> "litert-community" to ""
            "diffusion" -> null to "stable-diffusion-v1-5 safetensors"
            "gemma" -> null to "gemma-3 GGUF"
            "qwen" -> null to "Qwen2.5 GGUF"
            "smollm" -> null to "SmolLM2 GGUF"
            "llama" -> null to "Llama-3.2 GGUF"
            else -> null to _modelHubState.value.searchQuery
        }
        _modelHubState.update { it.copy(selectedPreset = preset, searchQuery = query) }
        executeHfSearch(query = query, authorFilter = author)
    }

    fun submitHfSearch() {
        val q = _modelHubState.value.searchQuery.trim()
        _modelHubState.update { it.copy(selectedPreset = "custom") }
        executeHfSearch(query = q, authorFilter = null)
    }

    private fun executeHfSearch(query: String, authorFilter: String?) {
        viewModelScope.launch {
            _modelHubState.update { it.copy(isSearching = true, searchError = null) }
            val result = chatRepository.searchHuggingFace(
                query = query,
                hfToken = settings.value.huggingFaceToken,
                authorFilter = authorFilter
            )
            result.fold(
                onSuccess = { repos ->
                    _modelHubState.update {
                        it.copy(isSearching = false, repositories = repos, searchError = null)
                    }
                },
                onFailure = { err ->
                    _modelHubState.update {
                        it.copy(
                            isSearching = false,
                            searchError = err.localizedMessage ?: "Failed to query Hugging Face Hub."
                        )
                    }
                }
            )
        }
    }

    fun toggleInspectRepo(repoId: String) {
        val currentExpanded = _modelHubState.value.expandedRepoId
        if (currentExpanded == repoId) {
            _modelHubState.update { it.copy(expandedRepoId = null) }
            return
        }
        _modelHubState.update { it.copy(expandedRepoId = repoId) }
        if (_modelHubState.value.repoFiles.containsKey(repoId)) return

        viewModelScope.launch {
            _modelHubState.update { it.copy(loadingFilesRepoId = repoId) }
            val result = chatRepository.fetchHuggingFaceFiles(repoId, settings.value.huggingFaceToken)
            result.fold(
                onSuccess = { files ->
                    _modelHubState.update {
                        it.copy(
                            loadingFilesRepoId = if (it.loadingFilesRepoId == repoId) null else it.loadingFilesRepoId,
                            repoFiles = it.repoFiles + (repoId to files),
                            repoFileErrors = it.repoFileErrors - repoId
                        )
                    }
                },
                onFailure = { err ->
                    _modelHubState.update {
                        it.copy(
                            loadingFilesRepoId = if (it.loadingFilesRepoId == repoId) null else it.loadingFilesRepoId,
                            repoFileErrors = it.repoFileErrors + (repoId to (err.localizedMessage ?: "Failed to list files"))
                        )
                    }
                }
            )
        }
    }

    fun startModelDownload(modelFile: HfModelFile) {
        val fileKey = "${modelFile.repoId}/${modelFile.path}"
        if (downloadJobs.containsKey(fileKey)) return

        val job = viewModelScope.launch {
            _modelHubState.update {
                it.copy(
                    activeDownloads = it.activeDownloads + (
                        fileKey to ActiveDownloadState(
                            fileKey = fileKey,
                            fileName = modelFile.fileName,
                            bytesDownloaded = 0L,
                            totalBytes = modelFile.sizeBytes,
                            speedBytesPerSec = 0L
                        )
                    ),
                    statusMessage = null
                )
            }
            try {
                chatRepository.downloadHuggingFaceFile(modelFile, settings.value.huggingFaceToken)
                    .collect { progress ->
                        when (progress) {
                            is ModelDownloadProgress.Downloading -> {
                                _modelHubState.update {
                                    it.copy(
                                        activeDownloads = it.activeDownloads + (
                                            fileKey to ActiveDownloadState(
                                                fileKey = fileKey,
                                                fileName = modelFile.fileName,
                                                bytesDownloaded = progress.bytesDownloaded,
                                                totalBytes = progress.totalBytes,
                                                speedBytesPerSec = progress.speedBytesPerSec
                                            )
                                        )
                                    )
                                }
                            }

                            is ModelDownloadProgress.Completed -> {
                                val entity = DownloadedModelEntity(
                                    id = fileKey,
                                    repoId = modelFile.repoId,
                                    fileName = modelFile.fileName,
                                    displayName = modelFile.fileName.substringBeforeLast('.'),
                                    localPath = progress.file.absolutePath,
                                    sizeBytes = progress.sizeBytes,
                                    format = modelFile.format,
                                    downloadedAt = System.currentTimeMillis()
                                )
                                chatRepository.saveDownloadedModel(entity)
                                val isDiffusionWeight = entity.format.lowercase() in setOf("safetensors", "ckpt")
                                if (isDiffusionWeight) {
                                    settingsRepository.updateActiveImageModelId(entity.id)
                                    _modelHubState.update {
                                        it.copy(statusMessage = "Downloaded diffusion checkpoint ${modelFile.fileName} (ready in Image Studio)")
                                    }
                                } else {
                                    settingsRepository.updateActiveLocalModelId(entity.id)
                                    settingsRepository.updateActiveProvider(ApiProvider.ON_DEVICE)
                                    _connectionState.value = ConnectionUiState.Connected(
                                        models = listOf(entity.displayName),
                                        latencyMs = 0L
                                    )
                                    _modelHubState.update {
                                        it.copy(statusMessage = "Downloaded ${modelFile.fileName}")
                                    }
                                }
                            }
                        }
                    }
            } catch (_: CancellationException) {
                _modelHubState.update { it.copy(statusMessage = "Download cancelled") }
            } catch (e: Exception) {
                _modelHubState.update {
                    it.copy(statusMessage = e.localizedMessage ?: "Download failed")
                }
            } finally {
                downloadJobs.remove(fileKey)
                _modelHubState.update {
                    it.copy(activeDownloads = it.activeDownloads - fileKey)
                }
            }
        }
        downloadJobs[fileKey] = job
    }

    fun cancelModelDownload(fileKey: String) {
        downloadJobs.remove(fileKey)?.cancel()
        _modelHubState.update { it.copy(activeDownloads = it.activeDownloads - fileKey) }
    }

    fun activateDownloadedModel(model: DownloadedModelEntity) {
        viewModelScope.launch {
            if (model.format.lowercase() in setOf("safetensors", "ckpt")) {
                settingsRepository.updateActiveImageModelId(model.id)
                _uiNotice.value = "Selected ${model.displayName} for Image Studio"
                return@launch
            }
            settingsRepository.updateActiveLocalModelId(model.id)
            settingsRepository.updateActiveProvider(ApiProvider.ON_DEVICE)
            _connectionState.value = ConnectionUiState.Connected(
                models = listOf(model.displayName),
                latencyMs = 0L
            )
        }
    }

    fun deleteDownloadedModel(model: DownloadedModelEntity) {
        viewModelScope.launch {
            chatRepository.deleteDownloadedModel(model)
            if (settings.value.activeLocalModelId == model.id) {
                settingsRepository.updateActiveLocalModelId("")
            }
            if (settings.value.activeImageModelId == model.id) {
                settingsRepository.updateActiveImageModelId("")
            }
        }
    }

    fun importLocalModelFile(uri: Uri) {
        viewModelScope.launch {
            val result = chatRepository.importLocalModel(getApplication<Application>().contentResolver, uri)
            result.fold(
                onSuccess = { entity ->
                    if (entity.format.lowercase() in setOf("safetensors", "ckpt")) {
                        settingsRepository.updateActiveImageModelId(entity.id)
                        _modelHubState.update { it.copy(statusMessage = "Imported diffusion model ${entity.fileName}") }
                        _uiNotice.value = "Imported ${entity.fileName} for Image Studio"
                    } else {
                        settingsRepository.updateActiveLocalModelId(entity.id)
                        settingsRepository.updateActiveProvider(ApiProvider.ON_DEVICE)
                        _connectionState.value = ConnectionUiState.Connected(
                            models = listOf(entity.displayName),
                            latencyMs = 0L
                        )
                        _modelHubState.update { it.copy(statusMessage = "Imported ${entity.fileName}") }
                    }
                },
                onFailure = { err ->
                    _modelHubState.update {
                        it.copy(statusMessage = err.localizedMessage ?: "Import failed")
                    }
                }
            )
        }
    }

    fun clearModelHubStatus() {
        _modelHubState.update { it.copy(statusMessage = null) }
    }

    // --- Conversation & Settings ---

    fun renameConversation(conversationId: Long, newTitle: String) {
        viewModelScope.launch { chatRepository.renameConversation(conversationId, newTitle) }
    }

    fun togglePin(conversationId: Long, currentlyPinned: Boolean) {
        viewModelScope.launch { chatRepository.togglePin(conversationId, currentlyPinned) }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                stopGeneration()
                _activeConversationId.value = null
            }
            chatRepository.deleteConversation(conversationId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            stopGeneration()
            _activeConversationId.value = null
            chatRepository.clearAllHistory()
        }
    }

    suspend fun exportConversationMarkdown(conversationId: Long): String? =
        chatRepository.exportConversationAsMarkdown(conversationId)

    fun updateProviderBaseUrl(provider: ApiProvider, url: String) {
        viewModelScope.launch { settingsRepository.updateProviderBaseUrl(provider, url) }
    }

    fun updateProviderApiKey(provider: ApiProvider, key: String) {
        viewModelScope.launch { settingsRepository.updateProviderApiKey(provider, key) }
    }

    fun updateProviderModel(provider: ApiProvider, modelId: String) {
        viewModelScope.launch { settingsRepository.updateProviderModel(provider, modelId) }
    }

    fun updateLocalEngine(engine: LocalInferenceEngine) {
        viewModelScope.launch { settingsRepository.updateLocalEngine(engine) }
    }

    fun updateLocalBackend(backend: LocalHardwareBackend) {
        viewModelScope.launch { settingsRepository.updateLocalBackend(backend) }
    }

    fun updateLocalThreads(threads: Int) {
        viewModelScope.launch { settingsRepository.updateLocalThreads(threads) }
    }

    fun updateHuggingFaceToken(token: String) {
        viewModelScope.launch { settingsRepository.updateHuggingFaceToken(token) }
    }

    fun updateSystemPrompt(prompt: String) {
        viewModelScope.launch { settingsRepository.updateSystemPrompt(prompt) }
    }

    fun updateTemperature(temp: Float) {
        viewModelScope.launch { settingsRepository.updateTemperature(temp) }
    }

    fun updateMaxTokens(maxTokens: Int) {
        viewModelScope.launch { settingsRepository.updateMaxTokens(maxTokens) }
    }

    fun updateContextLimit(limit: Int) {
        viewModelScope.launch { settingsRepository.updateContextLimit(limit) }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.updateThemeMode(mode) }
    }

    fun updateDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateDynamicColor(enabled) }
    }

    fun updateVoiceLocale(locale: VoiceLocale) {
        viewModelScope.launch { settingsRepository.updateVoiceLocale(locale) }
    }

    fun updateAppLanguage(language: AppLanguage, onApplied: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.updateAppLanguage(language)
            onApplied()
        }
    }

    // --- MCP Servers & Autonomous Agents ---

    fun selectActiveAgent(agentId: String) {
        viewModelScope.launch {
            settingsRepository.updateActiveAgentId(agentId)
        }
    }

    fun updateEnableBuiltInTools(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateEnableBuiltInTools(enabled)
        }
    }

    fun createCustomAgent(
        name: String,
        description: String,
        systemPrompt: String,
        maxSteps: Int,
        enableBuiltInTools: Boolean,
        enableMcpServers: Boolean
    ) {
        viewModelScope.launch {
            val created = chatRepository.createCustomAgent(
                name = name,
                description = description,
                systemPrompt = systemPrompt,
                maxSteps = maxSteps,
                enableBuiltInTools = enableBuiltInTools,
                enableMcpServers = enableMcpServers
            )
            settingsRepository.updateActiveAgentId(created.id)
        }
    }

    fun deleteCustomAgent(agentId: String) {
        viewModelScope.launch {
            if (settings.value.activeAgentId == agentId) {
                settingsRepository.updateActiveAgentId("")
            }
            chatRepository.deleteCustomAgent(agentId)
        }
    }

    fun addMcpServer(name: String, endpointUrl: String, authHeader: String) {
        viewModelScope.launch {
            val created = chatRepository.addMcpServer(name, endpointUrl, authHeader)
            syncMcpServer(created.id)
        }
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            chatRepository.toggleMcpServer(serverId, enabled)
        }
    }

    fun syncMcpServer(serverId: String) {
        viewModelScope.launch {
            _mcpBusyServerId.value = serverId
            try {
                val res = chatRepository.testAndSyncMcpServer(serverId)
                res.fold(
                    onSuccess = { tools ->
                        _uiNotice.value = "MCP synced: ${tools.size} tools available"
                    },
                    onFailure = { err ->
                        _uiNotice.value = err.localizedMessage ?: "MCP connection failed"
                    }
                )
            } finally {
                if (_mcpBusyServerId.value == serverId) {
                    _mcpBusyServerId.value = null
                }
            }
        }
    }

    fun deleteMcpServer(serverId: String) {
        viewModelScope.launch {
            chatRepository.deleteMcpServer(serverId)
        }
    }

    fun deleteAgentNote(noteId: Long) {
        viewModelScope.launch {
            chatRepository.deleteAgentNote(noteId)
        }
    }

    // --- Interactive Code Sandbox ---

    fun selectSandboxLanguage(language: SandboxLanguage) {
        sandboxHolder.selectLanguage(language)
        if (!language.usesWebView) {
            runSandboxCode()
        }
    }

    fun updateSandboxCode(code: String) {
        sandboxHolder.updateCode(code)
    }

    fun updateSandboxAiPrompt(prompt: String) {
        sandboxHolder.updateAiPrompt(prompt)
    }

    fun resetSandboxTemplate() {
        val lang = sandboxHolder.state.value.language
        sandboxHolder.loadTemplate(lang)
        if (!lang.usesWebView) {
            runSandboxCode()
        }
    }

    fun clearSandboxLogs() {
        sandboxHolder.clearLogs()
    }

    fun appendSandboxLog(level: SandboxLogLevel, message: String) {
        sandboxHolder.appendLog(level, message)
    }

    fun markSandboxWebDone(durationMs: Long) {
        val current = sandboxHolder.state.value
        val existingPairs = current.consoleLogs.map { it.level to it.text }
        sandboxHolder.setExecutionResult(existingPairs, durationMs)
    }

    fun openCodeInSandbox(languageTag: String, code: String) {
        val lang = SandboxLanguage.fromTag(languageTag)
        sandboxHolder.loadCodeAndRun(lang, code.trim())
        if (!lang.usesWebView) {
            runSandboxCode()
        }
    }

    fun runSandboxCode() {
        val snapshot = sandboxHolder.state.value
        when (snapshot.language) {
            SandboxLanguage.HTML_JS, SandboxLanguage.JAVASCRIPT -> {
                sandboxHolder.markRunTriggered()
            }

            SandboxLanguage.PYTHON -> {
                viewModelScope.launch {
                    val (logs, ms) = PythonMiniInterpreter.run(snapshot.code)
                    sandboxHolder.setExecutionResult(logs, ms)
                }
            }

            SandboxLanguage.SQL -> {
                viewModelScope.launch {
                    val (logs, ms) = SqlSandboxRunner.run(snapshot.code)
                    sandboxHolder.setExecutionResult(logs, ms)
                }
            }
        }
    }

    fun stopSandboxAiGeneration() {
        sandboxAiJob?.cancel()
        sandboxAiJob = null
        sandboxHolder.setAiGenerating(false, null)
    }

    fun generateSandboxCodeWithAi(overrideInstruction: String? = null) {
        val currentSandbox = sandboxHolder.state.value
        val instruction = (overrideInstruction ?: currentSandbox.aiPrompt).trim()
        if (instruction.isEmpty() || currentSandbox.isAiGenerating) return

        if (overrideInstruction == null) {
            sandboxHolder.updateAiPrompt("")
        }

        sandboxAiJob?.cancel()
        sandboxAiJob = viewModelScope.launch {
            val currentSettings = settings.value
            val localModel = resolveActiveLocalModel(currentSettings)
            val effectiveModel = resolveEffectiveModel(currentSettings, localModel)
            val lang = currentSandbox.language

            sandboxHolder.setAiGenerating(true, "AI writing ${lang.displayName}…")

            val sysPrompt = buildString {
                appendLine("You are an expert code generator inside an Android Code Sandbox.")
                appendLine("Target language: ${lang.displayName} (tag: ${lang.id}).")
                appendLine("Output ONLY a single complete, self-contained runnable code block fenced with ```${lang.id} ... ``` and NO extra commentary outside the fence.")
                when (lang) {
                    SandboxLanguage.HTML_JS -> appendLine("Produce a complete single-file HTML5 document with inline <style> and <script>, responsive mobile layout, zero external CDN dependencies (offline-safe), and crisp modern styling.")
                    SandboxLanguage.JAVASCRIPT -> appendLine("Produce clean modern JavaScript that prints clear results using console.log(...).")
                    SandboxLanguage.PYTHON -> appendLine("Produce clean Python using standard built-ins (def, for, while, if/elif/else, lists, dicts, print, range, len, sum, min, max, abs, round, sorted, math) without external pip packages.")
                    SandboxLanguage.SQL -> appendLine("Produce valid SQLite SQL statements (CREATE TABLE, INSERT, SELECT) that run cleanly in an empty in-memory SQLite database.")
                }
            }

            val recentErrors = currentSandbox.consoleLogs
                .filter { it.level == SandboxLogLevel.ERROR }
                .takeLast(5)
                .joinToString("\n") { it.text }

            val userPrompt = buildString {
                appendLine("Task: $instruction")
                if (currentSandbox.code.isNotBlank()) {
                    appendLine()
                    appendLine("Current sandbox code:")
                    appendLine("```${lang.id}")
                    appendLine(currentSandbox.code.take(3500))
                    appendLine("```")
                }
                if (recentErrors.isNotBlank()) {
                    appendLine()
                    appendLine("Recent runtime errors:")
                    appendLine(recentErrors)
                }
            }

            val rawBuffer = StringBuilder()
            try {
                chatRepository.streamDirectCompletion(
                    settings = currentSettings,
                    messages = listOf(
                        ChatMessageDto(role = "system", content = sysPrompt),
                        ChatMessageDto(role = "user", content = userPrompt)
                    ),
                    effectiveModel = effectiveModel,
                    localModel = localModel
                ).collect { event ->
                    if (event is StreamEvent.Chunk && event.contentDelta.isNotEmpty()) {
                        rawBuffer.append(event.contentDelta)
                        val liveExtracted = extractSandboxCodeBlock(rawBuffer.toString())
                        if (liveExtracted.code.isNotBlank()) {
                            sandboxHolder.updateCode(liveExtracted.code)
                        }
                    }
                }

                val finalExtracted = extractSandboxCodeBlock(rawBuffer.toString())
                if (finalExtracted.code.isNotBlank()) {
                    val detectedLang = finalExtracted.languageTag?.let { SandboxLanguage.fromTag(it) } ?: lang
                    sandboxHolder.loadCodeAndRun(
                        language = detectedLang,
                        code = finalExtracted.code,
                        title = instruction.take(36)
                    )
                    if (!detectedLang.usesWebView) {
                        val (logs, ms) = when (detectedLang) {
                            SandboxLanguage.PYTHON -> PythonMiniInterpreter.run(finalExtracted.code)
                            SandboxLanguage.SQL -> SqlSandboxRunner.run(finalExtracted.code)
                            else -> emptyList<Pair<SandboxLogLevel, String>>() to 1L
                        }
                        sandboxHolder.setExecutionResult(logs, ms)
                    }
                    sandboxHolder.setAiGenerating(false, null)
                } else {
                    sandboxHolder.setAiGenerating(false, null)
                    _uiNotice.value = "AI returned empty code."
                }
            } catch (_: CancellationException) {
                sandboxHolder.setAiGenerating(false, null)
            } catch (e: Exception) {
                sandboxHolder.setAiGenerating(false, null)
                sandboxHolder.appendLog(
                    SandboxLogLevel.ERROR,
                    e.localizedMessage ?: "AI code generation failed."
                )
            }
        }
    }

    private data class ExtractedCode(val languageTag: String?, val code: String)

    private fun extractSandboxCodeBlock(raw: String): ExtractedCode {
        val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val fenceRegex = Regex("```([a-zA-Z0-9_+-]*)\\s*\\n([\\s\\S]*?)(?:```|$)")
        val match = fenceRegex.find(cleaned)
        return if (match != null) {
            val tag = match.groupValues[1].trim().ifBlank { null }
            val body = match.groupValues[2].trimEnd()
            ExtractedCode(tag, body)
        } else {
            ExtractedCode(null, cleaned.removePrefix("```").removeSuffix("```").trim())
        }
    }

    private data class SplitThinkingResult(
        val thinking: String?,
        val answer: String,
        val isStillThinking: Boolean
    )

    private fun extractThinkingAndAnswer(
        rawContent: String,
        explicitReasoning: String
    ): SplitThinkingResult {
        val openTag = "<think>"
        val closeTag = "</think>"

        if (explicitReasoning.isNotBlank()) {
            val cleanedAnswer = rawContent.trimStart()
            return SplitThinkingResult(
                thinking = explicitReasoning.trim(),
                answer = cleanedAnswer,
                isStillThinking = cleanedAnswer.isEmpty()
            )
        }

        val startIdx = rawContent.indexOf(openTag)
        if (startIdx == -1) {
            return SplitThinkingResult(null, rawContent, false)
        }

        val endIdx = rawContent.indexOf(closeTag, startIndex = startIdx + openTag.length)
        return if (endIdx == -1) {
            val thinkingSoFar = rawContent.substring(startIdx + openTag.length).trim()
            val prefix = rawContent.substring(0, startIdx).trim()
            SplitThinkingResult(thinkingSoFar, prefix, true)
        } else {
            val thinkingText = rawContent.substring(startIdx + openTag.length, endIdx).trim()
            val before = rawContent.substring(0, startIdx)
            val after = rawContent.substring(endIdx + closeTag.length)
            SplitThinkingResult(thinkingText.ifBlank { null }, (before + after).trimStart(), false)
        }
    }

    // --- Local Uncensored Image Generation Studio ---

    fun updateImageGenMode(mode: ImageGenBackendMode) {
        viewModelScope.launch { settingsRepository.updateImageGenMode(mode) }
    }

    fun updateImageServerUrl(url: String) {
        viewModelScope.launch { settingsRepository.updateImageServerUrl(url) }
    }

    fun updateImageServerApiKey(key: String) {
        viewModelScope.launch { settingsRepository.updateImageServerApiKey(key) }
    }

    fun updateImageServerModel(model: String) {
        viewModelScope.launch { settingsRepository.updateImageServerModel(model) }
    }

    fun selectActiveImageModel(modelId: String) {
        viewModelScope.launch { settingsRepository.updateActiveImageModelId(modelId) }
    }

    fun updateImageSize(width: Int, height: Int) {
        viewModelScope.launch { settingsRepository.updateImageSize(width, height) }
    }

    fun updateImageSteps(steps: Int) {
        viewModelScope.launch { settingsRepository.updateImageSteps(steps) }
    }

    fun updateImageCfgScale(cfg: Float) {
        viewModelScope.launch { settingsRepository.updateImageCfgScale(cfg) }
    }

    fun updateImagePrompt(prompt: String) {
        imageStudioHolder.update { it.copy(prompt = prompt) }
    }

    fun updateImageNegativePrompt(negativePrompt: String) {
        imageStudioHolder.update { it.copy(negativePrompt = negativePrompt) }
    }

    fun updateImageSeed(seedText: String) {
        imageStudioHolder.update { it.copy(seedInput = seedText.filter { c -> c.isDigit() || c == '-' }.take(12)) }
    }

    fun updateImg2ImgStrength(strength: Float) {
        imageStudioHolder.update { it.copy(img2imgStrength = strength.coerceIn(0.05f, 0.99f)) }
    }

    fun clearImageStudioError() {
        imageStudioHolder.update { it.copy(lastError = null) }
    }

    fun selectGalleryImage(imageId: Long?) {
        imageStudioHolder.update { it.copy(selectedGalleryImageId = imageId) }
    }

    fun importImg2ImgReference(uri: Uri) {
        viewModelScope.launch {
            val res = localImageGenerator.importReferencePhoto(uri)
            res.fold(
                onSuccess = { path ->
                    imageStudioHolder.update { it.copy(initImagePath = path, lastError = null) }
                },
                onFailure = { err ->
                    imageStudioHolder.update { it.copy(lastError = err.localizedMessage ?: "Failed to load reference photo") }
                }
            )
        }
    }

    fun clearImg2ImgReference() {
        imageStudioHolder.update { it.copy(initImagePath = null) }
    }

    fun probeImageServer() {
        val current = settings.value
        if (current.imageGenMode == ImageGenBackendMode.ON_DEVICE_SD_CPP) return
        viewModelScope.launch {
            imageStudioHolder.update { it.copy(isProbingServer = true, serverStatus = null) }
            val result = localImageGenerator.probeServerModels(
                mode = current.imageGenMode,
                serverUrl = current.imageServerUrl,
                apiKey = current.imageServerApiKey
            )
            result.fold(
                onSuccess = { models ->
                    imageStudioHolder.update {
                        it.copy(
                            isProbingServer = false,
                            serverModels = models,
                            serverStatus = "Connected · ${models.size} checkpoint(s) found"
                        )
                    }
                    if (current.imageServerModel.isBlank() && models.isNotEmpty()) {
                        settingsRepository.updateImageServerModel(models.first())
                    }
                },
                onFailure = { err ->
                    imageStudioHolder.update {
                        it.copy(
                            isProbingServer = false,
                            serverStatus = "Offline: ${err.localizedMessage ?: "Server unreachable"}"
                        )
                    }
                }
            )
        }
    }

    fun stopImageGeneration() {
        imageGenJob?.cancel()
        imageGenJob = null
        imageStudioHolder.update { it.copy(isGenerating = false, progressLabel = null) }
    }

    fun generateLocalImage() {
        val ui = imageStudioHolder.state.value
        val prompt = ui.prompt.trim()
        if (prompt.isEmpty() || ui.isGenerating) return

        imageGenJob?.cancel()
        imageGenJob = viewModelScope.launch {
            executeLocalImageGeneration(
                prompt = prompt,
                negativePrompt = ui.negativePrompt.trim(),
                seedOverride = ui.seedInput.trim().toLongOrNull() ?: -1L,
                initImagePath = ui.initImagePath,
                strength = ui.img2imgStrength
            )
        }
    }

    private suspend fun generateImageFromAgentTool(prompt: String, negativePrompt: String): String {
        imageStudioHolder.update {
            it.copy(
                prompt = prompt,
                negativePrompt = negativePrompt.ifBlank { it.negativePrompt }
            )
        }
        val res = executeLocalImageGeneration(
            prompt = prompt,
            negativePrompt = negativePrompt.ifBlank { imageStudioHolder.state.value.negativePrompt },
            seedOverride = -1L,
            initImagePath = null,
            strength = 0.65f
        )
        return res.fold(
            onSuccess = { entity ->
                "Image generated locally (${entity.width}x${entity.height}, seed=${entity.seed}, ${entity.durationMs}ms, engine=${entity.engineLabel}) and saved to Local Image Studio gallery."
            },
            onFailure = { err ->
                "Prompt loaded into Image Studio, but local generation requires a checkpoint or running local WebUI: ${err.localizedMessage}"
            }
        )
    }

    private suspend fun executeLocalImageGeneration(
        prompt: String,
        negativePrompt: String,
        seedOverride: Long,
        initImagePath: String?,
        strength: Float
    ): Result<GeneratedImageEntity> {
        val currentSettings = settings.value
        val allDownloaded = downloadedModels.value.filter { File(it.localPath).exists() }
        val diffusionCandidates = allDownloaded.filter {
            val ext = it.format.lowercase()
            ext == "safetensors" || ext == "ckpt" || (ext == "gguf" && isLikelyDiffusionName(it.fileName))
        }
        val selectedDiffusion = allDownloaded.firstOrNull { it.id == currentSettings.activeImageModelId }
            ?: diffusionCandidates.firstOrNull()

        imageStudioHolder.update {
            it.copy(
                isGenerating = true,
                lastError = null,
                progressLabel = "Preparing local diffusion pipeline…"
            )
        }

        return try {
            val req = ImageGenRequest(
                mode = currentSettings.imageGenMode,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = currentSettings.imageWidth,
                height = currentSettings.imageHeight,
                steps = currentSettings.imageSteps,
                cfgScale = currentSettings.imageCfgScale,
                seed = seedOverride,
                localModelPath = selectedDiffusion?.localPath,
                localModelName = selectedDiffusion?.displayName.orEmpty(),
                serverUrl = currentSettings.imageServerUrl,
                serverApiKey = currentSettings.imageServerApiKey,
                serverModel = currentSettings.imageServerModel,
                hardwareBackend = currentSettings.localBackend,
                threads = currentSettings.localThreads,
                initImagePath = initImagePath,
                img2imgStrength = strength
            )

            val genResult = localImageGenerator.generate(req) { status ->
                imageStudioHolder.update { it.copy(progressLabel = status) }
            }

            genResult.fold(
                onSuccess = { res ->
                    val entity = GeneratedImageEntity(
                        prompt = prompt,
                        negativePrompt = negativePrompt,
                        localPath = res.file.absolutePath,
                        width = res.width,
                        height = res.height,
                        steps = currentSettings.imageSteps,
                        cfgScale = currentSettings.imageCfgScale,
                        seed = res.seed,
                        engineLabel = res.engineLabel,
                        modelName = res.modelName,
                        durationMs = res.durationMs,
                        createdAt = System.currentTimeMillis()
                    )
                    val newId = chatRepository.saveGeneratedImage(entity)
                    val saved = entity.copy(id = newId)
                    imageStudioHolder.update {
                        it.copy(
                            isGenerating = false,
                            progressLabel = null,
                            lastError = null,
                            selectedGalleryImageId = newId
                        )
                    }
                    Result.success(saved)
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "Local image generation failed."
                    imageStudioHolder.update {
                        it.copy(
                            isGenerating = false,
                            progressLabel = null,
                            lastError = msg
                        )
                    }
                    Result.failure(err)
                }
            )
        } catch (ce: CancellationException) {
            imageStudioHolder.update { it.copy(isGenerating = false, progressLabel = null) }
            throw ce
        }
    }

    fun enhanceImagePromptWithAi() {
        val ui = imageStudioHolder.state.value
        val draft = ui.prompt.trim()
        if (draft.isEmpty() || ui.isEnhancingPrompt) return

        viewModelScope.launch {
            val currentSettings = settings.value
            val localModel = resolveActiveLocalModel(currentSettings)
            val effectiveModel = resolveEffectiveModel(currentSettings, localModel)
            imageStudioHolder.update { it.copy(isEnhancingPrompt = true, lastError = null) }

            val sysPrompt =
                "You are a Stable Diffusion / Flux prompt engineer. Rewrite the user's idea into a single vivid, high-detail English image generation prompt (subject, lighting, composition, lens/medium, colors, atmosphere). Output ONLY the prompt text with no quotes, markdown, or explanations."

            val out = StringBuilder()
            try {
                chatRepository.streamDirectCompletion(
                    settings = currentSettings,
                    messages = listOf(
                        ChatMessageDto(role = "system", content = sysPrompt),
                        ChatMessageDto(role = "user", content = draft)
                    ),
                    effectiveModel = effectiveModel,
                    localModel = localModel
                ).collect { ev ->
                    if (ev is StreamEvent.Chunk && ev.contentDelta.isNotEmpty()) {
                        out.append(ev.contentDelta)
                    }
                }
                val enhanced = out.toString()
                    .replace(Regex("(?s)<think>.*?</think>"), "")
                    .trim()
                    .trim('"')
                if (enhanced.isNotBlank()) {
                    imageStudioHolder.update { it.copy(prompt = enhanced, isEnhancingPrompt = false) }
                } else {
                    imageStudioHolder.update { it.copy(isEnhancingPrompt = false) }
                }
            } catch (e: Exception) {
                imageStudioHolder.update {
                    it.copy(
                        isEnhancingPrompt = false,
                        lastError = e.localizedMessage ?: "Failed to enhance prompt"
                    )
                }
            }
        }
    }

    fun saveGeneratedImageToGallery(image: GeneratedImageEntity) {
        viewModelScope.launch {
            val res = localImageGenerator.saveToDeviceGallery(image.localPath)
            res.fold(
                onSuccess = { fileName -> _uiNotice.value = "Saved to Pictures/LocalMind/$fileName" },
                onFailure = { err -> _uiNotice.value = err.localizedMessage ?: "Failed to save image" }
            )
        }
    }

    fun deleteGeneratedImage(image: GeneratedImageEntity) {
        viewModelScope.launch {
            chatRepository.deleteGeneratedImage(image)
            imageStudioHolder.update {
                if (it.selectedGalleryImageId == image.id) it.copy(selectedGalleryImageId = null) else it
            }
        }
    }

    fun reuseGeneratedImageSettings(image: GeneratedImageEntity) {
        imageStudioHolder.update {
            it.copy(
                prompt = image.prompt,
                negativePrompt = image.negativePrompt,
                seedInput = if (image.seed >= 0L) image.seed.toString() else ""
            )
        }
        viewModelScope.launch {
            settingsRepository.updateImageSize(image.width, image.height)
            settingsRepository.updateImageSteps(image.steps)
            settingsRepository.updateImageCfgScale(image.cfgScale)
        }
    }

    fun attachGeneratedImageToChat(image: GeneratedImageEntity) {
        val file = File(image.localPath)
        if (!file.exists()) {
            _uiNotice.value = "Image file not found on disk."
            return
        }
        val attachment = MessageAttachment(
            id = "gen_${image.id}_${System.currentTimeMillis()}",
            displayName = file.name,
            mimeType = "image/png",
            localPath = file.absolutePath,
            sizeBytes = file.length(),
            isImage = true,
            extractedText = null
        )
        _pendingAttachments.update { (it + attachment).take(6) }
        _uiNotice.value = "Image attached to chat composer"
    }

    private fun isLikelyDiffusionName(name: String): Boolean {
        val l = name.lowercase()
        return l.contains("sd") || l.contains("diffusion") || l.contains("flux") ||
            l.contains("dreamshaper") || l.contains("pony") || l.contains("juggernaut") ||
            l.contains("turbo") || l.contains("xl")
    }
}
