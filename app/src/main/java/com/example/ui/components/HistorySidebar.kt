package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ConversationSummary
import com.example.data.preferences.ThemeMode
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlass
import com.example.ui.theme.LocalStudioColors
import com.example.ui.viewmodel.ConnectionUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistorySidebarContent(
    conversations: List<ConversationSummary>,
    activeConversationId: Long?,
    searchQuery: String,
    themeMode: ThemeMode,
    connectionState: ConnectionUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectConversation: (Long) -> Unit,
    onNewChat: () -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onTogglePin: (Long, Boolean) -> Unit,
    onExportConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenModelHub: () -> Unit,
    onOpenAgentsMcp: () -> Unit,
    onOpenCodeSandbox: () -> Unit,
    onOpenImageStudio: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current
    val glass = LocalLiquidGlass.current
    var renamingTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deletingTarget by remember { mutableStateOf<ConversationSummary?>(null) }

    val pinnedChats = remember(conversations) { conversations.filter { it.isPinned } }
    val recentChats = remember(conversations) { conversations.filterNot { it.isPinned } }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(glass.glassSurfaceElevated)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Brand Header + New Chat button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_localmind_mark_1795360967472),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.studio_tagline),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onNewChat,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("drawer_new_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.action_new_chat),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // Local search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_chats_placeholder),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("drawer_search_input")
        )

        // Conversation list
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            stringResource(R.string.drawer_empty_title)
                        } else {
                            stringResource(R.string.drawer_no_search_results)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (searchQuery.isBlank()) {
                        Text(
                            text = stringResource(R.string.drawer_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (pinnedChats.isNotEmpty()) {
                    item(key = "section_pinned") {
                        Text(
                            text = stringResource(R.string.drawer_section_pinned),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    items(pinnedChats, key = { "pin_${it.id}" }) { summary ->
                        ConversationRowItem(
                            summary = summary,
                            isSelected = summary.id == activeConversationId,
                            onSelect = { onSelectConversation(summary.id) },
                            onRename = { renamingTarget = summary },
                            onTogglePin = { onTogglePin(summary.id, summary.isPinned) },
                            onExport = { onExportConversation(summary.id) },
                            onDelete = { deletingTarget = summary }
                        )
                    }
                }

                if (recentChats.isNotEmpty()) {
                    item(key = "section_recent") {
                        Text(
                            text = stringResource(R.string.drawer_section_recent),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 6.dp,
                                end = 6.dp,
                                top = if (pinnedChats.isNotEmpty()) 10.dp else 4.dp,
                                bottom = 4.dp
                            )
                        )
                    }
                    items(recentChats, key = { "rec_${it.id}" }) { summary ->
                        ConversationRowItem(
                            summary = summary,
                            isSelected = summary.id == activeConversationId,
                            onSelect = { onSelectConversation(summary.id) },
                            onRename = { renamingTarget = summary },
                            onTogglePin = { onTogglePin(summary.id, summary.isPinned) },
                            onExport = { onExportConversation(summary.id) },
                            onDelete = { deletingTarget = summary }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = glass.specularBorderMid)

        // Quick 4-way Theme Switcher right inside the drawer (System / Light / Dark / AMOLED)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.drawer_theme_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_theme_switcher")
            ) {
                val options = listOf(
                    Triple(ThemeMode.SYSTEM, Icons.Rounded.SettingsBrightness, stringResource(R.string.theme_system)),
                    Triple(ThemeMode.LIGHT, Icons.Rounded.LightMode, stringResource(R.string.theme_light)),
                    Triple(ThemeMode.DARK, Icons.Rounded.DarkMode, stringResource(R.string.theme_dark)),
                    Triple(ThemeMode.AMOLED, Icons.Rounded.Contrast, stringResource(R.string.theme_amoled))
                )
                options.forEach { (mode, icon, label) ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        leadingIcon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}")
                    )
                }
            }
        }

        // Local Uncensored Image Studio shortcut
        LiquidGlassCard(
            onClick = onOpenImageStudio,
            shape = RoundedCornerShape(16.dp),
            tinted = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("drawer_image_studio_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.action_image_studio),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Code Sandbox shortcut
        LiquidGlassCard(
            onClick = onOpenCodeSandbox,
            shape = RoundedCornerShape(16.dp),
            tinted = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("drawer_code_sandbox_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.action_code_sandbox),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Agents & MCP shortcut
        LiquidGlassCard(
            onClick = onOpenAgentsMcp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("drawer_agents_mcp_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.action_agents_mcp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Hugging Face Model Hub shortcut
        LiquidGlassCard(
            onClick = onOpenModelHub,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("drawer_model_hub_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.action_model_hub),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Settings & Connection shortcut
        LiquidGlassCard(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("drawer_settings_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.action_settings),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                val dotColor = when (connectionState) {
                    is ConnectionUiState.Connected -> studioColors.statusOnline
                    ConnectionUiState.Checking -> studioColors.statusWarning
                    is ConnectionUiState.Offline -> studioColors.statusOffline
                }
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }
    }

    // Rename dialog
    renamingTarget?.let { target ->
        var draftTitle by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renamingTarget = null },
            title = { Text(stringResource(R.string.dialog_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dialog_rename_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameConversation(target.id, draftTitle)
                        renamingTarget = null
                    },
                    enabled = draftTitle.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Delete dialog
    deletingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingTarget = null },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteConversation(target.id)
                        deletingTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.action_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun ConversationRowItem(
    summary: ConversationSummary,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    LiquidGlassCard(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        tinted = isSelected,
        elevated = isSelected,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("conversation_item_${summary.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (summary.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = summary.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = summary.lastMessage?.replace("\n", " ")?.trim().orEmpty()
                            .ifBlank { "${summary.messageCount} msg" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatShortTimestamp(summary.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.action_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (summary.isPinned) R.string.action_unpin else R.string.action_pin
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.PushPin, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_export_markdown)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.IosShare, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

private fun formatShortTimestamp(timestampMs: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dayFormatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val now = System.currentTimeMillis()
    val diffHours = (now - timestampMs) / (1000 * 60 * 60)
    return if (diffHours < 20) {
        formatter.format(Date(timestampMs))
    } else {
        dayFormatter.format(Date(timestampMs))
    }
}
