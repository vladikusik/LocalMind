package com.example.ui.components

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class UnorderedList(val items: List<String>) : MarkdownBlock
    data class OrderedList(val items: List<Pair<String, String>>) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

object MarkdownParser {

    private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
    private val orderedItemRegex = Regex("^(\\d+\\.)\\s+(.+)$")
    private val unorderedItemRegex = Regex("^[-*•+]\\s+(.+)$")
    private val hrRegex = Regex("^([-*_])\\s*\\1\\s*\\1[\\s\\1]*$")
    private val tableSeparatorRegex = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?$")

    fun parseBlocks(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // Fenced code block
            if (trimmed.startsWith("```")) {
                val language = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing fence
                blocks.add(
                    MarkdownBlock.CodeBlock(
                        language = language,
                        code = codeLines.joinToString("\n")
                    )
                )
                continue
            }

            // Horizontal rule
            if (hrRegex.matches(trimmed)) {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++
                continue
            }

            // Heading
            val headingMatch = headingRegex.matchEntire(trimmed)
            if (headingMatch != null) {
                blocks.add(
                    MarkdownBlock.Heading(
                        level = headingMatch.groupValues[1].length,
                        text = headingMatch.groupValues[2].trim()
                    )
                )
                i++
                continue
            }

            // Table (header row followed by |---|--- separator)
            if (trimmed.contains("|") && i + 1 < lines.size &&
                tableSeparatorRegex.matches(lines[i + 1].trim())
            ) {
                val headers = parseTableRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (rowLine.isEmpty() || !rowLine.contains("|")) break
                    rows.add(parseTableRow(rowLine))
                    i++
                }
                blocks.add(MarkdownBlock.Table(headers = headers, rows = rows))
                continue
            }

            // Blockquote
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(MarkdownBlock.BlockQuote(quoteLines.joinToString("\n")))
                continue
            }

            // Unordered list
            val unorderedMatch = unorderedItemRegex.matchEntire(trimmed)
            if (unorderedMatch != null) {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val match = unorderedItemRegex.matchEntire(lines[i].trim()) ?: break
                    items.add(match.groupValues[1])
                    i++
                }
                blocks.add(MarkdownBlock.UnorderedList(items))
                continue
            }

            // Ordered list
            val orderedMatch = orderedItemRegex.matchEntire(trimmed)
            if (orderedMatch != null) {
                val items = mutableListOf<Pair<String, String>>()
                while (i < lines.size) {
                    val match = orderedItemRegex.matchEntire(lines[i].trim()) ?: break
                    items.add(match.groupValues[1] to match.groupValues[2])
                    i++
                }
                blocks.add(MarkdownBlock.OrderedList(items))
                continue
            }

            // Paragraph (merge consecutive plain lines)
            val paragraphLines = mutableListOf<String>()
            while (i < lines.size) {
                val current = lines[i]
                val currentTrimmed = current.trim()
                if (currentTrimmed.isEmpty() ||
                    currentTrimmed.startsWith("```") ||
                    currentTrimmed.startsWith(">") ||
                    headingRegex.matches(currentTrimmed) ||
                    hrRegex.matches(currentTrimmed) ||
                    unorderedItemRegex.matches(currentTrimmed) ||
                    orderedItemRegex.matches(currentTrimmed) ||
                    (currentTrimmed.contains("|") && i + 1 < lines.size &&
                        tableSeparatorRegex.matches(lines[i + 1].trim()))
                ) {
                    break
                }
                paragraphLines.add(current)
                i++
            }
            if (paragraphLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
            }
        }

        return blocks
    }

    private fun parseTableRow(line: String): List<String> {
        return line
            .trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }
    }
}
