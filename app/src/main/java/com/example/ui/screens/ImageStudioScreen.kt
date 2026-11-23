package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.R
import com.example.data.imagegen.GeneratedImageEntity
import com.example.data.imagegen.ImageGenBackendMode
import com.example.data.imagegen.ImageStudioUiState
import com.example.data.local.DownloadedModelEntity
import com.example.data.localmodel.HardwareAcceleratorInfo
import com.example.data.localmodel.LocalHardwareBackend
import com.example.data.preferences.AppSettings
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiquidGlassBackdrop
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalStudioColors
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageStudioScreen(
    settings: AppSettings,
    studioState: ImageStudioUiState,
    hardwareInfo: HardwareAcceleratorInfo,
    downloadedModels: List<DownloadedModelEntity>,
    generatedImages: List<GeneratedImageEntity>,
    uiNotice: String?,
    onClearNotice: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenDiffusionHub: () -> Unit,
    onImportCheckpointUri: (Uri) -> Unit,
    onSelectMode: (ImageGenBackendMode) -> Unit,
    onSelectBackend: (LocalHardwareBackend) -> Unit,
    onSelectLocalCheckpoint: (String) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onUpdateServerModel: (String) -> Unit,
    onProbeServer: () -> Unit,
    onUpdatePrompt: (String) -> Unit,
    onUpdateNegativePrompt: (String) -> Unit,
    onEnhancePrompt: () -> Unit,
    onPickReferencePhoto: (Uri) -> Unit,
    onClearReferencePhoto: () -> Unit,
    onUpdateStrength: (Float) -> Unit,
    onUpdateSize: (Int, Int) -> Unit,
    onUpdateSteps: (Int) -> Unit,
    onUpdateCfgScale: (Float) -> Unit,
    onUpdateSeed: (String) -> Unit,
    onGenerate: () -> Unit,
    onStopGenerate: () -> Unit,
    onSaveToGallery: (GeneratedImageEntity) -> Unit,
    onSendToChat: (GeneratedImageEntity) -> Unit,
    onReuseSettings: (GeneratedImageEntity) -> Unit,
    onDeleteImage: (GeneratedImageEntity) -> Unit,
    onSelectGalleryImage: (Long?) -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val studioColors = LocalStudioColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    var serverUrlDraft by remember(settings.imageServerUrl) { mutableStateOf(settings.imageServerUrl) }
    var serverModelDraft by remember(settings.imageServerModel) { mutableStateOf(settings.imageServerModel) }
    var inspectedImage by remember { mutableStateOf<GeneratedImageEntity?>(null) }

    val diffusionModels = remember(downloadedModels) {
        downloadedModels.filter { model ->
            val exists = File(model.localPath).exists()
            val ext = model.format.lowercase()
            exists && (ext == "safetensors" || ext == "ckpt" || ext == "gguf")
        }
    }

    val checkpointPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportCheckpointUri(uri)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickReferencePhoto(uri)
    }

    LaunchedEffect(uiNotice) {
        if (!uiNotice.isNullOrBlank()) {
            snackbarHostState.showSnackbar(uiNotice)
            onClearNotice()
        }
    }

    val activePreviewImage = remember(generatedImages, studioState.selectedGalleryImageId) {
        generatedImages.firstOrNull { it.id == studioState.selectedGalleryImageId }
            ?: generatedImages.firstOrNull()
    }

    LiquidGlassBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.image_studio_title),
                                style = MaterialTheme.typography.titleLarge,
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
                                    color = studioColors.statusOnline.copy(alpha = 0.16f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.LockOpen,
                                            contentDescription = null,
                                            tint = studioColors.statusOnline,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.image_studio_uncensored_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = studioColors.statusOnline
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "${settings.localBackend.id.uppercase()} · ${hardwareInfo.cpuCores}T",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("image_studio_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onOpenDiffusionHub,
                            modifier = Modifier.testTag("image_studio_hf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = stringResource(R.string.image_studio_browse_hf)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
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
                        .widthIn(max = 820.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Live Preview Stage (shows latest generated image or generating spinner)
                    if (activePreviewImage != null || studioState.isGenerating) {
                        item(key = "preview_stage") {
                            LiquidGlassCard(
                                shape = RoundedCornerShape(22.dp),
                                elevated = true,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(
                                                if (activePreviewImage != null && activePreviewImage.height > 0) {
                                                    (activePreviewImage.width.toFloat() / activePreviewImage.height.toFloat())
                                                        .coerceIn(0.65f, 1.6f)
                                                } else {
                                                    1f
                                                }
                                            )
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                            .clickable(enabled = activePreviewImage != null) {
                                                inspectedImage = activePreviewImage
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (activePreviewImage != null) {
                                            AsyncImage(
                                                model = File(activePreviewImage.localPath),
                                                contentDescription = activePreviewImage.prompt,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        if (studioState.isGenerating) {
                                            Surface(
                                                shape = RoundedCornerShape(16.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                                modifier = Modifier.padding(16.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    CircularProgressIndicator(strokeWidth = 3.dp)
                                                    Text(
                                                        text = studioState.progressLabel
                                                            ?: stringResource(R.string.image_studio_generating),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    LinearProgressIndicator(modifier = Modifier.width(180.dp))
                                                }
                                            }
                                        }
                                    }

                                    if (activePreviewImage != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${activePreviewImage.width}×${activePreviewImage.height} · Seed ${activePreviewImage.seed} · ${activePreviewImage.durationMs / 1000.0}s",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                IconButton(
                                                    onClick = { onSaveToGallery(activePreviewImage) },
                                                    modifier = Modifier.testTag("image_preview_save_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Download,
                                                        contentDescription = stringResource(R.string.image_studio_save_gallery),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        onSendToChat(activePreviewImage)
                                                        onNavigateBack()
                                                    },
                                                    modifier = Modifier.testTag("image_preview_send_chat_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Rounded.Send,
                                                        contentDescription = stringResource(R.string.image_studio_send_chat),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Engine & Hardware / Checkpoint Card
                    item(key = "engine_checkpoint_card") {
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = hardwareInfo.npuVendor,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = hardwareInfo.npuDetail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Mode selector chips
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val modes = listOf(
                                        ImageGenBackendMode.ON_DEVICE_SD_CPP to stringResource(R.string.image_studio_mode_on_device),
                                        ImageGenBackendMode.LOCAL_A1111_FORGE to stringResource(R.string.image_studio_mode_a1111),
                                        ImageGenBackendMode.LOCAL_OPENAI_IMAGES to stringResource(R.string.image_studio_mode_openai)
                                    )
                                    modes.forEach { (mode, label) ->
                                        FilterChip(
                                            selected = settings.imageGenMode == mode,
                                            onClick = { onSelectMode(mode) },
                                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                            modifier = Modifier.testTag("image_mode_${mode.id}")
                                        )
                                    }
                                }

                                // Compute backend selector (CPU / GPU / NPU)
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
                                        modifier = Modifier.testTag("image_studio_backend_segmented")
                                    ) {
                                        val backends = listOf(
                                            LocalHardwareBackend.CPU to stringResource(R.string.backend_cpu),
                                            LocalHardwareBackend.GPU to stringResource(R.string.backend_gpu),
                                            LocalHardwareBackend.NPU to stringResource(R.string.backend_npu)
                                        )
                                        backends.forEachIndexed { idx, (backend, label) ->
                                            SegmentedButton(
                                                selected = settings.localBackend == backend,
                                                onClick = { onSelectBackend(backend) },
                                                shape = SegmentedButtonDefaults.itemShape(idx, backends.size),
                                                icon = {}
                                            ) {
                                                Text(label, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }

                                if (settings.imageGenMode == ImageGenBackendMode.ON_DEVICE_SD_CPP) {
                                    Text(
                                        text = stringResource(R.string.image_studio_checkpoint_label),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (diffusionModels.isEmpty()) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = stringResource(R.string.image_studio_no_checkpoint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }
                                    } else {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            diffusionModels.forEach { model ->
                                                val selected = settings.activeImageModelId == model.id ||
                                                    (settings.activeImageModelId.isBlank() && diffusionModels.first().id == model.id)
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = { onSelectLocalCheckpoint(model.id) },
                                                    leadingIcon = if (selected) {
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
                                                            text = "${model.displayName} (.${model.format})",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { checkpointPicker.launch(arrayOf("*/*")) },
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("image_import_checkpoint_button")
                                        ) {
                                            Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.image_studio_import_checkpoint),
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = onOpenDiffusionHub,
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("image_open_hf_diffusion_button")
                                        ) {
                                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.image_studio_browse_hf),
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = serverUrlDraft,
                                        onValueChange = {
                                            serverUrlDraft = it
                                            onUpdateServerUrl(it)
                                        },
                                        label = { Text(stringResource(R.string.image_studio_server_url)) },
                                        placeholder = { Text(settings.imageGenMode.defaultUrl) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("image_server_url_input")
                                    )

                                    val defaultPort = if (settings.imageGenMode == ImageGenBackendMode.LOCAL_A1111_FORGE) "7860" else "1234/v1"
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            stringResource(R.string.preset_emulator) to "http://10.0.2.2:$defaultPort",
                                            stringResource(R.string.preset_localhost) to "http://127.0.0.1:$defaultPort",
                                            stringResource(R.string.preset_lan) to "http://192.168.1.100:$defaultPort"
                                        ).forEach { (label, presetUrl) ->
                                            FilterChip(
                                                selected = serverUrlDraft.trim() == presetUrl,
                                                onClick = {
                                                    serverUrlDraft = presetUrl
                                                    onUpdateServerUrl(presetUrl)
                                                },
                                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = serverModelDraft,
                                        onValueChange = {
                                            serverModelDraft = it
                                            onUpdateServerModel(it)
                                        },
                                        label = { Text(stringResource(R.string.image_studio_server_model)) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    FilledTonalButton(
                                        onClick = onProbeServer,
                                        enabled = !studioState.isProbingServer,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("image_probe_server_button")
                                    ) {
                                        if (studioState.isProbingServer) {
                                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                        } else {
                                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.image_studio_probe_server))
                                    }

                                    if (!studioState.serverStatus.isNullOrBlank()) {
                                        Text(
                                            text = studioState.serverStatus,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (studioState.serverModels.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            studioState.serverModels.take(8).forEach { m ->
                                                FilterChip(
                                                    selected = serverModelDraft == m,
                                                    onClick = {
                                                        serverModelDraft = m
                                                        onUpdateServerModel(m)
                                                    },
                                                    label = {
                                                        Text(
                                                            text = m.substringAfterLast('/'),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Prompt & Negative Prompt Card
                    item(key = "prompt_card") {
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
                                    Text(
                                        text = stringResource(R.string.image_studio_prompt_label),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    FilledTonalButton(
                                        onClick = onEnhancePrompt,
                                        enabled = studioState.prompt.isNotBlank() && !studioState.isEnhancingPrompt,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.testTag("image_enhance_prompt_button")
                                    ) {
                                        if (studioState.isEnhancingPrompt) {
                                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                        } else {
                                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                                        }
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            text = stringResource(R.string.image_studio_enhance_prompt),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = studioState.prompt,
                                    onValueChange = onUpdatePrompt,
                                    placeholder = { Text(stringResource(R.string.image_studio_prompt_placeholder)) },
                                    minLines = 3,
                                    maxLines = 6,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("image_prompt_input")
                                )

                                // Quick style booster chips
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(
                                        "Photorealistic 8K" to "photorealistic, 8k uhd, sharp focus, 85mm lens, natural lighting",
                                        "Cyberpunk" to "cyberpunk neon rain, volumetric fog, reflections, cinematic sci-fi",
                                        "Anime" to "masterpiece anime key visual, cel shaded, vibrant lighting, ultra detailed",
                                        "Dark Fantasy" to "dark fantasy oil painting, dramatic chiaroscuro, intricate armor, epic scale"
                                    ).forEach { (chipLabel, suffix) ->
                                        FilterChip(
                                            selected = studioState.prompt.contains(suffix.take(14), ignoreCase = true),
                                            onClick = {
                                                val base = studioState.prompt.trim()
                                                onUpdatePrompt(if (base.isEmpty()) suffix else "$base, $suffix")
                                            },
                                            label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = studioState.negativePrompt,
                                    onValueChange = onUpdateNegativePrompt,
                                    label = { Text(stringResource(R.string.image_studio_negative_label)) },
                                    minLines = 1,
                                    maxLines = 3,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("image_negative_prompt_input")
                                )

                                // Optional Img2Img reference photo
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
                                        if (!studioState.initImagePath.isNullOrBlank()) {
                                            AsyncImage(
                                                model = File(studioState.initImagePath),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                            )
                                        }
                                        Text(
                                            text = stringResource(R.string.image_studio_img2img_title),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (!studioState.initImagePath.isNullOrBlank()) {
                                            OutlinedButton(
                                                onClick = onClearReferencePhoto,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text(stringResource(R.string.image_studio_clear_reference), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                photoPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.testTag("image_pick_ref_button")
                                        ) {
                                            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.image_studio_pick_reference), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                if (!studioState.initImagePath.isNullOrBlank()) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = stringResource(R.string.image_studio_strength_label),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = String.format(Locale.US, "%.2f", studioState.img2imgStrength),
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = studioState.img2imgStrength,
                                            onValueChange = onUpdateStrength,
                                            valueRange = 0.1f..0.95f
                                        )
                                    }
                                }

                                if (!studioState.lastError.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = studioState.lastError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }

                                if (studioState.isGenerating) {
                                    Button(
                                        onClick = onStopGenerate,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                            .testTag("image_stop_button")
                                    ) {
                                        Icon(Icons.Rounded.Stop, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.action_stop))
                                    }
                                } else {
                                    Button(
                                        onClick = onGenerate,
                                        enabled = studioState.prompt.isNotBlank(),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                            .testTag("image_generate_button")
                                    ) {
                                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.image_studio_generate),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. Diffusion Parameters Card (Resolution, Steps, CFG, Seed)
                    item(key = "parameters_card") {
                        LiquidGlassCard(
                            shape = RoundedCornerShape(22.dp),
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
                                    Icon(
                                        imageVector = Icons.Rounded.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.image_studio_resolution_label),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                val sizes = listOf(
                                    256 to 256,
                                    384 to 384,
                                    512 to 512,
                                    512 to 768,
                                    768 to 512,
                                    768 to 768,
                                    1024 to 1024
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    sizes.forEach { (w, h) ->
                                        FilterChip(
                                            selected = settings.imageWidth == w && settings.imageHeight == h,
                                            onClick = { onUpdateSize(w, h) },
                                            label = {
                                                Text(
                                                    text = "${w}×${h}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                                                )
                                            }
                                        )
                                    }
                                }

                                // Steps slider
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.image_studio_steps_label),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${settings.imageSteps}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Slider(
                                        value = settings.imageSteps.toFloat(),
                                        onValueChange = { onUpdateSteps(it.roundToInt()) },
                                        valueRange = 1f..50f
                                    )
                                }

                                // CFG slider
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.image_studio_cfg_label),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%.1f", settings.imageCfgScale),
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Slider(
                                        value = settings.imageCfgScale,
                                        onValueChange = { onUpdateCfgScale((it * 2f).roundToInt() / 2f) },
                                        valueRange = 1.0f..15.0f
                                    )
                                }

                                // Seed field + random dice
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = studioState.seedInput,
                                        onValueChange = onUpdateSeed,
                                        label = { Text(stringResource(R.string.image_studio_seed_label)) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilledTonalButton(
                                        onClick = { onUpdateSeed(Random.nextLong(1L, 999_999_999L).toString()) },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.height(54.dp)
                                    ) {
                                        Icon(Icons.Rounded.Casino, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }

                    // 5. Local Generated Images Gallery
                    item(key = "gallery_section") {
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PhotoLibrary,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.image_studio_gallery_title, generatedImages.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (generatedImages.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.image_studio_gallery_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(generatedImages, key = { it.id }) { img ->
                                            val isSelected = activePreviewImage?.id == img.id
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                border = if (isSelected) {
                                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                                } else {
                                                    null
                                                },
                                                modifier = Modifier
                                                    .size(104.dp)
                                                    .clickable {
                                                        onSelectGalleryImage(img.id)
                                                        inspectedImage = img
                                                    }
                                                    .testTag("gallery_image_${img.id}")
                                            ) {
                                                AsyncImage(
                                                    model = File(img.localPath),
                                                    contentDescription = img.prompt,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    // Full-screen Image Inspection Lightbox
    inspectedImage?.let { image ->
        Dialog(
            onDismissRequest = { inspectedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 640.dp)
                    .padding(vertical = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = image.modelName.ifBlank { image.engineLabel },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { inspectedImage = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }

                    AsyncImage(
                        model = File(image.localPath),
                        contentDescription = image.prompt,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    )

                    Text(
                        text = image.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "${image.width}×${image.height} · ${image.steps} steps · CFG ${image.cfgScale} · Seed ${image.seed} · ${image.engineLabel}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { onSaveToGallery(image) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.image_studio_save_gallery))
                        }
                        FilledTonalButton(
                            onClick = {
                                onSendToChat(image)
                                inspectedImage = null
                                onNavigateBack()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.image_studio_send_chat))
                        }
                        OutlinedButton(
                            onClick = {
                                onReuseSettings(image)
                                inspectedImage = null
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.image_studio_reuse_settings))
                        }
                        OutlinedButton(
                            onClick = {
                                onDeleteImage(image)
                                inspectedImage = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }
}
