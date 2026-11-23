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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.imagegen.DiffusionPipelineMode
import com.example.data.imagegen.DiffusionScheduler
import com.example.data.imagegen.GeneratedImageEntity
import com.example.data.imagegen.ImageGenBackendMode
import com.example.data.imagegen.ImageStudioUiState
import com.example.data.imagegen.LocalDreamCatalog
import com.example.data.imagegen.MaskStroke
import com.example.data.imagegen.UpscaleMethod
import com.example.data.local.DownloadedModelEntity
import com.example.data.localmodel.HardwareAcceleratorInfo
import com.example.data.localmodel.LocalHardwareBackend
import com.example.data.preferences.AppSettings
import com.example.ui.components.DiffusionModelManagerCard
import com.example.ui.components.DiffusionParametersCard
import com.example.ui.components.GeneratedImageLightbox
import com.example.ui.components.InpaintMaskCanvas
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiquidGlassBackdrop
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalStudioColors
import java.io.File
import java.util.Locale

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
    onSearchModelPreset: (String) -> Unit,
    onImportCheckpointUri: (Uri) -> Unit,
    onSelectMode: (ImageGenBackendMode) -> Unit,
    onSelectPipeline: (DiffusionPipelineMode) -> Unit,
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
    onAddMaskStroke: (MaskStroke) -> Unit,
    onUndoMaskStroke: () -> Unit,
    onClearMask: () -> Unit,
    onToggleInvertMask: () -> Unit,
    onUpdateMaskFeather: (Int) -> Unit,
    onUpdateUpscaleMethod: (UpscaleMethod) -> Unit,
    onUpdateUpscaleDenoise: (Float) -> Unit,
    onUpdateSize: (Int, Int) -> Unit,
    onUpdateScheduler: (DiffusionScheduler) -> Unit,
    onUpdateSteps: (Int) -> Unit,
    onUpdateCfgScale: (Float) -> Unit,
    onUpdateClipSkip: (Int) -> Unit,
    onUpdateBatchCount: (Int) -> Unit,
    onUpdateHiResFix: (Boolean) -> Unit,
    onUpdateSeed: (String) -> Unit,
    onToggleSeedLock: () -> Unit,
    onGenerate: () -> Unit,
    onStopGenerate: () -> Unit,
    onQuickUpscaleImage: (GeneratedImageEntity) -> Unit,
    onSendToImg2Img: (GeneratedImageEntity) -> Unit,
    onSendToInpaint: (GeneratedImageEntity) -> Unit,
    onSaveToGallery: (GeneratedImageEntity) -> Unit,
    onSendToChat: (GeneratedImageEntity) -> Unit,
    onReuseSettings: (GeneratedImageEntity) -> Unit,
    onDeleteImage: (GeneratedImageEntity) -> Unit,
    onSelectGalleryImage: (Long?) -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val studioColors = LocalStudioColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    var inspectedImage by remember { mutableStateOf<GeneratedImageEntity?>(null) }

    val diffusionModels = remember(downloadedModels) {
        downloadedModels.filter { model ->
            val exists = File(model.localPath).exists()
            val ext = model.format.lowercase()
            exists && (LocalDreamCatalog.isImageModelFormat(ext) || ext == "gguf")
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
                    // 1. Local Dream Core Pipeline Tabs: Txt2Img | Img2Img | Inpaint | Upscale
                    item(key = "pipeline_tabs") {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_pipeline_tabs")
                        ) {
                            val tabs = listOf(
                                DiffusionPipelineMode.TXT2IMG to stringResource(R.string.studio_tab_txt2img),
                                DiffusionPipelineMode.IMG2IMG to stringResource(R.string.studio_tab_img2img),
                                DiffusionPipelineMode.INPAINT to stringResource(R.string.studio_tab_inpaint),
                                DiffusionPipelineMode.UPSCALE to stringResource(R.string.studio_tab_upscale)
                            )
                            tabs.forEachIndexed { idx, (mode, label) ->
                                SegmentedButton(
                                    selected = studioState.pipelineMode == mode,
                                    onClick = { onSelectPipeline(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(idx, tabs.size),
                                    icon = {},
                                    modifier = Modifier.testTag("studio_tab_${mode.id}")
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // 2. Live Preview Stage (with quick Inpaint / Upscale / Save / Send actions)
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
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
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
                                                    LinearProgressIndicator(modifier = Modifier.width(190.dp))
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
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                IconButton(
                                                    onClick = { onQuickUpscaleImage(activePreviewImage) },
                                                    enabled = !studioState.isGenerating,
                                                    modifier = Modifier.testTag("image_preview_upscale_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Hd,
                                                        contentDescription = stringResource(R.string.image_studio_action_upscale),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { onSendToInpaint(activePreviewImage) },
                                                    modifier = Modifier.testTag("image_preview_inpaint_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Brush,
                                                        contentDescription = stringResource(R.string.image_studio_action_inpaint),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
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

                    // 3. Pipeline Workspace Card (Prompt / Img2Img / Interactive Inpaint Mask Canvas / Upscaler)
                    item(key = "workspace_card") {
                        LiquidGlassCard(
                            shape = RoundedCornerShape(22.dp),
                            elevated = true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (studioState.pipelineMode == DiffusionPipelineMode.UPSCALE) {
                                    Text(
                                        text = stringResource(R.string.upscale_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.upscale_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    val upscaleSource = studioState.initImagePath ?: activePreviewImage?.localPath
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            if (!upscaleSource.isNullOrBlank() && File(upscaleSource).exists()) {
                                                AsyncImage(
                                                    model = File(upscaleSource),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                )
                                            }
                                            Text(
                                                text = if (!upscaleSource.isNullOrBlank()) {
                                                    File(upscaleSource).name
                                                } else {
                                                    stringResource(R.string.inpaint_pick_source_prompt)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                photoPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.testTag("upscale_pick_photo_button")
                                        ) {
                                            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text(stringResource(R.string.image_studio_pick_reference), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            UpscaleMethod.SMART_SHARP_2X to stringResource(R.string.upscale_method_sharp_2x),
                                            UpscaleMethod.SMART_SHARP_4X to stringResource(R.string.upscale_method_sharp_4x),
                                            UpscaleMethod.HIRES_DIFFUSION_2X to stringResource(R.string.upscale_method_hires_2x)
                                        ).forEach { (method, label) ->
                                            FilterChip(
                                                selected = studioState.upscaleMethod == method,
                                                onClick = { onUpdateUpscaleMethod(method) },
                                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                                modifier = Modifier.testTag("upscale_method_${method.id}")
                                            )
                                        }
                                    }

                                    if (studioState.upscaleMethod.usesDiffusion) {
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.upscale_denoise_label),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = String.format(Locale.US, "%.2f", studioState.upscaleDenoise),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Slider(
                                                value = studioState.upscaleDenoise,
                                                onValueChange = onUpdateUpscaleDenoise,
                                                valueRange = 0.15f..0.70f
                                            )
                                        }
                                    }
                                } else {
                                    // Txt2Img / Img2Img / Inpaint Prompt & Canvas controls
                                    if (studioState.pipelineMode == DiffusionPipelineMode.INPAINT) {
                                        Text(
                                            text = stringResource(R.string.image_studio_inpaint_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    photoPicker.launch(
                                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                    )
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("inpaint_pick_photo_button")
                                            ) {
                                                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.image_studio_pick_reference))
                                            }
                                            if (!studioState.initImagePath.isNullOrBlank()) {
                                                OutlinedButton(
                                                    onClick = onClearReferencePhoto,
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Text(stringResource(R.string.image_studio_clear_reference))
                                                }
                                            }
                                        }

                                        if (!studioState.initImagePath.isNullOrBlank() && File(studioState.initImagePath).exists()) {
                                            InpaintMaskCanvas(
                                                imagePath = studioState.initImagePath,
                                                strokes = studioState.maskStrokes,
                                                invertMask = studioState.invertMask,
                                                featherRadiusPx = studioState.maskFeatherPx,
                                                onAddStroke = onAddMaskStroke,
                                                onUndoStroke = onUndoMaskStroke,
                                                onClearMask = onClearMask,
                                                onToggleInvertMask = onToggleInvertMask,
                                                onUpdateFeather = onUpdateMaskFeather
                                            )
                                        } else {
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.inpaint_pick_source_prompt),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(14.dp)
                                                )
                                            }
                                        }
                                    }

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

                                    // Negative prompt presets
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        LocalDreamCatalog.negativePresets.forEach { preset ->
                                            FilterChip(
                                                selected = studioState.negativePrompt.trim() == preset.prompt,
                                                onClick = { onUpdateNegativePrompt(preset.prompt) },
                                                label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }

                                    // Img2Img reference photo controls
                                    if (studioState.pipelineMode == DiffusionPipelineMode.IMG2IMG) {
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
                                                            .size(44.dp)
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
                                    }

                                    // Denoising strength slider for Img2Img & Inpaint
                                    if ((studioState.pipelineMode == DiffusionPipelineMode.IMG2IMG ||
                                            studioState.pipelineMode == DiffusionPipelineMode.INPAINT) &&
                                        !studioState.initImagePath.isNullOrBlank()
                                    ) {
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
                                }

                                if (!studioState.lastError.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
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

                                // Primary Generate / Inpaint / Upscale / Stop Action Button
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
                                    val canRun = when (studioState.pipelineMode) {
                                        DiffusionPipelineMode.UPSCALE ->
                                            !studioState.initImagePath.isNullOrBlank() || activePreviewImage != null
                                        else -> studioState.prompt.isNotBlank()
                                    }
                                    val buttonText = when (studioState.pipelineMode) {
                                        DiffusionPipelineMode.UPSCALE -> stringResource(R.string.upscale_action_button)
                                        DiffusionPipelineMode.INPAINT -> stringResource(R.string.image_studio_inpaint_generate)
                                        else -> if (settings.imageBatchCount > 1) {
                                            stringResource(R.string.image_studio_generate_batch, settings.imageBatchCount)
                                        } else {
                                            stringResource(R.string.image_studio_generate)
                                        }
                                    }
                                    Button(
                                        onClick = onGenerate,
                                        enabled = canRun,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                            .testTag("image_generate_button")
                                    ) {
                                        Icon(
                                            imageVector = when (studioState.pipelineMode) {
                                                DiffusionPipelineMode.UPSCALE -> Icons.Rounded.Hd
                                                DiffusionPipelineMode.INPAINT -> Icons.Rounded.Brush
                                                else -> Icons.Rounded.AutoAwesome
                                            },
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = buttonText,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. Built-in Model Manager & Hardware Card
                    item(key = "engine_checkpoint_card") {
                        DiffusionModelManagerCard(
                            settings = settings,
                            studioState = studioState,
                            hardwareInfo = hardwareInfo,
                            diffusionModels = diffusionModels,
                            onSelectMode = onSelectMode,
                            onSelectBackend = onSelectBackend,
                            onSelectLocalCheckpoint = onSelectLocalCheckpoint,
                            onPickImportCheckpoint = { checkpointPicker.launch(arrayOf("*/*")) },
                            onOpenDiffusionHub = onOpenDiffusionHub,
                            onSearchModelPreset = onSearchModelPreset,
                            onUpdateServerUrl = onUpdateServerUrl,
                            onUpdateServerModel = onUpdateServerModel,
                            onProbeServer = onProbeServer
                        )
                    }

                    // 5. Full Local Dream Parameters Card (Resolution, Scheduler, Batch, CLIP Skip, HiRes Fix, Steps, CFG, Seed Lock)
                    if (studioState.pipelineMode != DiffusionPipelineMode.UPSCALE) {
                        item(key = "parameters_card") {
                            DiffusionParametersCard(
                                settings = settings,
                                studioState = studioState,
                                onUpdateSize = onUpdateSize,
                                onUpdateScheduler = onUpdateScheduler,
                                onUpdateSteps = onUpdateSteps,
                                onUpdateCfgScale = onUpdateCfgScale,
                                onUpdateClipSkip = onUpdateClipSkip,
                                onUpdateBatchCount = onUpdateBatchCount,
                                onUpdateHiResFix = onUpdateHiResFix,
                                onUpdateSeed = onUpdateSeed,
                                onToggleSeedLock = onToggleSeedLock
                            )
                        }
                    }

                    // 6. Local Generated Images Gallery
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

    // Full-screen Image Inspection Lightbox with Upscale / Img2Img / Inpaint / Chat actions
    inspectedImage?.let { image ->
        GeneratedImageLightbox(
            image = image,
            onDismiss = { inspectedImage = null },
            onSaveToGallery = onSaveToGallery,
            onUpscale2x = onQuickUpscaleImage,
            onSendToImg2Img = onSendToImg2Img,
            onSendToInpaint = onSendToInpaint,
            onSendToChat = {
                onSendToChat(it)
                onNavigateBack()
            },
            onReuseSettings = onReuseSettings,
            onDeleteImage = onDeleteImage
        )
    }
}
