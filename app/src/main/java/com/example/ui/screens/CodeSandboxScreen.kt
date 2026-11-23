package com.example.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.sandbox.HtmlSandboxBuilder
import com.example.data.sandbox.SandboxLanguage
import com.example.data.sandbox.SandboxLogEntry
import com.example.data.sandbox.SandboxLogLevel
import com.example.data.sandbox.SandboxUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiquidGlassBackdrop
import com.example.ui.theme.LiquidGlassCard
import com.example.ui.theme.LocalStudioColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeSandboxScreen(
    sandboxState: SandboxUiState,
    activeModelLabel: String,
    onNavigateBack: () -> Unit,
    onSelectLanguage: (SandboxLanguage) -> Unit,
    onUpdateCode: (String) -> Unit,
    onRunCode: () -> Unit,
    onResetTemplate: () -> Unit,
    onClearLogs: () -> Unit,
    onAppendLog: (SandboxLogLevel, String) -> Unit,
    onWebRunDone: (Long) -> Unit,
    onUpdateAiPrompt: (String) -> Unit,
    onGenerateWithAi: (String?) -> Unit,
    onStopAi: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // Tabs for compact screens: 0 = Code, 1 = Preview (HTML) / Console, 2 = Console (for HTML)
    var selectedTab by remember { mutableIntStateOf(0) }

    // Auto-switch to Preview or Console when a fresh run finishes or code is launched from chat
    var lastObservedRun by remember { mutableIntStateOf(sandboxState.runCounter) }
    LaunchedEffect(sandboxState.runCounter, sandboxState.language) {
        if (sandboxState.runCounter != lastObservedRun) {
            lastObservedRun = sandboxState.runCounter
            selectedTab = if (sandboxState.language == SandboxLanguage.HTML_JS) 1 else 1
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    val errorCount = remember(sandboxState.consoleLogs) {
        sandboxState.consoleLogs.count { it.level == SandboxLogLevel.ERROR }
    }

    LiquidGlassBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("sandbox_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.sandbox_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = activeModelLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(sandboxState.code))
                                copied = true
                            },
                            modifier = Modifier.testTag("sandbox_copy_button")
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(R.string.action_copy_code)
                            )
                        }
                        IconButton(
                            onClick = onResetTemplate,
                            modifier = Modifier.testTag("sandbox_reset_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = stringResource(R.string.sandbox_reset)
                            )
                        }
                        Button(
                            onClick = onRunCode,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("sandbox_run_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.sandbox_run),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                )
            },
            bottomBar = {
                SandboxAiComposerBar(
                    language = sandboxState.language,
                    prompt = sandboxState.aiPrompt,
                    isGenerating = sandboxState.isAiGenerating,
                    statusText = sandboxState.aiStatusText,
                    hasErrors = errorCount > 0,
                    onPromptChange = onUpdateAiPrompt,
                    onGenerate = { onGenerateWithAi(null) },
                    onQuickPrompt = { onGenerateWithAi(it) },
                    onFixErrors = {
                        onGenerateWithAi("Fix all runtime/syntax errors in the current code and make it run cleanly.")
                    },
                    onStop = onStopAi
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Language selector strip
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(SandboxLanguage.entries, key = { it.id }) { lang ->
                        FilterChip(
                            selected = sandboxState.language == lang,
                            onClick = { onSelectLanguage(lang) },
                            label = {
                                Text(
                                    text = lang.displayName,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.testTag("sandbox_lang_${lang.id}")
                        )
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val isWide = maxWidth >= 820.dp

                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CodeEditorPane(
                                language = sandboxState.language,
                                code = sandboxState.code,
                                isAiWriting = sandboxState.isAiGenerating,
                                onCodeChange = onUpdateCode,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (sandboxState.language.usesWebView) {
                                    WebSandboxPreviewPane(
                                        language = sandboxState.language,
                                        code = sandboxState.code,
                                        runCounter = sandboxState.runCounter,
                                        onLog = onAppendLog,
                                        onDone = onWebRunDone,
                                        modifier = Modifier
                                            .weight(if (sandboxState.language == SandboxLanguage.HTML_JS) 1.5f else 0.45f)
                                            .fillMaxWidth()
                                    )
                                }
                                ConsoleOutputPane(
                                    logs = sandboxState.consoleLogs,
                                    durationMs = sandboxState.lastRunDurationMs,
                                    onClear = onClearLogs,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        val isHtml = sandboxState.language == SandboxLanguage.HTML_JS
                        val tabLabels = if (isHtml) {
                            listOf(
                                Icons.Rounded.Code to stringResource(R.string.sandbox_tab_code),
                                Icons.Rounded.Visibility to stringResource(R.string.sandbox_tab_preview),
                                Icons.Rounded.Terminal to "${stringResource(R.string.sandbox_tab_console)} (${sandboxState.consoleLogs.size})"
                            )
                        } else {
                            listOf(
                                Icons.Rounded.Code to stringResource(R.string.sandbox_tab_code),
                                Icons.Rounded.Terminal to "${stringResource(R.string.sandbox_tab_console)} (${sandboxState.consoleLogs.size})"
                            )
                        }
                        val safeTab = selectedTab.coerceIn(0, tabLabels.lastIndex)

                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sandbox_view_tabs")
                            ) {
                                tabLabels.forEachIndexed { idx, (icon, label) ->
                                    SegmentedButton(
                                        selected = safeTab == idx,
                                        onClick = { selectedTab = idx },
                                        shape = SegmentedButtonDefaults.itemShape(idx, tabLabels.size),
                                        icon = {}
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
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

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                // Keep WebView mounted for JS console execution even when viewing Console tab
                                if (sandboxState.language == SandboxLanguage.JAVASCRIPT) {
                                    Box(modifier = Modifier.size(1.dp)) {
                                        WebSandboxPreviewPane(
                                            language = sandboxState.language,
                                            code = sandboxState.code,
                                            runCounter = sandboxState.runCounter,
                                            onLog = onAppendLog,
                                            onDone = onWebRunDone,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                when {
                                    safeTab == 0 -> CodeEditorPane(
                                        language = sandboxState.language,
                                        code = sandboxState.code,
                                        isAiWriting = sandboxState.isAiGenerating,
                                        onCodeChange = onUpdateCode,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    isHtml && safeTab == 1 -> WebSandboxPreviewPane(
                                        language = sandboxState.language,
                                        code = sandboxState.code,
                                        runCounter = sandboxState.runCounter,
                                        onLog = onAppendLog,
                                        onDone = onWebRunDone,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    else -> ConsoleOutputPane(
                                        logs = sandboxState.consoleLogs,
                                        durationMs = sandboxState.lastRunDurationMs,
                                        onClear = onClearLogs,
                                        modifier = Modifier.fillMaxSize()
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

@Composable
private fun CodeEditorPane(
    language: SandboxLanguage,
    code: String,
    isAiWriting: Boolean,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current
    val lineCount = remember(code) { code.lines().size }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = studioColors.codeBackground,
        contentColor = studioColors.codeText,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(studioColors.codeHeader)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "main.${language.fileExtension}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = studioColors.codeText
                    )
                    if (isAiWriting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = "$lineCount lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = studioColors.codeText.copy(alpha = 0.65f)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                BasicTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = studioColors.codeText
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sandbox_code_editor")
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebSandboxPreviewPane(
    language: SandboxLanguage,
    code: String,
    runCounter: Int,
    onLog: (SandboxLogLevel, String) -> Unit,
    onDone: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val htmlDoc = remember(language, code, runCounter) {
        HtmlSandboxBuilder.buildHtmlDocument(language, code)
    }

    LiquidGlassCard(
        shape = RoundedCornerShape(20.dp),
        elevated = true,
        modifier = modifier
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp)),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#070B12"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = true
                    }
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun postLog(level: String, message: String) {
                                val parsedLevel = when (level.uppercase()) {
                                    "WARN" -> SandboxLogLevel.WARN
                                    "ERROR" -> SandboxLogLevel.ERROR
                                    else -> SandboxLogLevel.INFO
                                }
                                mainHandler.post { onLog(parsedLevel, message) }
                            }

                            @JavascriptInterface
                            fun postDone(elapsedMs: Long) {
                                mainHandler.post { onDone(elapsedMs) }
                            }
                        },
                        "AndroidSandbox"
                    )
                }
            },
            update = { webView ->
                val tagKey = "$runCounter:${language.id}:${htmlDoc.hashCode()}"
                if (webView.tag != tagKey) {
                    webView.tag = tagKey
                    webView.loadDataWithBaseURL(
                        "https://sandbox.localmind.invalid/",
                        htmlDoc,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        )
    }
}

@Composable
private fun ConsoleOutputPane(
    logs: List<SandboxLogEntry>,
    durationMs: Long?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = studioColors.codeBackground,
        contentColor = studioColors.codeText,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(studioColors.codeHeader)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.sandbox_tab_console),
                        style = MaterialTheme.typography.labelLarge,
                        color = studioColors.codeText
                    )
                    if (durationMs != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = studioColors.statusOnline.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "${durationMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = studioColors.statusOnline,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.sandbox_clear_console),
                        tint = studioColors.codeText.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.sandbox_console_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = studioColors.codeText.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs, key = { it.id }) { entry ->
                        val textColor = when (entry.level) {
                            SandboxLogLevel.ERROR -> Color(0xFFFF7B72)
                            SandboxLogLevel.WARN -> Color(0xFFF2CC60)
                            SandboxLogLevel.SYSTEM -> studioColors.statusOnline
                            SandboxLogLevel.INFO -> studioColors.codeText
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = entry.text,
                                style = TextStyle(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = textColor
                                )
                            )
                        }
                        HorizontalDivider(color = studioColors.codeText.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxAiComposerBar(
    language: SandboxLanguage,
    prompt: String,
    isGenerating: Boolean,
    statusText: String?,
    hasErrors: Boolean,
    onPromptChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    onFixErrors: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AnimatedVisibility(visible = isGenerating && !statusText.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 6.dp)
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Text(
                    text = statusText.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Quick AI action chips + one-tap Fix Errors when runtime errors exist
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (hasErrors && !isGenerating) {
                item(key = "fix_errors") {
                    FilledTonalButton(
                        onClick = onFixErrors,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.sandbox_ai_fix),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            val quickSuggestions = when (language) {
                SandboxLanguage.HTML_JS -> listOf(
                    R.string.sandbox_quick_snake to "Create a playable retro neon Snake game with touch buttons and score counter in HTML/CSS/JS",
                    R.string.sandbox_quick_calc to "Create a stylish Liquid Glass scientific calculator in HTML/CSS/JS",
                    R.string.sandbox_quick_chart to "Create an interactive animated bar & line chart dashboard in HTML5 Canvas"
                )
                SandboxLanguage.JAVASCRIPT -> listOf(
                    R.string.sandbox_quick_sort to "Write and benchmark Merge Sort vs Quick Sort on 200 random numbers in JavaScript"
                )
                SandboxLanguage.PYTHON -> listOf(
                    R.string.sandbox_quick_sort to "Write a Python script demonstrating prime sieve, Fibonacci, and statistics summary"
                )
                SandboxLanguage.SQL -> listOf(
                    R.string.sandbox_quick_chart to "Create an e-commerce SQLite schema with customers, orders, products and a revenue JOIN report"
                )
            }
            items(quickSuggestions, key = { it.first }) { (labelRes, promptText) ->
                AssistChip(
                    onClick = { onQuickPrompt(promptText) },
                    enabled = !isGenerating,
                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }

        // Liquid Glass AI Code Prompt Capsule
        LiquidGlassCard(
            shape = RoundedCornerShape(24.dp),
            elevated = true,
            tinted = isGenerating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (prompt.isEmpty()) {
                        Text(
                            text = stringResource(R.string.sandbox_ai_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sandbox_ai_prompt_input")
                    )
                }

                if (isGenerating) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("sandbox_ai_stop_button")
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.action_stop))
                    }
                } else {
                    FilledIconButton(
                        onClick = onGenerate,
                        enabled = prompt.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("sandbox_ai_generate_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = stringResource(R.string.sandbox_ai_generate)
                        )
                    }
                }
            }
        }
    }
}
