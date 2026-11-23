package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.mcp.AgentNoteEntity
import com.example.data.mcp.AgentProfileEntity
import com.example.data.mcp.McpServerEntity
import com.example.data.mcp.McpToolDefinition
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiquidGlassBackdrop
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalStudioColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentsMcpScreen(
    agents: List<AgentProfileEntity>,
    activeAgentId: String,
    enableBuiltInTools: Boolean,
    builtInTools: List<McpToolDefinition>,
    mcpServers: List<McpServerEntity>,
    agentNotes: List<AgentNoteEntity>,
    busyServerId: String?,
    uiNotice: String?,
    onClearNotice: () -> Unit,
    onNavigateBack: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onCreateAgent: (String, String, String, Int, Boolean, Boolean) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onToggleBuiltInTools: (Boolean) -> Unit,
    onAddMcpServer: (String, String, String) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onSyncMcpServer: (String) -> Unit,
    onDeleteMcpServer: (String) -> Unit,
    onDeleteNote: (Long) -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Agents, 1 = MCP
    var agentSearchQuery by remember { mutableStateOf("") }
    var showCreateAgentDialog by remember { mutableStateOf(false) }
    var showAddServerDialog by remember { mutableStateOf(false) }

    val filteredAgents = remember(agents, agentSearchQuery) {
        val q = agentSearchQuery.trim().lowercase()
        if (q.isEmpty()) agents
        else agents.filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
    }

    LaunchedEffect(uiNotice) {
        if (!uiNotice.isNullOrBlank()) {
            snackbarHostState.showSnackbar(uiNotice)
            onClearNotice()
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
                            text = stringResource(R.string.agents_mcp_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("agents_mcp_back_button")
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Tab switcher
                    item(key = "tab_switcher") {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("agents_mcp_tabs")
                        ) {
                            val tabs = listOf(
                                Icons.Rounded.SmartToy to stringResource(R.string.agents_tab),
                                Icons.Rounded.Hub to stringResource(R.string.mcp_tab)
                            )
                            tabs.forEachIndexed { idx, (icon, label) ->
                                SegmentedButton(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    shape = SegmentedButtonDefaults.itemShape(idx, tabs.size),
                                    icon = {}
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text(label, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }

                    if (selectedTab == 0) {
                        // Standard Chat (No Agent) option
                        item(key = "agent_none") {
                            val isStandardActive = activeAgentId.isBlank()
                            LiquidGlassCard(
                                onClick = { onSelectAgent("") },
                                shape = RoundedCornerShape(20.dp),
                                tinted = isStandardActive,
                                elevated = isStandardActive,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("agent_standard_chat_card")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.agents_standard_chat),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.agents_standard_chat_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isStandardActive) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = stringResource(R.string.agents_active_badge),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        // Search agents + Create Custom Agent button
                        item(key = "agents_search_and_create") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = agentSearchQuery,
                                    onValueChange = { agentSearchQuery = it },
                                    singleLine = true,
                                    placeholder = {
                                        Text(stringResource(R.string.agents_search_placeholder, agents.size))
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    trailingIcon = {
                                        if (agentSearchQuery.isNotEmpty()) {
                                            IconButton(onClick = { agentSearchQuery = "" }) {
                                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("agents_search_input")
                                )

                                Button(
                                    onClick = { showCreateAgentDialog = true },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("create_agent_button")
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.agents_create_button))
                                }
                            }
                        }

                        // Built-in & Custom Agents list
                        items(filteredAgents, key = { it.id }) { agent ->
                            val isActive = agent.id == activeAgentId
                            AgentProfileCard(
                                agent = agent,
                                isActive = isActive,
                                onActivate = {
                                    onSelectAgent(agent.id)
                                    onNavigateBack()
                                },
                                onDelete = if (!agent.isBuiltIn) {
                                    { onDeleteAgent(agent.id) }
                                } else null
                            )
                        }

                        // Agent Memory Notes Section
                        item(key = "agent_notes_card") {
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
                                        Icon(
                                            imageVector = Icons.Rounded.Bookmark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.agents_notes_section),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    if (agentNotes.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.agents_notes_empty),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        agentNotes.forEach { note ->
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
                                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        Text(
                                                            text = note.title,
                                                            style = MaterialTheme.typography.titleSmall,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = note.content,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    IconButton(onClick = { onDeleteNote(note.id) }) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.DeleteOutline,
                                                            contentDescription = stringResource(R.string.action_delete),
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // TAB 1: MCP SERVERS & TOOLS
                        item(key = "builtin_mcp_card") {
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
                                            Icon(
                                                imageVector = Icons.Rounded.Build,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.mcp_builtin_section),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Switch(
                                            checked = enableBuiltInTools,
                                            onCheckedChange = onToggleBuiltInTools,
                                            modifier = Modifier.testTag("mcp_builtin_switch")
                                        )
                                    }

                                    Text(
                                        text = stringResource(R.string.mcp_builtin_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        builtInTools.forEach { tool ->
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                                contentColor = MaterialTheme.colorScheme.primary
                                            ) {
                                                Text(
                                                    text = tool.name,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = JetBrainsMonoFontFamily
                                                    ),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Remote MCP Header + Add Button
                        item(key = "remote_mcp_header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.mcp_remote_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { showAddServerDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier.testTag("mcp_add_server_button")
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = stringResource(R.string.mcp_add_server),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        if (mcpServers.isEmpty()) {
                            item(key = "mcp_empty") {
                                LiquidGlassCard(
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.mcp_no_servers),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        } else {
                            items(mcpServers, key = { it.id }) { server ->
                                McpServerCard(
                                    server = server,
                                    isBusy = busyServerId == server.id,
                                    onToggle = { enabled -> onToggleMcpServer(server.id, enabled) },
                                    onSync = { onSyncMcpServer(server.id) },
                                    onDelete = { onDeleteMcpServer(server.id) }
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showCreateAgentDialog) {
        CreateAgentDialog(
            onDismiss = { showCreateAgentDialog = false },
            onCreate = { name, desc, prompt, maxSteps, builtIn, remote ->
                onCreateAgent(name, desc, prompt, maxSteps, builtIn, remote)
                showCreateAgentDialog = false
            }
        )
    }

    if (showAddServerDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddServerDialog = false },
            onAdd = { name, url, auth ->
                onAddMcpServer(name, url, auth)
                showAddServerDialog = false
            }
        )
    }
}

@Composable
private fun AgentProfileCard(
    agent: AgentProfileEntity,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val studioColors = LocalStudioColors.current
    val icon: ImageVector = when (agent.iconKey) {
        "sandbox" -> Icons.Rounded.Terminal
        "research" -> Icons.Rounded.TravelExplore
        "code" -> Icons.Rounded.Code
        "debug" -> Icons.Rounded.BugReport
        "design" -> Icons.Rounded.Palette
        "sql" -> Icons.Rounded.Storage
        "math" -> Icons.Rounded.Calculate
        "security" -> Icons.Rounded.Security
        "tutor" -> Icons.Rounded.School
        "translate" -> Icons.Rounded.Translate
        "writer" -> Icons.Rounded.EditNote
        "memory" -> Icons.Rounded.Psychology
        "image" -> Icons.Rounded.Image
        else -> Icons.Rounded.SmartToy
    }

    LiquidGlassCard(
        onClick = onActivate,
        shape = RoundedCornerShape(20.dp),
        tinted = isActive,
        elevated = isActive,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_card_${agent.id}")
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isActive) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = studioColors.statusOnline.copy(alpha = 0.18f),
                                contentColor = studioColors.statusOnline
                            ) {
                                Text(
                                    text = stringResource(R.string.agents_active_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = studioColors.statusOnline,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = agent.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error
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
                    text = "Max ${agent.maxSteps} steps • ${if (agent.enableBuiltInTools) "Local Tools" else "No Local"}${if (agent.enableMcpServers) " • MCP" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!isActive) {
                    FilledTonalButton(
                        onClick = onActivate,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.agents_activate),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerCard(
    server: McpServerEntity,
    isBusy: Boolean,
    onToggle: (Boolean) -> Unit,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    val tools = remember(server.cachedToolsJson) { McpToolDefinition.decodeList(server.cachedToolsJson) }

    LiquidGlassCard(
        shape = RoundedCornerShape(20.dp),
        elevated = server.isEnabled,
        tinted = server.isEnabled && tools.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mcp_server_${server.id}")
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = server.endpointUrl,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = server.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            if (server.lastStatus.isNotBlank()) {
                Text(
                    text = server.lastStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (server.lastStatus.startsWith("Error")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            if (tools.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tools.take(12).forEach { tool ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSync,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.mcp_sync_tools),
                        style = MaterialTheme.typography.labelMedium
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
}

@Composable
private fun CreateAgentDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Int, Boolean, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var maxSteps by remember { mutableIntStateOf(5) }
    var allowBuiltIn by remember { mutableStateOf(true) }
    var allowRemote by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agents_create_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.agents_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.agents_desc_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.agents_prompt_label)) },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Column {
                    Text(
                        text = "${stringResource(R.string.agents_max_steps_label)}: $maxSteps",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = maxSteps.toFloat(),
                        onValueChange = { maxSteps = it.roundToInt() },
                        valueRange = 1f..8f
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.agents_use_builtin_tools),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = allowBuiltIn, onCheckedChange = { allowBuiltIn = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.agents_use_remote_mcp),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = allowRemote, onCheckedChange = { allowRemote = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description, prompt, maxSteps, allowBuiltIn, allowRemote) },
                enabled = name.isNotBlank() && prompt.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://10.0.2.2:3001/mcp") }
    var auth by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mcp_server_name_label)) },
                    placeholder = { Text("My MCP Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.mcp_server_url_label)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = auth,
                    onValueChange = { auth = it },
                    label = { Text(stringResource(R.string.mcp_server_auth_label)) },
                    placeholder = { Text("Bearer ...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url, auth) },
                enabled = url.trim().startsWith("http://") || url.trim().startsWith("https://")
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
