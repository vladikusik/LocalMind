package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.huggingface.HfModelFile
import com.example.data.huggingface.HfRepoSummary
import com.example.data.local.DownloadedModelEntity
import com.example.data.localmodel.HardwareAcceleratorInfo
import com.example.data.localmodel.LocalHardwareBackend
import com.example.data.localmodel.LocalInferenceEngine
import com.example.data.network.ApiProvider
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiquidGlassBackdrop
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalStudioColors
import com.example.ui.viewmodel.ActiveDownloadState
import com.example.ui.viewmodel.ModelHubUiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelHubScreen(
    hubState: ModelHubUiState,
    downloadedModels: List<DownloadedModelEntity>,
    activeProvider: ApiProvider,
    activeLocalModelId: String,
    localEngine: LocalInferenceEngine,
    localBackend: LocalHardwareBackend,
    hardwareInfo: HardwareAcceleratorInfo,
    huggingFaceToken: String,
    onNavigateBack: () -> Unit,
    onUpdateLocalEngine: (LocalInferenceEngine) -> Unit,
    onUpdateLocalBackend: (LocalHardwareBackend) -> Unit,
    onUpdateSearchQuery: (String) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onToggleRepo: (String) -> Unit,
    onStartDownload: (HfModelFile) -> Unit,
    onCancelDownload: (String) -> Unit,
    onActivateDownloaded: (DownloadedModelEntity) -> Unit,
    onDeleteDownloaded: (DownloadedModelEntity) -> Unit,
    onImportLocalUri: (Uri) -> Unit,
    onUpdateHfToken: (String) -> Unit,
    onClearStatusMessage: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val snackbarHostState = remember { SnackbarHostState() }
    var showTokenField by remember { mutableStateOf(false) }
    var tokenDraft by remember(huggingFaceToken) { mutableStateOf(huggingFaceToken) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportLocalUri(uri)
        }
    }

    LaunchedEffect(hubState.statusMessage) {
        val msg = hubState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onClearStatusMessage()
        }
    }

    LiquidGlassBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.hub_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("hub_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showTokenField = !showTokenField },
                            modifier = Modifier.testTag("hub_toggle_token_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Key,
                                contentDescription = stringResource(R.string.hub_hf_token_label),
                                tint = if (huggingFaceToken.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Optional Hugging Face Token input
                    item(key = "hf_token_panel") {
                        AnimatedVisibility(visible = showTokenField) {
                            LiquidGlassCard(
                                shape = RoundedCornerShape(18.dp),
                                elevated = true,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = tokenDraft,
                                        onValueChange = {
                                            tokenDraft = it
                                            onUpdateHfToken(it)
                                        },
                                        label = { Text(stringResource(R.string.hub_hf_token_label)) },
                                        placeholder = { Text(stringResource(R.string.hub_hf_token_hint)) },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // On-Device Local Engine Selector Card (Auto / llama.cpp / LiteRT-LM / MediaPipe + CPU/GPU)
                    item(key = "local_engine_card") {
                        LiquidGlassCard(
                            shape = RoundedCornerShape(22.dp),
                            elevated = true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Tune,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.settings_section_local_engine),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

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
                                            selected = localEngine == engine,
                                            onClick = { onUpdateLocalEngine(engine) },
                                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                            modifier = Modifier.testTag("hub_engine_${engine.id}")
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    SingleChoiceSegmentedButtonRow(
                                        modifier = Modifier.testTag("hub_backend_segmented")
                                    ) {
                                        val backends = listOf(
                                            LocalHardwareBackend.CPU to stringResource(R.string.backend_cpu),
                                            LocalHardwareBackend.GPU to stringResource(R.string.backend_gpu),
                                            LocalHardwareBackend.NPU to stringResource(R.string.backend_npu)
                                        )
                                        backends.forEachIndexed { idx, (backend, label) ->
                                            SegmentedButton(
                                                selected = localBackend == backend,
                                                onClick = { onUpdateLocalBackend(backend) },
                                                shape = SegmentedButtonDefaults.itemShape(idx, backends.size),
                                                icon = {}
                                            ) {
                                                Text(label, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }

                                val studioColors = LocalStudioColors.current
                                val npuColor = if (hardwareInfo.npuSupported) {
                                    studioColors.statusOnline
                                } else {
                                    studioColors.statusWarning
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${hardwareInfo.npuVendor} · ${hardwareInfo.socName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = npuColor.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = stringResource(
                                                if (hardwareInfo.npuSupported) R.string.npu_status_ready else R.string.npu_status_fallback
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = npuColor,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Downloaded On-Device Models card
                    item(key = "downloaded_models_card") {
                        LiquidGlassCard(
                            shape = RoundedCornerShape(22.dp),
                            elevated = true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Memory,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = stringResource(R.string.hub_downloaded_section),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.testTag("hub_import_local_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.FileOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            text = stringResource(R.string.hub_import_local),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }

                                if (downloadedModels.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.hub_no_downloaded),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    downloadedModels.forEach { model ->
                                        val isActive = activeProvider == ApiProvider.ON_DEVICE &&
                                            activeLocalModelId == model.id
                                        DownloadedModelRow(
                                            model = model,
                                            preferredEngine = localEngine,
                                            isActive = isActive,
                                            onActivate = {
                                                onActivateDownloaded(model)
                                                onNavigateBack()
                                            },
                                            onDelete = { onDeleteDownloaded(model) }
                                        )
                                    }
                                }

                                Text(
                                    text = stringResource(R.string.hub_runtime_tip),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Active downloads section
                    if (hubState.activeDownloads.isNotEmpty()) {
                        items(
                            items = hubState.activeDownloads.values.toList(),
                            key = { "dl_${it.fileKey}" }
                        ) { active ->
                            ActiveDownloadCard(
                                state = active,
                                onCancel = { onCancelDownload(active.fileKey) }
                            )
                        }
                    }

                    // Search bar & curated Hugging Face preset chips
                    item(key = "hf_search_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = hubState.searchQuery,
                                    onValueChange = onUpdateSearchQuery,
                                    placeholder = { Text(stringResource(R.string.hub_search_placeholder)) },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Search, contentDescription = null)
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("hub_search_input")
                                )
                                Button(
                                    onClick = onSubmitSearch,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .height(54.dp)
                                        .testTag("hub_search_button")
                                ) {
                                    Text(stringResource(R.string.hub_search_action))
                                }
                            }

                            val presets = listOf(
                                "gguf" to stringResource(R.string.hub_preset_gguf),
                                "litert" to stringResource(R.string.hub_preset_litert),
                                "localdream" to stringResource(R.string.hub_preset_local_dream),
                                "diffusion" to stringResource(R.string.hub_preset_diffusion),
                                "qwen" to stringResource(R.string.hub_preset_qwen),
                                "gemma" to stringResource(R.string.hub_preset_gemma),
                                "smollm" to stringResource(R.string.hub_preset_smollm),
                                "llama" to stringResource(R.string.hub_preset_llama)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                presets.forEach { (id, label) ->
                                    FilterChip(
                                        selected = hubState.selectedPreset == id,
                                        onClick = { onSelectPreset(id) },
                                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                        modifier = Modifier.testTag("hub_preset_$id")
                                    )
                                }
                            }
                        }
                    }

                    // Loading / error / repository results
                    if (hubState.isSearching) {
                        item(key = "searching_indicator") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (!hubState.searchError.isNullOrBlank()) {
                        item(key = "search_error") {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = hubState.searchError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    } else {
                        items(
                            items = hubState.repositories,
                            key = { it.repoId }
                        ) { repo ->
                            val isExpanded = hubState.expandedRepoId == repo.repoId
                            val files = hubState.repoFiles[repo.repoId]
                            val fileError = hubState.repoFileErrors[repo.repoId]
                            val isLoadingFiles = hubState.loadingFilesRepoId == repo.repoId

                            HfRepositoryCard(
                                repo = repo,
                                isExpanded = isExpanded,
                                isLoadingFiles = isLoadingFiles,
                                files = files,
                                fileError = fileError,
                                downloadedIds = downloadedModels.map { it.id }.toSet(),
                                activeDownloads = hubState.activeDownloads,
                                onToggle = { onToggleRepo(repo.repoId) },
                                onDownloadFile = onStartDownload,
                                onCancelDownload = onCancelDownload
                            )
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DownloadedModelRow(
    model: DownloadedModelEntity,
    preferredEngine: LocalInferenceEngine,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit
) {
    val studioColors = LocalStudioColors.current
    val resolvedEngine = LocalInferenceEngine.resolveForFormat(preferredEngine, model.format)
    val engineBadge = when {
        model.format.lowercase() in setOf("mnn", "dlc", "qnn", "zip") -> stringResource(R.string.hub_badge_local_dream)
        model.format.lowercase() in setOf("safetensors", "ckpt") -> stringResource(R.string.hub_badge_diffusion)
        resolvedEngine == LocalInferenceEngine.LLAMA_CPP -> stringResource(R.string.hub_badge_llama_cpp)
        resolvedEngine == LocalInferenceEngine.LITERT_LM -> stringResource(R.string.hub_badge_litert_lm)
        else -> stringResource(R.string.hub_badge_mediapipe)
    }

    LiquidGlassCard(
        shape = RoundedCornerShape(16.dp),
        tinted = isActive,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = engineBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = formatFileSize(model.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isActive) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = studioColors.statusOnline.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = stringResource(R.string.hub_active_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = studioColors.statusOnline,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (!isActive) {
                FilledTonalButton(
                    onClick = onActivate,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.hub_use_model), style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.hub_active_badge),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    state: ActiveDownloadState,
    onCancel: () -> Unit
) {
    LiquidGlassCard(
        shape = RoundedCornerShape(18.dp),
        tinted = true,
        elevated = true,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (state.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { state.progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            val pct = (state.progressFraction * 100).toInt()
            val speedText = if (state.speedBytesPerSec > 0L) {
                " · ${formatFileSize(state.speedBytesPerSec)}/s"
            } else ""
            Text(
                text = "${formatFileSize(state.bytesDownloaded)} / ${formatFileSize(state.totalBytes)} ($pct%)$speedText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HfRepositoryCard(
    repo: HfRepoSummary,
    isExpanded: Boolean,
    isLoadingFiles: Boolean,
    files: List<HfModelFile>?,
    fileError: String?,
    downloadedIds: Set<String>,
    activeDownloads: Map<String, ActiveDownloadState>,
    onToggle: () -> Unit,
    onDownloadFile: (HfModelFile) -> Unit,
    onCancelDownload: (String) -> Unit
) {
    val studioColors = LocalStudioColors.current
    LiquidGlassCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = repo.repoId,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (repo.isGated) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = "${formatCount(repo.downloads)} downloads · ♥ ${repo.likes}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    when {
                        isLoadingFiles -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        !fileError.isNullOrBlank() -> {
                            Text(
                                text = fileError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        files.isNullOrEmpty() -> {
                            Text(
                                text = stringResource(R.string.hub_no_weight_files),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
                            files.forEach { file ->
                                val fileKey = "${file.repoId}/${file.path}"
                                val isDownloaded = downloadedIds.contains(fileKey)
                                val activeDl = activeDownloads[fileKey]
                                val badgeLabel = when (file.format.lowercase()) {
                                    "mnn", "dlc", "qnn", "zip" -> stringResource(R.string.hub_badge_local_dream)
                                    "safetensors", "ckpt" -> stringResource(R.string.hub_badge_diffusion)
                                    "gguf" -> stringResource(R.string.hub_badge_llama_cpp)
                                    "litertlm" -> stringResource(R.string.hub_badge_litert_lm)
                                    else -> stringResource(R.string.hub_badge_mediapipe)
                                }

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = file.fileName,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = JetBrainsMonoFontFamily
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = formatFileSize(file.sizeBytes),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = studioColors.statusOnline.copy(alpha = 0.16f)
                                                ) {
                                                    Text(
                                                        text = badgeLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = studioColors.statusOnline,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }

                                        when {
                                            isDownloaded -> {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.DownloadDone,
                                                        contentDescription = null,
                                                        tint = studioColors.statusOnline,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.hub_downloaded),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = studioColors.statusOnline
                                                    )
                                                }
                                            }

                                            activeDl != null -> {
                                                OutlinedButton(
                                                    onClick = { onCancelDownload(fileKey) },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = "${(activeDl.progressFraction * 100).toInt()}%",
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }

                                            else -> {
                                                FilledTonalButton(
                                                    onClick = { onDownloadFile(file) },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CloudDownload,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        text = stringResource(R.string.hub_download),
                                                        style = MaterialTheme.typography.labelMedium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000L -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else -> count.toString()
}
