package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CodeCommentColor
import com.example.ui.theme.CodeKeywordColor
import com.example.ui.theme.CodeNumberColor
import com.example.ui.theme.CodeStringColor
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.SpaceGroteskFontFamily
import kotlinx.coroutines.delay

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    isStreaming: Boolean = false,
    onRunCodeInSandbox: ((language: String, code: String) -> Unit)? = null
) {
    if (isStreaming) {
        // O(1) incremental streaming split: completed blocks before the last paragraph boundary
        // outside an open code fence are parsed once and cached; only the short active tail reparses!
        val splitIdx = remember(markdown) { findSafeIncrementalSplitIndex(markdown) }
        val completedPrefix = remember(markdown, splitIdx) {
            if (splitIdx > 0) markdown.substring(0, splitIdx) else ""
        }
        val activeTail = remember(markdown, splitIdx) {
            if (splitIdx > 0) markdown.substring(splitIdx) else markdown
        }
        val completedBlocks = remember(completedPrefix) {
            if (completedPrefix.isEmpty()) emptyList() else MarkdownParser.parseBlocks(completedPrefix)
        }
        val tailBlocks = remember(activeTail) {
            if (activeTail.isBlank()) emptyList() else MarkdownParser.parseBlocks(activeTail)
        }

        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            completedBlocks.forEach { block ->
                RenderMarkdownBlock(
                    block = block,
                    textColor = textColor,
                    skipCodeHighlight = false,
                    onRunCodeInSandbox = null
                )
            }
            tailBlocks.forEachIndexed { idx, block ->
                RenderMarkdownBlock(
                    block = block,
                    textColor = textColor,
                    skipCodeHighlight = idx == tailBlocks.lastIndex,
                    onRunCodeInSandbox = null
                )
            }
        }
        return
    }

    val blocks = remember(markdown) { MarkdownParser.parseBlocks(markdown) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        blocks.forEach { block ->
            RenderMarkdownBlock(
                block = block,
                textColor = textColor,
                skipCodeHighlight = false,
                onRunCodeInSandbox = onRunCodeInSandbox
            )
        }
    }
}

@Composable
private fun RenderMarkdownBlock(
    block: MarkdownBlock,
    textColor: Color,
    skipCodeHighlight: Boolean,
    onRunCodeInSandbox: ((language: String, code: String) -> Unit)?
) {
    when (block) {
        is MarkdownBlock.Heading -> HeadingView(block, textColor)
        is MarkdownBlock.Paragraph -> InlineMarkdownText(
            text = block.text,
            style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
        )
        is MarkdownBlock.CodeBlock -> CodeBlockCard(
            block = block,
            skipHighlight = skipCodeHighlight,
            onRunInSandbox = onRunCodeInSandbox
        )
        is MarkdownBlock.BlockQuote -> BlockQuoteView(block.text, textColor)
        is MarkdownBlock.UnorderedList -> UnorderedListView(block.items, textColor)
        is MarkdownBlock.OrderedList -> OrderedListView(block.items, textColor)
        is MarkdownBlock.Table -> MarkdownTableView(block, textColor)
        MarkdownBlock.HorizontalRule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * Finds the last `\n\n` boundary that is NOT inside an open ``` code fence so previously
 * streamed paragraphs stay immutable and cached instead of re-parsing the full response.
 */
private fun findSafeIncrementalSplitIndex(markdown: String): Int {
    if (markdown.length < 160) return 0
    var inFence = false
    var lastSafeSplit = 0
    var i = 0
    val len = markdown.length
    while (i < len) {
        val lineStart = i
        var lineEnd = markdown.indexOf('\n', i)
        if (lineEnd == -1) lineEnd = len
        // Skip leading spaces for fence detection
        var p = lineStart
        while (p < lineEnd && (markdown[p] == ' ' || markdown[p] == '\t')) p++
        if (p + 2 < lineEnd && markdown[p] == '`' && markdown[p + 1] == '`' && markdown[p + 2] == '`') {
            inFence = !inFence
        } else if (!inFence && p == lineEnd && lineEnd + 1 < len) {
            // Blank line outside a code fence: everything up to lineEnd + 1 is finalized
            lastSafeSplit = lineEnd + 1
        }
        i = lineEnd + 1
    }
    return lastSafeSplit
}

