package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.data.imagegen.DiffusionScheduler
import com.example.data.imagegen.GeneratedImageEntity
import com.example.data.imagegen.ImageGenBackendMode
import com.example.data.imagegen.ImageStudioUiState
import com.example.data.imagegen.LocalDreamCatalog
import com.example.data.local.DownloadedModelEntity
import com.example.data.localmodel.HardwareAcceleratorInfo
import com.example.data.localmodel.LocalHardwareBackend
import com.example.data.preferences.AppSettings
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiquidGlassCard
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiffusionModelManagerCard(
    settings: AppSettings,
    studioState: ImageStudioUiState,
    hardwareInfo: HardwareAcceleratorInfo,
    diffusionModels: List<DownloadedModelEntity>,
    onSelectMode: (ImageGenBackendMode) -> Unit,
    onSelectBackend: (LocalHardwareBackend) -> Unit,
    onSelectLocalCheckpoint: (String) -> Unit,
    onPickImportCheckpoint: () -> Unit,
    onOpenDiffusionHub: () -> Unit,
    onSearchModelPreset: (String) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onUpdateServerModel: (String) -> Unit,
    onProbeServer: () -> Unit
) {
    var serverUrlDraft by remember(settings.imageServerUrl) { mutableStateOf(settings.imageServerUrl) }
    var serverModelDraft by remember(settings.imageServerModel) { mutableStateOf(settings.imageServerModel) }
    var showExternalModes by remember(settings.imageGenMode) {
        mutableStateOf(settings.imageGenMode != ImageGenBackendMode.ON_DEVICE_SD_CPP)
    }

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
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.studio_ready_models_hint),
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

                // Built-in Local Dream style Quick-Install Model Manager strip
                Text(
                    text = stringResource(R.string.studio_ready_models_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LocalDreamCatalog.curatedOnDeviceModels.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = { onSearchModelPreset(preset.repoQuery) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = "${preset.title} · ${preset.styleTag}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("studio_preset_${preset.repoQuery.replace('/', '_')}")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickImportCheckpoint,
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
            }

            // Optional collapsible PC server override (tucked away cleanly so On-Device is front and center)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.image_studio_external_toggle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = showExternalModes,
                    onCheckedChange = { enabled ->
                        showExternalModes = enabled
                        if (!enabled) {
                            onSelectMode(ImageGenBackendMode.ON_DEVICE_SD_CPP)
                        } else if (settings.imageGenMode == ImageGenBackendMode.ON_DEVICE_SD_CPP) {
                            onSelectMode(ImageGenBackendMode.LOCAL_A1111_FORGE)
                        }
                    },
                    modifier = Modifier.testTag("image_external_server_switch")
                )
            }

            if (showExternalModes) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        ImageGenBackendMode.ON_DEVICE_SD_CPP to stringResource(R.string.image_studio_mode_on_device),
                        ImageGenBackendMode.LOCAL_A1111_FORGE to stringResource(R.string.image_studio_mode_a1111),
                        ImageGenBackendMode.LOCAL_OPENAI_IMAGES to stringResource(R.string.image_studio_mode_openai)
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.imageGenMode == mode,
                            onClick = { onSelectMode(mode) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.testTag("image_mode_${mode.id}")
                        )
                    }
                }

                if (settings.imageGenMode != ImageGenBackendMode.ON_DEVICE_SD_CPP) {
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiffusionParametersCard(
    settings: AppSettings,
    studioState: ImageStudioUiState,
    onUpdateSize: (Int, Int) -> Unit,
    onUpdateScheduler: (DiffusionScheduler) -> Unit,
    onUpdateSteps: (Int) -> Unit,
    onUpdateCfgScale: (Float) -> Unit,
    onUpdateClipSkip: (Int) -> Unit,
    onUpdateBatchCount: (Int) -> Unit,
    onUpdateHiResFix: (Boolean) -> Unit,
    onUpdateSeed: (String) -> Unit,
    onToggleSeedLock: () -> Unit
) {
    LiquidGlassCard(
        shape = RoundedCornerShape(22.dp),
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

            // Aspect ratios & resolutions (matching Local Dream presets)
            val sizes = listOf(
                384 to 384,
                512 to 512,
                512 to 768,
                768 to 512,
                576 to 1024,
                1024 to 576,
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

            // Scheduler / Sampler selector
            Text(
                text = stringResource(R.string.studio_scheduler_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiffusionScheduler.entries.forEach { sched ->
                    FilterChip(
                        selected = settings.imageScheduler == sched,
                        onClick = { onUpdateScheduler(sched) },
                        label = { Text(sched.displayName, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("studio_scheduler_${sched.id}")
                    )
                }
            }

            // Batch count (1–4) & CLIP Skip (1–2)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.studio_batch_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        (1..4).forEachIndexed { idx, count ->
                            SegmentedButton(
                                selected = settings.imageBatchCount == count,
                                onClick = { onUpdateBatchCount(count) },
                                shape = SegmentedButtonDefaults.itemShape(idx, 4),
                                icon = {}
                            ) {
                                Text("$count", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Column(modifier = Modifier.width(118.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.studio_clip_skip_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(1, 2).forEachIndexed { idx, clip ->
                            SegmentedButton(
                                selected = settings.imageClipSkip == clip,
                                onClick = { onUpdateClipSkip(clip) },
                                shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                icon = {}
                            ) {
                                Text("$clip", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // HiRes Fix 2x toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = stringResource(R.string.studio_hires_fix_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.studio_hires_fix_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.imageHiResFix,
                    onCheckedChange = onUpdateHiResFix,
                    modifier = Modifier.testTag("studio_hires_fix_switch")
                )
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

            // Seed field + Lock toggle + Randomize Dice
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
                    onClick = onToggleSeedLock,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("studio_seed_lock_button")
                ) {
                    Icon(
                        imageVector = if (studioState.seedLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = stringResource(
                            if (studioState.seedLocked) R.string.studio_seed_lock else R.string.studio_seed_unlock
                        )
                    )
                }
                FilledTonalButton(
                    onClick = { onUpdateSeed(Random.nextLong(1L, 999_999_999L).toString()) },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("studio_seed_random_button")
                ) {
                    Icon(Icons.Rounded.Casino, contentDescription = null)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneratedImageLightbox(
    image: GeneratedImageEntity,
    onDismiss: () -> Unit,
    onSaveToGallery: (GeneratedImageEntity) -> Unit,
    onUpscale2x: (GeneratedImageEntity) -> Unit,
    onSendToImg2Img: (GeneratedImageEntity) -> Unit,
    onSendToInpaint: (GeneratedImageEntity) -> Unit,
    onSendToChat: (GeneratedImageEntity) -> Unit,
    onReuseSettings: (GeneratedImageEntity) -> Unit,
    onDeleteImage: (GeneratedImageEntity) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
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
                    IconButton(onClick = onDismiss) {
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
                            onUpscale2x(image)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Hd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.image_studio_action_upscale))
                    }
                    FilledTonalButton(
                        onClick = {
                            onSendToInpaint(image)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Brush, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.image_studio_action_inpaint))
                    }
                    FilledTonalButton(
                        onClick = {
                            onSendToImg2Img(image)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.image_studio_action_img2img))
                    }
                    OutlinedButton(
                        onClick = {
                            onSendToChat(image)
                            onDismiss()
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
                            onDismiss()
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
                            onDismiss()
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
