package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.data.local.StorageStats
import com.example.data.localmodel.HardwareAcceleratorInfo
import com.example.data.localmodel.LocalHardwareBackend
import com.example.data.localmodel.LocalInferenceEngine
import com.example.data.network.ApiProtocol
import com.example.data.network.ApiProvider
import com.example.data.preferences.AppLanguage
import com.example.data.preferences.AppSettings
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.VoiceLocale
import com.example.data.updates.GitHubUpdateManager
import com.example.data.updates.UpdateCheckState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiquidGlassBackdrop
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalStudioColors
import com.example.ui.viewmodel.ConnectionUiState
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    connectionState: ConnectionUiState,
    storageStats: StorageStats,
    hardwareInfo: HardwareAcceleratorInfo,
    updateState: UpdateCheckState,
    onNavigateBack: () -> Unit,
    onOpenModelHub: () -> Unit,
    onOpenAgentsMcp: () -> Unit,
    onOpenImageStudio: () -> Unit,
    onSelectProvider: (ApiProvider) -> Unit,
    onUpdateProviderBaseUrl: (ApiProvider, String) -> Unit,
    onUpdateProviderApiKey: (ApiProvider, String) -> Unit,
    onUpdateProviderModel: (ApiProvider, String) -> Unit,
    onUpdateLocalEngine: (LocalInferenceEngine) -> Unit,
    onUpdateLocalBackend: (LocalHardwareBackend) -> Unit,
    onUpdateLocalThreads: (Int) -> Unit,
    onUpdateSystemPrompt: (String) -> Unit,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateMaxTokens: (Int) -> Unit,
    onUpdateContextLimit: (Int) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateDynamicColor: (Boolean) -> Unit,
    onUpdateAppLanguage: (AppLanguage) -> Unit,
    onUpdateVoiceLocale: (VoiceLocale) -> Unit,
    onUpdateGitHubRepo: (String) -> Unit,
    onUpdateAutoCheckUpdates: (Boolean) -> Unit,
    onCheckForUpdates: (String) -> Unit,
    onTestConnection: (ApiProvider, String, String) -> Unit,
    onClearAllHistory: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val studioColors = LocalStudioColors.current
    val provider = settings.activeProvider
    var urlDraft by remember(provider, settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKeyDraft by remember(provider, settings.apiKey) { mutableStateOf(settings.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var modelDraft by remember(provider, settings.selectedModel) { mutableStateOf(settings.selectedModel) }
    var systemPromptDraft by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var githubRepoDraft by remember(settings.githubUpdateRepo) { mutableStateOf(settings.githubUpdateRepo) }
    var confirmClearAll by remember { mutableStateOf(false) }

    LiquidGlassBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (provider.protocol != ApiProtocol.ON_DEVICE_MEDIAPIPE) {
                                    onUpdateProviderBaseUrl(provider, urlDraft)
                                    onUpdateProviderApiKey(provider, apiKeyDraft)
                                    onUpdateProviderModel(provider, modelDraft)
                                }
                                onUpdateSystemPrompt(systemPromptDraft)
                                onNavigateBack()
                            },
                            modifier = Modifier.testTag("settings_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Universal API Provider Selector
                    SettingsSectionCard(
                        title = stringResource(R.string.settings_section_provider),
                        icon = Icons.Rounded.Api
                    ) {
                        Text(
                            text = stringResource(R.string.provider_section_free),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ApiProvider.entries.filter { it.isFreeTier }.forEach { item ->
                                FilterChip(
                                    selected = provider == item,
                                    onClick = { onSelectProvider(item) },
                                    label = { Text(item.displayName, style = MaterialTheme.typography.labelMedium) },
                                    modifier = Modifier.testTag("provider_chip_${item.id}")
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.provider_section_local),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ApiProvider.entries.filter { it.isLocal }.forEach { item ->
                                FilterChip(
                                    selected = provider == item,
                                    onClick = { onSelectProvider(item) },
                                    label = { Text(item.displayName, style = MaterialTheme.typography.labelMedium) },
                                    modifier = Modifier.testTag("provider_chip_${item.id}")
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.provider_section_cloud),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ApiProvider.entries.filter { !it.isLocal && !it.isFreeTier }.forEach { item ->
                                FilterChip(
                                    selected = provider == item,
                                    onClick = { onSelectProvider(item) },
                                    label = { Text(item.displayName, style = MaterialTheme.typography.labelMedium) },
                                    modifier = Modifier.testTag("provider_chip_${item.id}")
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = onOpenModelHub,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_hub_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_open_model_hub))
                        }

                        FilledTonalButton(
                            onClick = onOpenAgentsMcp,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_agents_mcp_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_open_agents_mcp))
                        }

                        FilledTonalButton(
                            onClick = onOpenImageStudio,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_image_studio_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Image,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_open_image_studio))
                        }
                    }

                    // 2. On-Device Local Inference Engines (llama.cpp / LiteRT-LM / MediaPipe + CPU/GPU + Threads)
                    SettingsSectionCard(
                        title = stringResource(R.string.settings_section_local_engine),
                        icon = Icons.Rounded.Tune
                    ) {
                        Text(
                            text = stringResource(R.string.settings_local_engine_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val engineOptions = listOf(
                            LocalInferenceEngine.AUTO to stringResource(R.string.engine_auto),
                            LocalInferenceEngine.LLAMA_CPP to stringResource(R.string.engine_llama_cpp),
                            LocalInferenceEngine.LITERT_LM to stringResource(R.string.engine_litert_lm),
                            LocalInferenceEngine.MEDIAPIPE_GENAI to stringResource(R.string.engine_mediapipe)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            engineOptions.forEach { (engine, label) ->
                                FilterChip(
                                    selected = settings.localEngine == engine,
                                    onClick = { onUpdateLocalEngine(engine) },
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                    modifier = Modifier.testTag("settings_engine_${engine.id}")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.settings_local_backend_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.testTag("settings_local_backend_segmented")
                            ) {
                                val backends = listOf(
                                    LocalHardwareBackend.CPU to stringResource(R.string.backend_cpu),
                                    LocalHardwareBackend.GPU to stringResource(R.string.backend_gpu),
                                    LocalHardwareBackend.NPU to stringResource(R.string.backend_npu)
                                )
                                backends.forEachIndexed { idx, (backend, label) ->
                                    SegmentedButton(
                                        selected = settings.localBackend == backend,
                                        onClick = { onUpdateLocalBackend(backend) },
                                        shape = SegmentedButtonDefaults.itemShape(idx, backends.size),
                                        icon = {},
                                        modifier = Modifier.testTag("settings_backend_${backend.id}")
                                    ) {
                                        Text(label, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }

                        // Hardware & NPU Accelerator Diagnostics Card
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_npu_hardware_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = hardwareInfo.npuVendor,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val badgeColor = if (hardwareInfo.npuSupported) {
                                        studioColors.statusOnline
                                    } else {
                                        studioColors.statusWarning
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = badgeColor.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = stringResource(
                                                if (hardwareInfo.npuSupported) {
                                                    R.string.npu_status_ready
                                                } else {
                                                    R.string.npu_status_fallback
                                                }
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = badgeColor,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val ramGb = ((hardwareInfo.totalRamMb + 512L) / 1024L).coerceAtLeast(1L)
                                Text(
                                    text = stringResource(
                                        R.string.npu_hardware_specs,
                                        hardwareInfo.socName,
                                        hardwareInfo.cpuCores,
                                        ramGb
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = hardwareInfo.npuDetail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.npu_tip),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                )
                            }
                        }

                        SliderPreferenceRow(
                            label = stringResource(R.string.settings_local_threads_label),
                            valueText = "${settings.localThreads}",
                            value = settings.localThreads.toFloat(),
                            valueRange = 1f..8f,
                            onValueChange = { onUpdateLocalThreads(it.roundToInt()) }
                        )
                    }

                    // 3. Endpoint & Credentials Card (hidden for On-Device)
                    if (provider.protocol != ApiProtocol.ON_DEVICE_MEDIAPIPE) {
                        SettingsSectionCard(
                            title = "${stringResource(R.string.settings_section_connection)} · ${provider.displayName}",
                            icon = Icons.Rounded.Dns
                        ) {
                            OutlinedTextField(
                                value = urlDraft,
                                onValueChange = {
                                    urlDraft = it
                                    onUpdateProviderBaseUrl(provider, it)
                                },
                                label = { Text(stringResource(R.string.settings_server_url_label)) },
                                placeholder = { Text(provider.defaultBaseUrl) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_server_url_input")
                            )

                            if (provider == ApiProvider.LM_STUDIO || provider == ApiProvider.OLLAMA) {
                                val port = if (provider == ApiProvider.OLLAMA) "11434" else "1234"
                                Text(
                                    text = stringResource(R.string.settings_presets_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val presets = listOf(
                                        stringResource(R.string.preset_emulator) to "http://10.0.2.2:$port/v1",
                                        stringResource(R.string.preset_localhost) to "http://127.0.0.1:$port/v1",
                                        stringResource(R.string.preset_lan) to "http://192.168.1.100:$port/v1"
                                    )
                                    presets.forEach { (label, presetUrl) ->
                                        FilterChip(
                                            selected = urlDraft.trim() == presetUrl,
                                            onClick = {
                                                urlDraft = presetUrl
                                                onUpdateProviderBaseUrl(provider, presetUrl)
                                            },
                                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = apiKeyDraft,
                                onValueChange = {
                                    apiKeyDraft = it
                                    onUpdateProviderApiKey(provider, it)
                                },
                                label = { Text(stringResource(R.string.settings_api_key_label)) },
                                placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
                                singleLine = true,
                                visualTransformation = if (showApiKey) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showApiKey = !showApiKey }) {
                                        Icon(
                                            imageVector = if (showApiKey) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = null
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_api_key_input")
                            )

                            Button(
                                onClick = {
                                    onUpdateProviderBaseUrl(provider, urlDraft)
                                    onUpdateProviderApiKey(provider, apiKeyDraft)
                                    onTestConnection(provider, urlDraft, apiKeyDraft)
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_test_connection_button")
                            ) {
                                if (connectionState is ConnectionUiState.Checking) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.settings_test_connection))
                            }

                            val statusBannerColor = when (connectionState) {
                                is ConnectionUiState.Connected -> studioColors.statusOnline.copy(alpha = 0.14f)
                                ConnectionUiState.Checking -> studioColors.statusWarning.copy(alpha = 0.14f)
                                is ConnectionUiState.Offline -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = statusBannerColor,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = when (connectionState) {
                                        is ConnectionUiState.Connected ->
                                            "${stringResource(R.string.status_connected)} (${connectionState.latencyMs} ms) — ${connectionState.models.size} model(s)"
                                        ConnectionUiState.Checking ->
                                            stringResource(R.string.status_checking)
                                        is ConnectionUiState.Offline ->
                                            "${stringResource(R.string.status_offline)}: ${connectionState.reason}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            Text(
                                text = stringResource(R.string.settings_connection_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 4. Model & Generation Parameters Card
                    SettingsSectionCard(
                        title = stringResource(R.string.settings_section_generation),
                        icon = Icons.Rounded.Memory
                    ) {
                        if (provider.protocol != ApiProtocol.ON_DEVICE_MEDIAPIPE) {
                            val availableModels = (
                                (connectionState as? ConnectionUiState.Connected)?.models.orEmpty() +
                                    provider.suggestedModels
                                ).distinct().take(12)

                            if (availableModels.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    availableModels.forEach { modelId ->
                                        FilterChip(
                                            selected = modelDraft == modelId,
                                            onClick = {
                                                modelDraft = modelId
                                                onUpdateProviderModel(provider, modelId)
                                            },
                                            leadingIcon = if (modelDraft == modelId) {
                                                {
                                                    Icon(
                                                        Icons.Rounded.CheckCircle,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            } else null,
                                            label = {
                                                Text(
                                                    text = modelId.substringAfterLast('/'),
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = modelDraft,
                                onValueChange = {
                                    modelDraft = it
                                    onUpdateProviderModel(provider, it)
                                },
                                label = { Text(stringResource(R.string.settings_active_model_label)) },
                                placeholder = { Text(stringResource(R.string.model_auto_detect)) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedTextField(
                            value = systemPromptDraft,
                            onValueChange = {
                                systemPromptDraft = it
                                onUpdateSystemPrompt(it)
                            },
                            label = { Text(stringResource(R.string.settings_system_prompt_label)) },
                            minLines = 3,
                            maxLines = 5,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        SliderPreferenceRow(
                            label = stringResource(R.string.settings_temperature_label),
                            valueText = String.format(Locale.US, "%.2f", settings.temperature),
                            value = settings.temperature,
                            valueRange = 0f..2f,
                            onValueChange = onUpdateTemperature
                        )

                        SliderPreferenceRow(
                            label = stringResource(R.string.settings_max_tokens_label),
                            valueText = "${settings.maxTokens}",
                            value = settings.maxTokens.toFloat(),
                            valueRange = 256f..8192f,
                            onValueChange = { onUpdateMaxTokens((it / 128f).roundToInt() * 128) }
                        )

                        SliderPreferenceRow(
                            label = stringResource(R.string.settings_context_limit_label),
                            valueText = "${settings.contextMessageLimit}",
                            value = settings.contextMessageLimit.toFloat(),
                            valueRange = 4f..40f,
                            onValueChange = { onUpdateContextLimit(it.roundToInt()) }
                        )
                    }

                    // 5. Theme, Interface Language & Voice Card
                    SettingsSectionCard(
                        title = stringResource(R.string.settings_section_appearance),
                        icon = Icons.Rounded.Palette
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_theme_segmented")
                        ) {
                            val options = listOf(
                                Triple(ThemeMode.SYSTEM, Icons.Rounded.SettingsBrightness, stringResource(R.string.theme_system)),
                                Triple(ThemeMode.LIGHT, Icons.Rounded.LightMode, stringResource(R.string.theme_light)),
                                Triple(ThemeMode.DARK, Icons.Rounded.DarkMode, stringResource(R.string.theme_dark)),
                                Triple(ThemeMode.AMOLED, Icons.Rounded.Contrast, stringResource(R.string.theme_amoled))
                            )
                            options.forEach { (mode, icon, label) ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onUpdateThemeMode(mode) },
                                    leadingIcon = {
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                    modifier = Modifier.testTag("settings_theme_${mode.name.lowercase()}")
                                )
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.settings_dynamic_color),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_dynamic_color_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = settings.useDynamicColor,
                                    onCheckedChange = onUpdateDynamicColor,
                                    modifier = Modifier.testTag("settings_dynamic_color_switch")
                                )
                            }
                        }

                        // Interface Language Picker
                        Text(
                            text = stringResource(R.string.settings_app_language_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val languages = listOf(
                                AppLanguage.SYSTEM to stringResource(R.string.lang_system),
                                AppLanguage.UK to stringResource(R.string.lang_uk),
                                AppLanguage.RU to stringResource(R.string.lang_ru),
                                AppLanguage.EN to stringResource(R.string.lang_en)
                            )
                            languages.forEach { (lang, label) ->
                                FilterChip(
                                    selected = settings.appLanguage == lang,
                                    onClick = { onUpdateAppLanguage(lang) },
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                    modifier = Modifier.testTag("lang_chip_${lang.code}")
                                )
                            }
                        }

                        // Voice Recognition Language Picker
                        Text(
                            text = stringResource(R.string.settings_voice_locale_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val locales = listOf(
                                VoiceLocale.AUTO to stringResource(R.string.voice_locale_auto),
                                VoiceLocale.UK to stringResource(R.string.voice_locale_uk),
                                VoiceLocale.RU to stringResource(R.string.voice_locale_ru),
                                VoiceLocale.EN to stringResource(R.string.voice_locale_en)
                            )
                            locales.forEach { (locale, label) ->
                                FilterChip(
                                    selected = settings.voiceLocale == locale,
                                    onClick = { onUpdateVoiceLocale(locale) },
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }
                    }

                    // 6. GitHub Releases App Update System
                    SettingsSectionCard(
                        title = stringResource(R.string.settings_section_updates),
                        icon = Icons.Rounded.SystemUpdate
                    ) {
                        Text(
                            text = stringResource(
                                R.string.updates_current_version,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE
                            ),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )

                        OutlinedTextField(
                            value = githubRepoDraft,
                            onValueChange = {
                                githubRepoDraft = it
                                onUpdateGitHubRepo(it)
                            },
                            label = { Text(stringResource(R.string.updates_repo_label)) },
                            placeholder = { Text(stringResource(R.string.updates_repo_placeholder)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_github_repo_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.updates_auto_check),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = stringResource(R.string.updates_auto_check_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.autoCheckUpdates,
                                onCheckedChange = onUpdateAutoCheckUpdates,
                                modifier = Modifier.testTag("settings_auto_update_switch")
                            )
                        }

                        Button(
                            onClick = {
                                onUpdateGitHubRepo(githubRepoDraft)
                                onCheckForUpdates(githubRepoDraft)
                            },
                            enabled = updateState !is UpdateCheckState.Checking,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_check_updates_button")
                        ) {
                            if (updateState is UpdateCheckState.Checking) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.updates_checking))
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.updates_check_now))
                            }
                        }

                        when (updateState) {
                            is UpdateCheckState.UpdateAvailable -> {
                                val release = updateState.release
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = studioColors.statusOnline.copy(alpha = 0.14f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.updates_available_title, release.tagName),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = studioColors.statusOnline
                                        )
                                        if (release.apkAssetName != null) {
                                            val sizeMb = String.format(
                                                Locale.US,
                                                "%.1f MB",
                                                release.apkSizeBytes / (1024.0 * 1024.0)
                                            )
                                            Text(
                                                text = "${release.apkAssetName} · $sizeMb${if (release.publishedAt.isNotBlank()) " · ${release.publishedAt}" else ""}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = JetBrainsMonoFontFamily
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (release.changelog.isNotBlank()) {
                                            Text(
                                                text = release.changelog,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 4,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val downloadTarget = release.apkDownloadUrl ?: release.htmlUrl
                                            Button(
                                                onClick = {
                                                    if (GitHubUpdateManager.isTrustedGitHubUrl(downloadTarget)) {
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent(Intent.ACTION_VIEW, Uri.parse(downloadTarget))
                                                            )
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("settings_download_apk_button")
                                            ) {
                                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.updates_download_apk))
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    if (GitHubUpdateManager.isTrustedGitHubUrl(release.htmlUrl)) {
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                                                            )
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(stringResource(R.string.updates_view_release))
                                            }
                                        }
                                    }
                                }
                            }

                            is UpdateCheckState.UpToDate -> {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = studioColors.statusOnline.copy(alpha = 0.14f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.updates_up_to_date, updateState.latestTag),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            is UpdateCheckState.Error -> {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = updateState.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            else -> Unit
                        }

                        Text(
                            text = stringResource(R.string.updates_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 7. Local Room Database Card
                    SettingsSectionCard(
                        title = stringResource(R.string.settings_section_storage),
                        icon = Icons.Rounded.Storage
                    ) {
                        Text(
                            text = stringResource(
                                R.string.settings_storage_stats,
                                storageStats.chatCount,
                                storageStats.messageCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedButton(
                            onClick = { confirmClearAll = true },
                            enabled = storageStats.chatCount > 0,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(
                                    alpha = if (storageStats.chatCount > 0) 0.5f else 0.2f
                                )
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_clear_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_clear_history))
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.dialog_clear_all_title)) },
            text = { Text(stringResource(R.string.dialog_clear_all_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllHistory()
                        confirmClearAll = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.action_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    LiquidGlassCard(
        shape = RoundedCornerShape(22.dp),
        elevated = true,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun SliderPreferenceRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