@Composable
fun ThinkingProcessCard(
    thinkingText: String,
    isStillThinking: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(isStillThinking) }

    LaunchedEffect(isStillThinking) {
        if (!isStillThinking) {
            expanded = false
        }
    }

    com.example.ui.theme.LiquidGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevated = false
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isStillThinking) {
                        stringResource(R.string.thinking_in_progress)
                    } else {
                        stringResource(R.string.thinking_section_label)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Text(
                        text = thinkingText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = FontStyle.Italic,
                            lineHeight = 19.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeadingView(heading: MarkdownBlock.Heading, textColor: Color) {
    val style = when (heading.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }.copy(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        color = textColor
    )
    InlineMarkdownText(
        text = heading.text,
        style = style,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun BlockQuoteView(text: String, textColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
        InlineMarkdownText(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = textColor.copy(alpha = 0.85f),
                fontStyle = FontStyle.Italic
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun UnorderedListView(items: List<String>, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(start = 4.dp)
                )
                InlineMarkdownText(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun OrderedListView(items: List<Pair<String, String>>, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (number, content) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
                InlineMarkdownText(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    block: MarkdownBlock.CodeBlock,
    skipHighlight: Boolean = false,
    onRunInSandbox: ((language: String, code: String) -> Unit)? = null
) {
    val studioColors = LocalStudioColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.code) { mutableStateOf(false) }
    val langNormalized = block.language.trim().lowercase()
    val isSandboxRunnable = langNormalized in setOf(
        "html", "htm", "css", "svg", "js", "javascript", "py", "python", "python3", "sql", "sqlite"
    )

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    val highlighted = remember(block.code, skipHighlight, studioColors.codeText) {
        if (skipHighlight || block.code.length > 16_000) {
            AnnotatedString(block.code)
        } else {
            highlightCodeSyntax(block.code, studioColors.codeText)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = studioColors.codeBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(studioColors.codeHeader)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = block.language.ifBlank { "code" }.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = studioColors.codeText.copy(alpha = 0.72f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isSandboxRunnable && onRunInSandbox != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                .clickable { onRunInSandbox(langNormalized, block.code) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.action_run_in_sandbox),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.action_run_in_sandbox),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(block.code))
                                copied = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy_code),
                            tint = if (copied) studioColors.statusOnline else studioColors.codeText.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (copied) {
                                stringResource(R.string.action_copied)
                            } else {
                                stringResource(R.string.action_copy)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (copied) studioColors.statusOnline else studioColors.codeText.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                Text(
                    text = highlighted,
                    style = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = studioColors.codeText
                    )
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableView(table: MarkdownBlock.Table, textColor: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                table.headers.forEach { header ->
                    InlineMarkdownText(
                        text = header,
                        style = MaterialTheme.typography.labelLarge.copy(color = textColor),
                        modifier = Modifier.widthIn(min = 96.dp, max = 200.dp)
                    )
                }
            }

            table.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val colCount = maxOf(table.headers.size, row.size)
                    for (c in 0 until colCount) {
                        InlineMarkdownText(
                            text = row.getOrElse(c) { "" },
                            style = MaterialTheme.typography.bodySmall.copy(color = textColor),
                            modifier = Modifier.widthIn(min = 96.dp, max = 200.dp)
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun InlineMarkdownText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current

    val annotated = remember(text, primaryColor, studioColors.inlineCodeBackground) {
        buildInlineAnnotatedString(
            text = text,
            linkColor = primaryColor,
            inlineCodeBg = studioColors.inlineCodeBackground
        )
    }

    val hasLinks = remember(annotated) {
        annotated.getStringAnnotations("URL", 0, annotated.length).isNotEmpty()
    }

    if (hasLinks) {
        ClickableText(
            text = annotated,
            style = style,
            modifier = modifier,
            onClick = { offset ->
                annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()
                    ?.let { annotation ->
                        val url = annotation.item.trim()
                        if (url.startsWith("http://", ignoreCase = true) ||
                            url.startsWith("https://", ignoreCase = true)
                        ) {
                            runCatching { uriHandler.openUri(url) }
                        }
                    }
            }
        )
    } else {
        Text(
            text = annotated,
            style = style,
            modifier = modifier
        )
    }
}

private val inlineTokenRegex = Regex(
    """(`[^`]+`)|(\[([^\]]+)\]\((https?://[^)\s]+)\))|(\*\*\*([^*]+)\*\*\*)|(\*\*([^*]+)\*\*)|(__([^_]+)__)|(~~([^~]+)~~)|(\*([^*]+)\*)"""
)

private fun hasInlineMarkdownTokens(text: String): Boolean {
    for (i in 0 until text.length) {
        when (text[i]) {
            '`', '*', '_', '[', '~' -> return true
        }
    }
    return false
}

private fun buildInlineAnnotatedString(
    text: String,
    linkColor: Color,
    inlineCodeBg: Color
): AnnotatedString {
    if (!hasInlineMarkdownTokens(text)) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
    var cursor = 0
    for (match in inlineTokenRegex.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val full = match.value
        when {
            // Inline code
            match.groups[1] != null -> {
                withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.5.sp,
                        background = inlineCodeBg
                    )
                ) {
                    append(" ${full.removeSurrounding("`")} ")
                }
            }
            // Markdown link [label](https://...)
            match.groups[2] != null -> {
                val label = match.groupValues[3]
                val url = match.groupValues[4]
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium
                    )
                ) {
                    append(label)
                }
                pop()
            }
            // Bold + Italic ***text***
            match.groups[5] != null -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[6])
                }
            }
            // Bold **text** or __text__
            match.groups[7] != null -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[8])
                }
            }
            match.groups[9] != null -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[10])
                }
            }
            // Strikethrough ~~text~~
            match.groups[11] != null -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(match.groupValues[12])
                }
            }
            // Italic *text*
            match.groups[13] != null -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[14])
                }
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}
}

private val codeHighlightRegex = Regex(
    """(//[^\n]*|#[^\n]*)|("([^"\\]|\\.)*"|'([^'\\]|\\.)*')|\b(fun|val|var|class|object|interface|data|sealed|suspend|return|if|else|when|for|while|import|package|def|async|await|const|let|function|export|from|try|catch|public|private|override|true|false|null|None|SELECT|FROM|WHERE|INSERT|UPDATE|DELETE)\b|\b(\d+(?:\.\d+)?)\b"""
)

private fun highlightCodeSyntax(code: String, baseColor: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (match in codeHighlightRegex.findAll(code)) {
            if (match.range.first > cursor) {
                withStyle(SpanStyle(color = baseColor)) {
                    append(code.substring(cursor, match.range.first))
                }
            }
            val color = when {
                match.groups[1] != null -> CodeCommentColor
                match.groups[2] != null -> CodeStringColor
                match.groups[5] != null -> CodeKeywordColor
                match.groups[6] != null -> CodeNumberColor
                else -> baseColor
            }
            withStyle(SpanStyle(color = color)) {
                append(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < code.length) {
            withStyle(SpanStyle(color = baseColor)) {
                append(code.substring(cursor))
            }
        }
    }
