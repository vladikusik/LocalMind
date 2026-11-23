package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.attachments.MessageAttachment
import com.example.data.local.MessageEntity
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlass
import com.example.ui.theme.LocalStudioColors
import com.example.ui.viewmodel.ConnectionUiState
import com.example.voice.VoiceUiState
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun ChatMessageItem(
    message: MessageEntity,
    isStreaming: Boolean,
    isThinkingPhase: Boolean,
    isLastAssistantMessage: Boolean,
    onRegenerate: () -> Unit,
    onRetryError: () -> Unit,
    onOpenSettings: () -> Unit,
    onShare: (String) -> Unit,
    onRunCodeInSandbox: ((language: String, code: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (message.role == "user") {
        val attachments = remember(message.attachmentsJson) {
            MessageAttachment.decodeList(message.attachmentsJson)
        }
        UserMessageBubble(
            content = message.content,
            attachments = attachments,
            modifier = modifier
        )
    } else {
        AssistantMessageBlock(
            message = message,
            isStreaming = isStreaming,
            isThinkingPhase = isThinkingPhase,
            isLastAssistantMessage = isLastAssistantMessage,
            onRegenerate = onRegenerate,
            onRetryError = onRetryError,
            onOpenSettings = onOpenSettings,
            onShare = onShare,
            onRunCodeInSandbox = onRunCodeInSandbox,
            modifier = modifier
        )
    }
}

@Composable
private fun UserMessageBubble(
    content: String,
    attachments: List<MessageAttachment>,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val glass = LocalLiquidGlass.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        LiquidGlassCard(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .padding(start = 40.dp),
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 6.dp,
                bottomStart = 22.dp,
                bottomEnd = 22.dp
            ),
            tinted = true,
            elevated = true
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (attachments.isNotEmpty()) {
                    val images = remember(attachments) { attachments.filter { it.isImage } }
                    val files = remember(attachments) { attachments.filterNot { it.isImage } }

                    if (images.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            images.take(3).forEach { img ->
                                val imgFile = remember(img.localPath) { File(img.localPath) }
                                Box(
                                    modifier = Modifier
                                        .size(if (images.size == 1) 176.dp else 108.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(
                                            1.dp,
                                            glass.specularBorderTop,
                                            RoundedCornerShape(14.dp)
                                        )
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                ) {
                                    AsyncImage(
                                        model = imgFile,
                                        contentDescription = img.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    if (files.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            files.forEach { fileAtt ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, glass.specularBorderMid)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f, fill = false)) {
                                            Text(
                                                text = fileAtt.displayName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = formatAttachmentSize(fileAtt.sizeBytes),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (content.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        if (content.isNotBlank()) {
            Row(
                modifier = Modifier.padding(top = 3.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (copied) stringResource(R.string.action_copied) else stringResource(R.string.action_copy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(content))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AssistantMessageBlock(
    message: MessageEntity,
    isStreaming: Boolean,
    isThinkingPhase: Boolean,
    isLastAssistantMessage: Boolean,
    onRegenerate: () -> Unit,
    onRetryError: () -> Unit,
    onOpenSettings: () -> Unit,
    onShare: (String) -> Unit,
    onRunCodeInSandbox: ((language: String, code: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val glass = LocalLiquidGlass.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    LiquidGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevated = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row with assistant badge, model id, and speed metric
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (message.isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    },
                    border = BorderStroke(1.dp, glass.specularBorderTop),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (message.isError) {
                                Icons.Rounded.ErrorOutline
                            } else {
                                Icons.Rounded.AutoAwesome
                            },
                            contentDescription = stringResource(R.string.role_assistant),
                            tint = if (message.isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = message.modelName?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
                        ?: stringResource(R.string.role_assistant),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                message.tokensPerSecond?.let { tps ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1f tok/s", tps),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Collapsible thinking content if model returned reasoning
            if (!message.thinkingContent.isNullOrBlank()) {
                ThinkingProcessCard(
                    thinkingText = message.thinkingContent,
                    isStillThinking = isStreaming && isThinkingPhase
                )
            }

            // Main response body or error card
            if (message.isError) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MarkdownText(
                            markdown = message.content,
                            textColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = onRetryError,
                                modifier = Modifier.testTag("retry_error_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_retry))
                            }
                            OutlinedButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.testTag("error_open_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.welcome_configure_server))
                            }
                        }
                    }
                }
            } else {
                val contentToShow = message.content
                if (contentToShow.isEmpty() && isStreaming) {
                    StreamingDotsIndicator()
                } else {
                    SelectionContainer {
                        MarkdownText(
                            markdown = if (isStreaming && !isThinkingPhase) {
                                "$contentToShow ▍"
                            } else {
                                contentToShow
                            },
                            onRunCodeInSandbox = if (isStreaming) null else onRunCodeInSandbox
                        )
                    }
                }

                // Action row once generation finishes
                if (!isStreaming && contentToShow.isNotBlank()) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MessageActionChip(
                            icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            label = if (copied) {
                                stringResource(R.string.action_copied)
                            } else {
                                stringResource(R.string.action_copy)
                            },
                            onClick = {
                                clipboard.setText(AnnotatedString(contentToShow))
                                copied = true
                            }
                        )
                        MessageActionChip(
                            icon = Icons.Rounded.Share,
                            label = stringResource(R.string.action_share),
                            onClick = { onShare(contentToShow) }
                        )
                        if (isLastAssistantMessage) {
                            MessageActionChip(
                                icon = Icons.Rounded.Refresh,
                                label = stringResource(R.string.action_regenerate),
                                onClick = onRegenerate
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StreamingDotsIndicator() {
    val transition = rememberInfiniteTransition(label = "streaming_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        )
        Text(
            text = stringResource(R.string.generating_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ChatComposerBar(
    text: String,
    pendingAttachments: List<MessageAttachment>,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    voiceState: VoiceUiState,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleVoice: () -> Unit,
    onAttachPhoto: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlass.current
    var showAttachMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Active voice listening waveform ribbon
        AnimatedVisibility(visible = voiceState.isListening) {
            LiquidGlassCard(
                shape = RoundedCornerShape(18.dp),
                tinted = true,
                elevated = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = voiceState.partialTranscript.ifBlank {
                                stringResource(R.string.composer_listening)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AudioWaveformBars(rmsLevel = voiceState.rmsLevel)
                }
            }
        }

        // Pending photo & document attachment preview tray
        AnimatedVisibility(visible = pendingAttachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(pendingAttachments, key = { it.id }) { att ->
                    if (att.isImage) {
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, glass.specularBorderTop, RoundedCornerShape(16.dp))
                        ) {
                            AsyncImage(
                                model = File(att.localPath),
                                contentDescription = att.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                onClick = { onRemoveAttachment(att.id) },
                                shape = CircleShape,
                                color = Color(0xCC0D121D),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.action_remove_attachment),
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        LiquidGlassCard(
                            shape = RoundedCornerShape(14.dp),
                            elevated = true
                        ) {
                            Row(
                                modifier = Modifier
                                    .widthIn(max = 210.dp)
                                    .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = att.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = formatAttachmentSize(att.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveAttachment(att.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.action_remove_attachment),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Main Liquid Glass floating composer capsule
        LiquidGlassCard(
            shape = RoundedCornerShape(28.dp),
            elevated = true,
            tinted = voiceState.isListening,
            borderWidth = if (voiceState.isListening) 1.5.dp else 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Attach Photo / File button + menu
                Box {
                    IconButton(
                        onClick = { showAttachMenu = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("attach_media_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.action_attach),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_attach_photo)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.PhotoLibrary,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {
                                showAttachMenu = false
                                onAttachPhoto()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_attach_file)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {
                                showAttachMenu = false
                                onAttachFile()
                            }
                        )
                    }
                }

                // Multi-line prompt input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 11.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.composer_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_message_input")
                    )
                }

                // Voice input microphone button
                IconButton(
                    onClick = onToggleVoice,
                    colors = if (voiceState.isListening) {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("voice_input_button")
                ) {
                    Icon(
                        imageVector = if (voiceState.isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = stringResource(
                            if (voiceState.isListening) {
                                R.string.action_voice_stop
                            } else {
                                R.string.action_voice_start
                            }
                        )
                    )
                }

                // Send or Stop button
                if (isGenerating) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("stop_generation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.action_stop)
                        )
                    }
                } else {
                    val canSend = text.isNotBlank() || pendingAttachments.isNotEmpty()
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.action_send)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioWaveformBars(rmsLevel: Float) {
    val factors = remember { listOf(0.45f, 0.9f, 1.0f, 0.7f, 0.5f) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(22.dp)
    ) {
        factors.forEach { factor ->
            val targetHeight = (4f + 18f * (rmsLevel * factor).coerceIn(0.1f, 1f))
            val animatedHeight by animateFloatAsState(
                targetValue = targetHeight,
                animationSpec = tween(100),
                label = "wave_bar"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(animatedHeight.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyChatWelcome(
    providerName: String,
    baseUrl: String,
    connectionState: ConnectionUiState,
    onSelectPrompt: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelHub: () -> Unit,
    onRefreshConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current
    val glass = LocalLiquidGlass.current
    val starterPrompts = listOf(
        Triple(
            Icons.Rounded.Code,
            stringResource(R.string.prompt_chip_code_title),
            stringResource(R.string.prompt_chip_code_body)
        ),
        Triple(
            Icons.Rounded.TableChart,
            stringResource(R.string.prompt_chip_explain_title),
            stringResource(R.string.prompt_chip_explain_body)
        ),
        Triple(
            Icons.Rounded.Hub,
            stringResource(R.string.prompt_chip_debug_title),
            stringResource(R.string.prompt_chip_debug_body)
        ),
        Triple(
            Icons.Rounded.EditNote,
            stringResource(R.string.prompt_chip_write_title),
            stringResource(R.string.prompt_chip_write_body)
        )
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Liquid Glass Hero Card with generated prism artwork
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .height(178.dp),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(glass.specularBorderTop, glass.specularBorderMid, glass.specularBorderBottom)
                )
            ),
            shadowElevation = 6.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.img_studio_banner_1795361001913),
                    contentDescription = stringResource(R.string.studio_tagline),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x220A101C),
                                    Color(0xB80A101C),
                                    Color(0xEB0A101C)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.welcome_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFFF3F7FC)
                    )
                    Text(
                        text = stringResource(R.string.welcome_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD5DFEC),
                        maxLines = 3
                    )
                }
            }
        }

        // Liquid Glass Provider Status & Quick Actions card
        LiquidGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp),
            shape = RoundedCornerShape(22.dp),
            elevated = true
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val (dotColor, statusTitle, statusDetail) = when (connectionState) {
                        is ConnectionUiState.Connected -> Triple(
                            studioColors.statusOnline,
                            "$providerName · ${stringResource(R.string.status_connected)}",
                            if (connectionState.latencyMs > 0L) {
                                "${connectionState.latencyMs} ms · ${connectionState.models.size} model(s)"
                            } else {
                                "${connectionState.models.firstOrNull().orEmpty()} · ${stringResource(R.string.status_on_device_ready)}"
                            }
                        )
                        ConnectionUiState.Checking -> Triple(
                            studioColors.statusWarning,
                            "$providerName · ${stringResource(R.string.status_checking)}",
                            baseUrl
                        )
                        is ConnectionUiState.Offline -> Triple(
                            studioColors.statusOffline,
                            "$providerName · ${stringResource(R.string.status_offline)}",
                            baseUrl
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = statusTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = statusDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onRefreshConnection,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("welcome_refresh_connection")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.model_picker_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpenModelHub,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("welcome_model_hub_button")
                    ) {
                        Text(
                            text = stringResource(R.string.welcome_download_models),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("welcome_configure_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.welcome_configure_server),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Liquid Glass Starter Prompt Grid
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2
        ) {
            starterPrompts.forEachIndexed { index, (icon, title, body) ->
                LiquidGlassCard(
                    onClick = { onSelectPrompt(body) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("starter_prompt_$index"),
                    shape = RoundedCornerShape(20.dp),
                    elevated = false
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

private fun formatAttachmentSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
    }
}
