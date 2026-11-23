package com.example

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.preferences.AppLocaleManager
import com.example.ui.screens.AgentsMcpScreen
import com.example.ui.screens.ChatWorkspaceScreen
import com.example.ui.screens.CodeSandboxScreen
import com.example.ui.screens.ImageStudioScreen
import com.example.ui.screens.ModelHubScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LocalMindTheme
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.ConnectionUiState

private enum class AppDestination {
    CHAT,
    SETTINGS,
    MODEL_HUB,
    AGENTS_MCP,
    CODE_SANDBOX,
    IMAGE_STUDIO
}

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent {
            LocalMindApp()
        }
    }
}

@Composable
private fun LocalMindApp(
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val downloadedModels by viewModel.downloadedModels.collectAsStateWithLifecycle()
    val modelHubState by viewModel.modelHubState.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val agentNotes by viewModel.agentNotes.collectAsStateWithLifecycle()
    val mcpBusyServerId by viewModel.mcpBusyServerId.collectAsStateWithLifecycle()
    val sandboxState by viewModel.sandboxState.collectAsStateWithLifecycle()
    val imageStudioState by viewModel.imageStudioState.collectAsStateWithLifecycle()
    val generatedImages by viewModel.generatedImages.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val uiNotice by viewModel.uiNotice.collectAsStateWithLifecycle()

    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.CHAT) }

    val activeModelLabel = settings.selectedModel.takeIf { it.isNotBlank() }?.substringAfterLast('/')
        ?: (connectionState as? ConnectionUiState.Connected)?.models?.firstOrNull()?.substringAfterLast('/')
        ?: settings.activeProvider.displayName

    LocalMindTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.useDynamicColor
    ) {
        Crossfade(
            targetState = currentDestination,
            label = "screen_transition"
        ) { destination ->
            when (destination) {
                AppDestination.CHAT -> ChatWorkspaceScreen(
                    viewModel = viewModel,
                    onOpenSettings = { currentDestination = AppDestination.SETTINGS },
                    onOpenModelHub = { currentDestination = AppDestination.MODEL_HUB },
                    onOpenAgentsMcp = { currentDestination = AppDestination.AGENTS_MCP },
                    onOpenCodeSandbox = { currentDestination = AppDestination.CODE_SANDBOX },
                    onOpenImageStudio = { currentDestination = AppDestination.IMAGE_STUDIO },
                    onRunCodeInSandbox = { language, code ->
                        viewModel.openCodeInSandbox(language, code)
                        currentDestination = AppDestination.CODE_SANDBOX
                    }
                )

                AppDestination.SETTINGS -> SettingsScreen(
                    settings = settings,
                    connectionState = connectionState,
                    storageStats = storageStats,
                    hardwareInfo = viewModel.hardwareInfo,
                    updateState = updateState,
                    onNavigateBack = { currentDestination = AppDestination.CHAT },
                    onOpenModelHub = { currentDestination = AppDestination.MODEL_HUB },
                    onOpenAgentsMcp = { currentDestination = AppDestination.AGENTS_MCP },
                    onOpenImageStudio = { currentDestination = AppDestination.IMAGE_STUDIO },
                    onSelectProvider = viewModel::selectProvider,
                    onUpdateProviderBaseUrl = viewModel::updateProviderBaseUrl,
                    onUpdateProviderApiKey = viewModel::updateProviderApiKey,
                    onUpdateProviderModel = viewModel::updateProviderModel,
                    onUpdateLocalEngine = viewModel::updateLocalEngine,
                    onUpdateLocalBackend = viewModel::updateLocalBackend,
                    onUpdateLocalThreads = viewModel::updateLocalThreads,
                    onUpdateSystemPrompt = viewModel::updateSystemPrompt,
                    onUpdateTemperature = viewModel::updateTemperature,
                    onUpdateMaxTokens = viewModel::updateMaxTokens,
                    onUpdateContextLimit = viewModel::updateContextLimit,
                    onUpdateThemeMode = viewModel::updateThemeMode,
                    onUpdateDynamicColor = viewModel::updateDynamicColor,
                    onUpdateAppLanguage = { lang ->
                        viewModel.updateAppLanguage(lang) {
                            (context as? Activity)?.recreate()
                        }
                    },
                    onUpdateVoiceLocale = viewModel::updateVoiceLocale,
                    onUpdateGitHubRepo = viewModel::updateGitHubUpdateRepo,
                    onUpdateAutoCheckUpdates = viewModel::updateAutoCheckUpdates,
                    onCheckForUpdates = { repo -> viewModel.checkForGitHubUpdates(repoOverride = repo, silent = false) },
                    onTestConnection = { provider, url, key ->
                        viewModel.refreshConnection(provider, url, key)
                    },
                    onClearAllHistory = viewModel::clearAllHistory
                )

                AppDestination.MODEL_HUB -> ModelHubScreen(
                    hubState = modelHubState,
                    downloadedModels = downloadedModels,
                    activeProvider = settings.activeProvider,
                    activeLocalModelId = settings.activeLocalModelId,
                    localEngine = settings.localEngine,
                    localBackend = settings.localBackend,
                    hardwareInfo = viewModel.hardwareInfo,
                    huggingFaceToken = settings.huggingFaceToken,
                    onNavigateBack = { currentDestination = AppDestination.CHAT },
                    onUpdateLocalEngine = viewModel::updateLocalEngine,
                    onUpdateLocalBackend = viewModel::updateLocalBackend,
                    onUpdateSearchQuery = viewModel::updateHfSearchQuery,
                    onSelectPreset = viewModel::searchHuggingFacePreset,
                    onSubmitSearch = viewModel::submitHfSearch,
                    onToggleRepo = viewModel::toggleInspectRepo,
                    onStartDownload = viewModel::startModelDownload,
                    onCancelDownload = viewModel::cancelModelDownload,
                    onActivateDownloaded = viewModel::activateDownloadedModel,
                    onDeleteDownloaded = viewModel::deleteDownloadedModel,
                    onImportLocalUri = viewModel::importLocalModelFile,
                    onUpdateHfToken = viewModel::updateHuggingFaceToken,
                    onClearStatusMessage = viewModel::clearModelHubStatus
                )

                AppDestination.AGENTS_MCP -> AgentsMcpScreen(
                    agents = agents,
                    activeAgentId = settings.activeAgentId,
                    enableBuiltInTools = settings.enableBuiltInTools,
                    builtInTools = viewModel.builtInTools,
                    mcpServers = mcpServers,
                    agentNotes = agentNotes,
                    busyServerId = mcpBusyServerId,
                    uiNotice = uiNotice,
                    onClearNotice = viewModel::clearUiNotice,
                    onNavigateBack = { currentDestination = AppDestination.CHAT },
                    onSelectAgent = viewModel::selectActiveAgent,
                    onCreateAgent = viewModel::createCustomAgent,
                    onDeleteAgent = viewModel::deleteCustomAgent,
                    onToggleBuiltInTools = viewModel::updateEnableBuiltInTools,
                    onAddMcpServer = viewModel::addMcpServer,
                    onToggleMcpServer = viewModel::toggleMcpServer,
                    onSyncMcpServer = viewModel::syncMcpServer,
                    onDeleteMcpServer = viewModel::deleteMcpServer,
                    onDeleteNote = viewModel::deleteAgentNote
                )

                AppDestination.CODE_SANDBOX -> CodeSandboxScreen(
                    sandboxState = sandboxState,
                    activeModelLabel = activeModelLabel,
                    onNavigateBack = { currentDestination = AppDestination.CHAT },
                    onSelectLanguage = viewModel::selectSandboxLanguage,
                    onUpdateCode = viewModel::updateSandboxCode,
                    onRunCode = viewModel::runSandboxCode,
                    onResetTemplate = viewModel::resetSandboxTemplate,
                    onClearLogs = viewModel::clearSandboxLogs,
                    onAppendLog = viewModel::appendSandboxLog,
                    onWebRunDone = viewModel::markSandboxWebDone,
                    onUpdateAiPrompt = viewModel::updateSandboxAiPrompt,
                    onGenerateWithAi = viewModel::generateSandboxCodeWithAi,
                    onStopAi = viewModel::stopSandboxAiGeneration
                )

                AppDestination.IMAGE_STUDIO -> ImageStudioScreen(
                    settings = settings,
                    studioState = imageStudioState,
                    hardwareInfo = viewModel.hardwareInfo,
                    downloadedModels = downloadedModels,
                    generatedImages = generatedImages,
                    uiNotice = uiNotice,
                    onClearNotice = viewModel::clearUiNotice,
                    onNavigateBack = { currentDestination = AppDestination.CHAT },
                    onOpenDiffusionHub = {
                        viewModel.searchHuggingFacePreset("diffusion")
                        currentDestination = AppDestination.MODEL_HUB
                    },
                    onSearchModelPreset = { query ->
                        viewModel.searchHuggingFaceQuery(query)
                        currentDestination = AppDestination.MODEL_HUB
                    },
                    onImportCheckpointUri = viewModel::importLocalModelFile,
                    onSelectMode = viewModel::updateImageGenMode,
                    onSelectPipeline = viewModel::selectDiffusionPipeline,
                    onSelectBackend = viewModel::updateLocalBackend,
                    onSelectLocalCheckpoint = viewModel::selectActiveImageModel,
                    onUpdateServerUrl = viewModel::updateImageServerUrl,
                    onUpdateServerModel = viewModel::updateImageServerModel,
                    onProbeServer = viewModel::probeImageServer,
                    onUpdatePrompt = viewModel::updateImagePrompt,
                    onUpdateNegativePrompt = viewModel::updateImageNegativePrompt,
                    onEnhancePrompt = viewModel::enhanceImagePromptWithAi,
                    onPickReferencePhoto = viewModel::importImg2ImgReference,
                    onClearReferencePhoto = viewModel::clearImg2ImgReference,
                    onUpdateStrength = viewModel::updateImg2ImgStrength,
                    onAddMaskStroke = viewModel::addInpaintMaskStroke,
                    onUndoMaskStroke = viewModel::undoInpaintMaskStroke,
                    onClearMask = viewModel::clearInpaintMask,
                    onToggleInvertMask = viewModel::toggleInpaintInvertMask,
                    onUpdateMaskFeather = viewModel::updateInpaintFeather,
                    onUpdateUpscaleMethod = viewModel::updateUpscaleMethod,
                    onUpdateUpscaleDenoise = viewModel::updateUpscaleDenoise,
                    onUpdateSize = viewModel::updateImageSize,
                    onUpdateScheduler = viewModel::updateImageScheduler,
                    onUpdateSteps = viewModel::updateImageSteps,
                    onUpdateCfgScale = viewModel::updateImageCfgScale,
                    onUpdateClipSkip = viewModel::updateImageClipSkip,
                    onUpdateBatchCount = viewModel::updateImageBatchCount,
                    onUpdateHiResFix = viewModel::updateImageHiResFix,
                    onUpdateSeed = viewModel::updateImageSeed,
                    onToggleSeedLock = viewModel::toggleImageSeedLocked,
                    onGenerate = viewModel::generateLocalImage,
                    onStopGenerate = viewModel::stopImageGeneration,
                    onQuickUpscaleImage = { image ->
                        viewModel.upscaleImageNow(
                            sourcePathOverride = image.localPath,
                            methodOverride = com.example.data.imagegen.UpscaleMethod.SMART_SHARP_2X
                        )
                    },
                    onSendToImg2Img = viewModel::sendGeneratedImageToImg2Img,
                    onSendToInpaint = viewModel::sendGeneratedImageToInpaint,
                    onSaveToGallery = viewModel::saveGeneratedImageToGallery,
                    onSendToChat = { image ->
                        viewModel.attachGeneratedImageToChat(image)
                        currentDestination = AppDestination.CHAT
                    },
                    onReuseSettings = viewModel::reuseGeneratedImageSettings,
                    onDeleteImage = viewModel::deleteGeneratedImage,
                    onSelectGalleryImage = viewModel::selectGalleryImage
                )
            }
        }
    }
}
