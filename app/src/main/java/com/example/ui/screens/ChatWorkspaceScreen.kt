package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.attachments.MessageAttachment
import com.example.data.local.DownloadedModelEntity
import com.example.data.local.MessageEntity
import com.example.data.localmodel.LocalInferenceEngine
import com.example.data.network.ApiProtocol
import com.example.data.network.ApiProvider
import com.example.data.updates.GitHubReleaseInfo
import com.example.data.updates.UpdateCheckState
import com.example.ui.components.UpdateCenterBottomSheet
import com.example.ui.components.ChatComposerBar
import com.example.ui.components.ChatMessageItem
import com.example.ui.components.EmptyChatWelcome
import com.example.ui.components.HistorySidebarContent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiquidGlassBackdrop
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlass
import com.example.ui.theme.LocalStudioColors
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.ConnectionUiState
import com.example.voice.VoiceInputManager
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWorkspaceScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenModelHub: () -> Unit,
    onOpenAgentsMcp: () -> Unit,
    onOpenCodeSandbox: () -> Unit,
    onOpenImageStudio: () -> Unit,
    onRunCodeInSandbox: (language: String, code: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val glass = LocalLiquidGlass.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val downloadedModels by viewModel.downloadedModels.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val streamingOverlay by viewModel.streamingOverlay.collectAsStateWithLifecycle()
    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateDownloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()
    val uiNotice by viewModel.uiNotice.collectAsStateWithLifecycle()

    val availableUpdate = remember(updateState, settings.dismissedUpdateTag) {
        val avail = (updateState as? UpdateCheckState.UpdateAvailable)?.release
        avail?.takeIf { it.tagName != settings.dismissedUpdateTag }
    }

    var activeUpdateSheetRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }

    val openUpdateDownload: (GitHubReleaseInfo) -> Unit = { release ->
        activeUpdateSheetRelease = release
    }

    val activeAgentName = remember(agents, settings.activeAgentId) {
        agents.firstOrNull { it.id == settings.activeAgentId }?.name
    }

    var showModelSheet by remember { mutableStateOf(false) }

    val voiceManager = remember(context) {
        VoiceInputManager(
            context = context,
            onPartialResult = {},
            onFinalResult = { transcript -> viewModel.appendVoiceTranscript(transcript) }
        )
    }
    DisposableEffect(voiceManager) {
        onDispose { voiceManager.release() }
    }
    val voiceState by voiceManager.state.collectAsStateWithLifecycle()

    val permissionDeniedMsg = stringResource(R.string.voice_permission_denied)
    val voiceUnavailableMsg = stringResource(R.string.voice_unavailable)

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceManager.startListening(settings.voiceLocale.tag)
        } else {
            scope.launch { snackbarHostState.showSnackbar(permissionDeniedMsg) }
        }
    }

    // Zero-permission Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.addAttachmentUri(uri, forceImage = true)
        }
    }

    // Zero-permission Storage Access Framework file picker
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.addAttachmentUri(uri, forceImage = false)
        }
    }

    LaunchedEffect(voiceState.errorMessage) {
        val err = voiceState.errorMessage
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
            voiceManager.clearError()
        }
    }

    LaunchedEffect(uiNotice) {
        val notice = uiNotice
        if (!notice.isNullOrBlank()) {
            snackbarHostState.showSnackbar(notice)
            viewModel.clearUiNotice()
        }
    }

    val handleToggleVoice: () -> Unit = {
        if (voiceState.isListening) {
            voiceManager.stopListening()
        } else if (!voiceManager.isRecognitionAvailable()) {
            scope.launch { snackbarHostState.showSnackbar(voiceUnavailableMsg) }
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                voiceManager.startListening(settings.voiceLocale.tag)
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val handleAttachPhoto: () -> Unit = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val handleAttachFile: () -> Unit = {
        documentPickerLauncher.launch(arrayOf("*/*"))
    }

    val exportAndShare: (Long) -> Unit = { convoId ->
        scope.launch {
            val markdown = viewModel.exportConversationMarkdown(convoId)
            if (!markdown.isNullOrBlank()) {
                sharePlainText(context, markdown)
            }
        }
    }

    val validLocalModels = remember(downloadedModels) {
        downloadedModels.filter { File(it.localPath).exists() }
    }

    val activeLocalModelName = remember(settings.activeLocalModelId, validLocalModels) {
        validLocalModels.firstOrNull { it.id == settings.activeLocalModelId }?.displayName
            ?: validLocalModels.firstOrNull()?.displayName
    }

    val activeModelLabel = if (settings.activeProvider.protocol == ApiProtocol.ON_DEVICE_MEDIAPIPE) {
        activeLocalModelName ?: settings.activeProvider.displayName
    } else {
        settings.selectedModel.takeIf { it.isNotBlank() }?.substringAfterLast('/')
            ?: (connectionState as? ConnectionUiState.Connected)?.models?.firstOrNull()?.substringAfterLast('/')
            ?: settings.activeProvider.displayName
    }

    LiquidGlassBackdrop {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpandedLayout = maxWidth >= 840.dp

            if (isExpandedLayout) {
                Row(modifier = Modifier.fillMaxSize()) {
                    HistorySidebarContent(
                        conversations = conversations,
                        activeConversationId = activeConversationId,
                        searchQuery = searchQuery,
                        themeMode = settings.themeMode,
                        connectionState = connectionState,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onSelectConversation = viewModel::selectConversation,
                        onNewChat = viewModel::startNewChat,
                        onRenameConversation = viewModel::renameConversation,
                        onTogglePin = viewModel::togglePin,
                        onExportConversation = exportAndShare,
                        onDeleteConversation = viewModel::deleteConversation,
                        onThemeModeChange = viewModel::updateThemeMode,
                        onOpenModelHub = onOpenModelHub,
                        onOpenAgentsMcp = onOpenAgentsMcp,
                        onOpenCodeSandbox = onOpenCodeSandbox,
                        onOpenImageStudio = onOpenImageStudio,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.width(308.dp)
                    )
                    VerticalDivider(color = glass.specularBorderMid)
                    ChatMainScaffold(
                        showMenuButton = false,
                        onOpenDrawer = {},
                        providerName = settings.activeProvider.displayName,
                        activeModelLabel = activeModelLabel,
                        activeAgentName = activeAgentName,
                        availableUpdate = availableUpdate,
                        baseUrl = settings.baseUrl,
                        connectionState = connectionState,
                        messages = messages,
                        streamingOverlay = streamingOverlay,
                        composerText = composerText,
                        pendingAttachments = pendingAttachments,
                        voiceState = voiceState,
                        snackbarHostState = snackbarHostState,
                        onOpenModelPicker = { showModelSheet = true },
                        onNewChat = viewModel::startNewChat,
                        onOpenModelHub = onOpenModelHub,
                        onOpenAgentsMcp = onOpenAgentsMcp,
                        onOpenCodeSandbox = onOpenCodeSandbox,
                        onOpenImageStudio = onOpenImageStudio,
                        onRunCodeInSandbox = onRunCodeInSandbox,
                        onClearActiveAgent = { viewModel.selectActiveAgent("") },
                        onDownloadUpdate = openUpdateDownload,
                        onDismissUpdate = { viewModel.dismissUpdateTag(it) },
                        onOpenSettings = onOpenSettings,
                        onRefreshConnection = { viewModel.refreshConnection() },
                        onComposerChange = viewModel::updateComposerText,
                        onSendMessage = { viewModel.sendMessage(it) },
                        onStopGeneration = viewModel::stopGeneration,
                        onToggleVoice = handleToggleVoice,
                        onAttachPhoto = handleAttachPhoto,
                        onAttachFile = handleAttachFile,
                        onRemoveAttachment = viewModel::removePendingAttachment,
                        onRegenerate = viewModel::regenerateLastResponse,
                        onRetryError = viewModel::retryErrorMessage,
                        onShareText = { sharePlainText(context, it) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

                BackHandler(enabled = drawerState.isOpen) {
                    scope.launch { drawerState.close() }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.widthIn(max = 320.dp),
                            drawerContainerColor = Color.Transparent,
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            HistorySidebarContent(
                                conversations = conversations,
                                activeConversationId = activeConversationId,
                                searchQuery = searchQuery,
                                themeMode = settings.themeMode,
                                connectionState = connectionState,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onSelectConversation = { id ->
                                    viewModel.selectConversation(id)
                                    scope.launch { drawerState.close() }
                                },
                                onNewChat = {
                                    viewModel.startNewChat()
                                    scope.launch { drawerState.close() }
                                },
                                onRenameConversation = viewModel::renameConversation,
                                onTogglePin = viewModel::togglePin,
                                onExportConversation = exportAndShare,
                                onDeleteConversation = viewModel::deleteConversation,
                                onThemeModeChange = viewModel::updateThemeMode,
                                onOpenModelHub = {
                                    scope.launch { drawerState.close() }
                                    onOpenModelHub()
                                },
                                onOpenAgentsMcp = {
                                    scope.launch { drawerState.close() }
                                    onOpenAgentsMcp()
                                },
                                onOpenCodeSandbox = {
                                    scope.launch { drawerState.close() }
                                    onOpenCodeSandbox()
                                },
                                onOpenImageStudio = {
                                    scope.launch { drawerState.close() }
                                    onOpenImageStudio()
                                },
                                onOpenSettings = {
                                    scope.launch { drawerState.close() }
                                    onOpenSettings()
                                }
                            )
                        }
                    }
                ) {
                    ChatMainScaffold(
                        showMenuButton = true,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        providerName = settings.activeProvider.displayName,
                        activeModelLabel = activeModelLabel,
                        activeAgentName = activeAgentName,
                        availableUpdate = availableUpdate,
                        baseUrl = settings.baseUrl,
                        connectionState = connectionState,
                        messages = messages,
                        streamingOverlay = streamingOverlay,
                        composerText = composerText,
                        pendingAttachments = pendingAttachments,
                        voiceState = voiceState,
                        snackbarHostState = snackbarHostState,
                        onOpenModelPicker = { showModelSheet = true },
                        onNewChat = viewModel::startNewChat,
                        onOpenModelHub = onOpenModelHub,
                        onOpenAgentsMcp = onOpenAgentsMcp,
                        onOpenCodeSandbox = onOpenCodeSandbox,
                        onOpenImageStudio = onOpenImageStudio,
                        onRunCodeInSandbox = onRunCodeInSandbox,
                        onClearActiveAgent = { viewModel.selectActiveAgent("") },
                        onDownloadUpdate = openUpdateDownload,
                        onDismissUpdate = { viewModel.dismissUpdateTag(it) },
                        onOpenSettings = onOpenSettings,
                        onRefreshConnection = { viewModel.refreshConnection() },
                        onComposerChange = viewModel::updateComposerText,
                        onSendMessage = { viewModel.sendMessage(it) },
                        onStopGeneration = viewModel::stopGeneration,
                        onToggleVoice = handleToggleVoice,
                        onAttachPhoto = handleAttachPhoto,
                        onAttachFile = handleAttachFile,
                        onRemoveAttachment = viewModel::removePendingAttachment,
                        onRegenerate = viewModel::regenerateLastResponse,
                        onRetryError = viewModel::retryErrorMessage,
                        onShareText = { sharePlainText(context, it) }
                    )
                }
            }
        }
    }

    if (showModelSheet) {
        ProviderAndModelBottomSheet(
            activeProvider = settings.activeProvider,
            activeLocalEngine = settings.localEngine,
            selectedModel = settings.selectedModel,
            activeLocalModelId = settings.activeLocalModelId,
            downloadedModels = validLocalModels,
            connectionState = connectionState,
            onSelectProvider = viewModel::selectProvider,
            onSelectModel = { modelId ->
                viewModel.updateProviderModel(settings.activeProvider, modelId)
                showModelSheet = false
            },
            onSelectLocalModel = { entity ->
                viewModel.activateDownloadedModel(entity)
                showModelSheet = false
            },
            onRefresh = { viewModel.refreshConnection() },
            onOpenModelHub = {
                showModelSheet = false
                onOpenModelHub()
            },
            onOpenSettings = {
                showModelSheet = false
                onOpenSettings()
            },
            onDismiss = { showModelSheet = false }
        )
    }

    activeUpdateSheetRelease?.let { release ->
        UpdateCenterBottomSheet(
            release = release,
            downloadState = updateDownloadState,
            onStartDownload = viewModel::startApkUpdateDownload,
            onCancelDownload = viewModel::cancelApkUpdateDownload,
            onSkipVersion = viewModel::dismissUpdateTag,
            onDismiss = { activeUpdateSheetRelease = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatMainScaffold(
    showMenuButton: Boolean,
    onOpenDrawer: () -> Unit,
    providerName: String,
    activeModelLabel: String,
    activeAgentName: String?,
    availableUpdate: GitHubReleaseInfo?,
    baseUrl: String,
    connectionState: ConnectionUiState,
    messages: List<MessageEntity>,
    streamingOverlay: com.example.ui.viewmodel.StreamingMessageOverlay?,
    composerText: String,
    pendingAttachments: List<MessageAttachment>,
    voiceState: com.example.voice.VoiceUiState,
    snackbarHostState: SnackbarHostState,
    onOpenModelPicker: () -> Unit,
    onNewChat: () -> Unit,
    onOpenModelHub: () -> Unit,
    onOpenAgentsMcp: () -> Unit,
    onOpenCodeSandbox: () -> Unit,
    onOpenImageStudio: () -> Unit,
    onRunCodeInSandbox: (language: String, code: String) -> Unit,
    onClearActiveAgent: () -> Unit,
    onDownloadUpdate: (GitHubReleaseInfo) -> Unit,
    onDismissUpdate: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshConnection: () -> Unit,
    onComposerChange: (String) -> Unit,
    onSendMessage: (String?) -> Unit,
    onStopGeneration: () -> Unit,
    onToggleVoice: () -> Unit,
    onAttachPhoto: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRetryError: (Long) -> Unit,
    onShareText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val displayMessages = remember(messages, streamingOverlay) {
        if (streamingOverlay == null) {
            messages
        } else {
            messages.map { msg ->
                if (msg.id == streamingOverlay.messageId) {
                    msg.copy(
                        content = streamingOverlay.content,
                        thinkingContent = streamingOverlay.thinkingContent,
                        modelName = streamingOverlay.modelName ?: msg.modelName
                    )
                } else {
                    msg
                }
            }
        }
    }

    val lastAssistantId = remember(displayMessages) {
        displayMessages.lastOrNull { it.role == "assistant" && !it.isError }?.id
    }

    // Never fight the user's touch gesture during streaming!
    // Immediately pause auto-scroll the instant the user scrolls upward.
    var autoScrollEnabled by remember { mutableStateOf(true) }

    val scrollInterceptConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 1.5f) {
                    autoScrollEnabled = false
                }
                return Offset.Zero
            }
        }
    }

    // Re-enable auto-scroll automatically when the user finishes scrolling down to the very bottom
    LaunchedEffect(listState.isScrollInProgress, listState.canScrollForward) {
        if (!listState.isScrollInProgress && !listState.canScrollForward) {
            autoScrollEnabled = true
        }
    }

    val showScrollToBottomPill by remember {
        derivedStateOf {
            displayMessages.isNotEmpty() && listState.canScrollForward
        }
    }

    var previousMessageCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(displayMessages.size) {
        if (displayMessages.size > previousMessageCount && displayMessages.isNotEmpty()) {
            autoScrollEnabled = true
            listState.animateScrollToItem(displayMessages.lastIndex, scrollOffset = Int.MAX_VALUE / 4)
        }
        previousMessageCount = displayMessages.size
    }

    LaunchedEffect(streamingOverlay?.content?.length, streamingOverlay?.thinkingContent?.length) {
        if (streamingOverlay != null &&
            autoScrollEnabled &&
            !listState.isScrollInProgress &&
            displayMessages.isNotEmpty()
        ) {
            listState.scrollToItem(displayMessages.lastIndex, scrollOffset = Int.MAX_VALUE / 4)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    if (showMenuButton) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.testTag("open_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Menu,
                                contentDescription = stringResource(R.string.action_open_drawer)
                            )
                        }
                    }
                },
                title = {
                    val dotColor = when (connectionState) {
                        is ConnectionUiState.Connected -> studioColors.statusOnline
                        ConnectionUiState.Checking -> studioColors.statusWarning
                        is ConnectionUiState.Offline -> studioColors.statusOffline
                    }

                    LiquidGlassCard(
                        onClick = onOpenModelPicker,
                        shape = RoundedCornerShape(18.dp),
                        elevated = true,
                        modifier = Modifier.testTag("model_selector_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = activeModelLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 165.dp)
                            )
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.model_picker_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenImageStudio,
                        modifier = Modifier.testTag("topbar_image_studio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = stringResource(R.string.action_image_studio),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onOpenCodeSandbox,
                        modifier = Modifier.testTag("topbar_code_sandbox_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = stringResource(R.string.action_code_sandbox)
                        )
                    }
                    IconButton(
                        onClick = onOpenAgentsMcp,
                        modifier = Modifier.testTag("topbar_agents_mcp_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SmartToy,
                            contentDescription = stringResource(R.string.action_agents_mcp),
                            tint = if (!activeAgentName.isNullOrBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (!showMenuButton) {
                        IconButton(
                            onClick = onOpenModelHub,
                            modifier = Modifier.testTag("topbar_model_hub_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = stringResource(R.string.action_model_hub)
                            )
                        }
                    }
                    IconButton(
                        onClick = onNewChat,
                        modifier = Modifier.testTag("topbar_new_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddComment,
                            contentDescription = stringResource(R.string.action_new_chat)
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("topbar_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.action_settings)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                ChatComposerBar(
                    text = composerText,
                    pendingAttachments = pendingAttachments,
                    onTextChange = onComposerChange,
                    isGenerating = streamingOverlay != null,
                    voiceState = voiceState,
                    onSend = { onSendMessage(null) },
                    onStop = onStopGeneration,
                    onToggleVoice = onToggleVoice,
                    onAttachPhoto = onAttachPhoto,
                    onAttachFile = onAttachFile,
                    onRemoveAttachment = onRemoveAttachment,
                    modifier = Modifier.widthIn(max = 820.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // GitHub Release Update Banner
            if (availableUpdate != null) {
                LiquidGlassCard(
                    onClick = { onDownloadUpdate(availableUpdate) },
                    shape = RoundedCornerShape(50),
                    tinted = true,
                    elevated = true,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("github_update_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = studioColors.statusOnline,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.updates_banner_text, availableUpdate.tagName),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = { onDismissUpdate(availableUpdate.tagName) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Active Autonomous Agent Liquid Glass Pill Banner
            if (!activeAgentName.isNullOrBlank()) {
                LiquidGlassCard(
                    onClick = onOpenAgentsMcp,
                    shape = RoundedCornerShape(50),
                    tinted = true,
                    elevated = true,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("active_agent_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.agents_active_pill, activeAgentName),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = onClearActiveAgent,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (displayMessages.isEmpty()) {
                    EmptyChatWelcome(
                        providerName = providerName,
                        baseUrl = baseUrl,
                        connectionState = connectionState,
                        onSelectPrompt = { prompt -> onSendMessage(prompt) },
                        onOpenSettings = onOpenSettings,
                        onOpenModelHub = onOpenModelHub,
                        onRefreshConnection = onRefreshConnection
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .widthIn(max = 820.dp)
                            .nestedScroll(scrollInterceptConnection),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(
                            items = displayMessages,
                            key = { _, item -> item.id }
                        ) { _, msg ->
                            val isStreamingThis = streamingOverlay?.messageId == msg.id
                            ChatMessageItem(
                                message = msg,
                                isStreaming = isStreamingThis,
                                isThinkingPhase = isStreamingThis && (streamingOverlay?.isThinkingPhase == true),
                                isLastAssistantMessage = msg.id == lastAssistantId,
                                onRegenerate = onRegenerate,
                                onRetryError = { onRetryError(msg.id) },
                                onOpenSettings = onOpenSettings,
                                onShare = onShareText,
                                onRunCodeInSandbox = onRunCodeInSandbox
                            )
                        }
                    }

                    // Floating Liquid Glass "Scroll to latest" pill when reading older messages
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToBottomPill,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    ) {
                        LiquidGlassCard(
                            onClick = {
                                autoScrollEnabled = true
                                scope.launch {
                                    if (displayMessages.isNotEmpty()) {
                                        listState.animateScrollToItem(
                                            displayMessages.lastIndex,
                                            scrollOffset = Int.MAX_VALUE / 4
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50),
                            elevated = true,
                            modifier = Modifier.testTag("scroll_to_bottom_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDownward,
                                    contentDescription = stringResource(R.string.action_scroll_to_bottom),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.action_scroll_to_bottom),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderAndModelBottomSheet(
    activeProvider: ApiProvider,
    activeLocalEngine: LocalInferenceEngine,
    selectedModel: String,
    activeLocalModelId: String,
    downloadedModels: List<DownloadedModelEntity>,
    connectionState: ConnectionUiState,
    onSelectProvider: (ApiProvider) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectLocalModel: (DownloadedModelEntity) -> Unit,
    onRefresh: () -> Unit,
    onOpenModelHub: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.model_picker_title),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.model_picker_refresh)
                    )
                }
            }

            // Grouped Provider Switcher Chips: Free Cloud, Local & On-Device, Cloud APIs
            Text(
                text = stringResource(R.string.provider_section_free),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ApiProvider.entries.filter { it.isFreeTier }.forEach { provider ->
                    FilterChip(
                        selected = provider == activeProvider,
                        onClick = { onSelectProvider(provider) },
                        label = { Text(provider.displayName, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Text(
                text = stringResource(R.string.provider_section_local),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ApiProvider.entries.filter { it.isLocal }.forEach { provider ->
                    FilterChip(
                        selected = provider == activeProvider,
                        onClick = { onSelectProvider(provider) },
                        label = { Text(provider.displayName, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Text(
                text = stringResource(R.string.provider_section_cloud),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ApiProvider.entries.filter { !it.isLocal && !it.isFreeTier }.forEach { provider ->
                    FilterChip(
                        selected = provider == activeProvider,
                        onClick = { onSelectProvider(provider) },
                        label = { Text(provider.displayName, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (activeProvider.protocol == ApiProtocol.ON_DEVICE_MEDIAPIPE) {
                if (downloadedModels.isEmpty()) {
                    LiquidGlassCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.hub_no_downloaded),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(onClick = onOpenModelHub) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_model_hub))
                            }
                        }
                    }
                } else {
                    downloadedModels.forEach { localModel ->
                        val isSelected = localModel.id == activeLocalModelId
                        val resolvedEngine = LocalInferenceEngine.resolveForFormat(
                            activeLocalEngine,
                            localModel.format
                        )
                        val engineBadge = when (resolvedEngine) {
                            LocalInferenceEngine.LLAMA_CPP -> stringResource(R.string.hub_badge_llama_cpp)
                            LocalInferenceEngine.LITERT_LM -> stringResource(R.string.hub_badge_litert_lm)
                            LocalInferenceEngine.MLC_LLM -> stringResource(R.string.hub_badge_mlc_llm)
                            else -> stringResource(R.string.hub_badge_mediapipe)
                        }
                        LiquidGlassCard(
                            onClick = { onSelectLocalModel(localModel) },
                            shape = RoundedCornerShape(16.dp),
                            tinted = isSelected,
                            elevated = isSelected,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = localModel.displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = JetBrainsMonoFontFamily
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$engineBadge · .${localModel.format}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val models = (
                    (connectionState as? ConnectionUiState.Connected)?.models.orEmpty() +
                        activeProvider.suggestedModels
                    ).distinct().take(20)

                if (models.isEmpty()) {
                    LiquidGlassCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.model_picker_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    models.forEach { modelId ->
                        val isSelected = modelId == selectedModel
                        LiquidGlassCard(
                            onClick = { onSelectModel(modelId) },
                            shape = RoundedCornerShape(16.dp),
                            tinted = isSelected,
                            elevated = isSelected,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenModelHub,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.action_model_hub),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.action_settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

private fun sharePlainText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
