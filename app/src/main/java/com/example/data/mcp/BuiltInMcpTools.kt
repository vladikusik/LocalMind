package com.example.data.mcp

import com.example.data.sandbox.PythonMiniInterpreter
import com.example.data.sandbox.SandboxLanguage
import com.example.data.sandbox.SandboxStateHolder
import com.example.data.sandbox.SqlSandboxRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Built-in local MCP tools that work offline or online without needing an external MCP server.
 */
class BuiltInMcpTools(
    private val dao: McpAgentDao,
    private val sandboxHolder: SandboxStateHolder,
    private val onGenerateLocalImage: (suspend (prompt: String, negativePrompt: String) -> String)? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val definitions: List<McpToolDefinition> = listOf(
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "get_current_datetime",
            description = "Returns the current local date, time, day of week, timezone, and Unix timestamp.",
            inputSchemaJson = """{"type":"object","properties":{}}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "calculate_math",
            description = "Evaluates a mathematical expression accurately (supports +, -, *, /, ^, %, parentheses, sqrt, sin, cos, tan, ln, log10, abs, round, floor, ceil, pi, e).",
            inputSchemaJson = """{"type":"object","properties":{"expression":{"type":"string","description":"Math expression to evaluate"}},"required":["expression"]}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "search_wikipedia",
            description = "Searches Wikipedia and returns clean factual article summaries. Supports language codes like en, uk, ru.",
            inputSchemaJson = """{"type":"object","properties":{"query":{"type":"string","description":"Topic or keyword to search"},"lang":{"type":"string","description":"Wikipedia language code: en, uk, or ru (default en)"}},"required":["query"]}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "web_fetch_url",
            description = "Fetches a public http/https webpage and extracts readable plain text (up to 4000 characters).",
            inputSchemaJson = """{"type":"object","properties":{"url":{"type":"string","description":"Full https:// or http:// URL to read"}},"required":["url"]}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "save_memory_note",
            description = "Saves a persistent personal memory note to local database so future conversations can recall it.",
            inputSchemaJson = """{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string"}},"required":["title","content"]}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "list_memory_notes",
            description = "Lists saved persistent memory notes from local storage, optionally filtered by a search keyword.",
            inputSchemaJson = """{"type":"object","properties":{"query":{"type":"string","description":"Optional keyword filter"}}}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "sandbox_write_code",
            description = "Writes code into the app's live Code Sandbox (supports html, javascript, python, sql) so the user can preview and run it immediately.",
            inputSchemaJson = """{"type":"object","properties":{"language":{"type":"string","description":"One of: html, javascript, python, sql"},"title":{"type":"string","description":"Short project title"},"code":{"type":"string","description":"Complete runnable source code"}},"required":["language","code"]}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "sandbox_read_code",
            description = "Reads the current language, title, source code, and recent console output from the Code Sandbox.",
            inputSchemaJson = """{"type":"object","properties":{}}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "sandbox_run_code",
            description = "Runs Python or SQL code directly in the offline sandbox and returns stdout/table output; for HTML/JS loads and triggers live preview in the Code Sandbox.",
            inputSchemaJson = """{"type":"object","properties":{"language":{"type":"string","description":"One of: python, sql, html, javascript"},"code":{"type":"string","description":"Optional code to run (defaults to current sandbox code)"}},"required":["language"]}"""
        ),
        McpToolDefinition(
            serverId = BUILT_IN_SERVER_ID,
            serverName = "Built-in Local MCP",
            name = "generate_local_image",
            description = "Generates an image locally using the app's uncensored Local Image Studio (on-device stable-diffusion.cpp or local WebUI server) and saves it to the app gallery.",
            inputSchemaJson = """{"type":"object","properties":{"prompt":{"type":"string","description":"Detailed image prompt in English"},"negative_prompt":{"type":"string","description":"Optional negative prompt of artifacts to avoid"}},"required":["prompt"]}"""
        )
    )

    fun isBuiltIn(toolName: String): Boolean = definitions.any { it.name == toolName }

    suspend fun execute(toolName: String, arguments: JSONObject): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (toolName) {
                    "get_current_datetime" -> executeDateTime()
                    "calculate_math" -> executeMath(arguments.optString("expression"))
                    "search_wikipedia" -> executeWikipedia(
                        query = arguments.optString("query"),
                        lang = arguments.optString("lang", "en")
                    )
                    "web_fetch_url" -> executeWebFetch(arguments.optString("url"))
                    "save_memory_note" -> executeSaveNote(
                        title = arguments.optString("title"),
                        content = arguments.optString("content")
                    )
                    "list_memory_notes" -> executeListNotes(arguments.optString("query"))
                    "sandbox_write_code" -> executeSandboxWrite(
                        language = arguments.optString("language", "html"),
                        code = arguments.optString("code"),
                        title = arguments.optString("title")
                    )
                    "sandbox_read_code" -> executeSandboxRead()
                    "sandbox_run_code" -> executeSandboxRun(
                        language = arguments.optString("language", ""),
                        code = arguments.optString("code", "")
                    )
                    "generate_local_image" -> executeGenerateLocalImage(
                        prompt = arguments.optString("prompt", ""),
                        negativePrompt = arguments.optString("negative_prompt", "")
                    )
                    else -> throw IllegalArgumentException("Unknown built-in tool: $toolName")
                }
            }
        }

    private fun executeDateTime(): String {
        val now = Date()
        val tz = TimeZone.getDefault()
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }.format(now)
        val day = SimpleDateFormat("EEEE", Locale.US).apply { timeZone = tz }.format(now)
        return "Local DateTime: $iso ($day)\nTimezone: ${tz.id}\nUnix Epoch (ms): ${now.time}"
    }

    private fun executeMath(rawExpr: String): String {
        val expr = rawExpr.trim()
        require(expr.isNotEmpty()) { "Expression is empty." }
        val value = MathExpressionEvaluator(expr).parse()
        val formatted = if (value == value.toLong().toDouble() && abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.10f", value).trimEnd('0').trimEnd('.')
        }
        return "$expr = $formatted"
    }

    private fun executeWikipedia(query: String, lang: String): String {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotEmpty()) { "Wikipedia query is empty." }
        val safeLang = lang.trim().lowercase().takeIf { it.matches(Regex("^[a-z]{2,3}$")) } ?: "en"
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

        // Step 1: Search best matching page titles
        val searchUrl = "https://$safeLang.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=3&namespace=0&format=json"
        val searchReq = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "LocalMindAndroid/1.0")
            .get()
            .build()

        val titles = httpClient.newCall(searchReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Wikipedia HTTP ${resp.code}")
            val arr = JSONArray(body)
            val titleArr = arr.optJSONArray(1) ?: JSONArray()
            buildList {
                for (i in 0 until titleArr.length()) {
                    val t = titleArr.optString(i).trim()
                    if (t.isNotEmpty()) add(t)
                }
            }
        }

        if (titles.isEmpty()) {
            return "No Wikipedia articles found for \"$cleanQuery\" ($safeLang)."
        }

        // Step 2: Fetch REST summaries for top 2 matches
        return buildString {
            titles.take(2).forEach { title ->
                val slug = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
                val sumUrl = "https://$safeLang.wikipedia.org/api/rest_v1/page/summary/$slug"
                val sumReq = Request.Builder()
                    .url(sumUrl)
                    .header("User-Agent", "LocalMindAndroid/1.0")
                    .get()
                    .build()
                runCatching {
                    httpClient.newCall(sumReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val obj = JSONObject(resp.body?.string().orEmpty())
                            val extract = obj.optString("extract").trim()
                            val pageUrl = obj.optJSONObject("content_urls")
                                ?.optJSONObject("desktop")
                                ?.optString("page")
                                .orEmpty()
                            if (extract.isNotEmpty()) {
                                appendLine("### ${obj.optString("title", title)}")
                                appendLine(extract)
                                if (pageUrl.isNotBlank()) appendLine("Source: $pageUrl")
                                appendLine()
                            }
                        }
                    }
                }
            }
        }.trim().ifEmpty { "Found titles: ${titles.joinToString(", ")}" }
    }

    private fun executeWebFetch(rawUrl: String): String {
        val url = rawUrl.trim()
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) LocalMindAgent/1.0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
            }
            val cleaned = raw
                .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
                .replace(Regex("(?s)<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (cleaned.length > 4000) {
                cleaned.take(4000) + "\n… [truncated]"
            } else {
                cleaned.ifBlank { "(Page returned empty readable text)" }
            }
        }
    }

    private suspend fun executeSaveNote(title: String, content: String): String {
        val cleanTitle = title.trim().ifEmpty { "Memory Note" }.take(80)
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Note content cannot be empty." }
        val id = dao.upsertNote(
            AgentNoteEntity(
                title = cleanTitle,
                content = cleanContent,
                updatedAt = System.currentTimeMillis()
            )
        )
        return "Saved memory note #$id: \"$cleanTitle\"."
    }

    private suspend fun executeListNotes(query: String): String {
        val notes = dao.getNotesSnapshot()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            notes
        } else {
            notes.filter {
                it.title.lowercase().contains(q) || it.content.lowercase().contains(q)
            }
        }
        if (filtered.isEmpty()) {
            return if (q.isEmpty()) "No memory notes saved yet." else "No memory notes matching \"$query\"."
        }
        return buildString {
            filtered.take(20).forEach { note ->
                appendLine("- [${note.title}]: ${note.content}")
            }
        }.trim()
    }

    private fun executeSandboxWrite(language: String, code: String, title: String): String {
        val cleanCode = code.trim()
        require(cleanCode.isNotEmpty()) { "Code cannot be empty." }
        val lang = SandboxLanguage.fromTag(language)
        sandboxHolder.loadCodeAndRun(lang, cleanCode, title.ifBlank { null })
        return "Loaded ${cleanCode.lines().size} lines of ${lang.displayName} into Code Sandbox. User can open the Code Sandbox screen to view & run it live."
    }

    private fun executeSandboxRead(): String {
        val st = sandboxHolder.state.value
        val logsPreview = st.consoleLogs.takeLast(12).joinToString("\n") { "[${it.level}] ${it.text}" }
        return buildString {
            appendLine("Sandbox Language: ${st.language.displayName}")
            appendLine("Title: ${st.title}")
            appendLine("Code:\n```${st.language.id}\n${st.code.take(3000)}\n```")
            if (logsPreview.isNotBlank()) {
                appendLine("Recent Console Output:\n$logsPreview")
            }
        }.trim()
    }

    private suspend fun executeSandboxRun(language: String, code: String): String {
        val current = sandboxHolder.state.value
        val lang = if (language.isNotBlank()) SandboxLanguage.fromTag(language) else current.language
        val targetCode = code.trim().ifEmpty { current.code }
        require(targetCode.isNotEmpty()) { "No code available to run." }

        sandboxHolder.loadCodeAndRun(lang, targetCode)

        return when (lang) {
            SandboxLanguage.PYTHON -> {
                val (logs, ms) = PythonMiniInterpreter.run(targetCode)
                sandboxHolder.setExecutionResult(logs, ms)
                buildString {
                    appendLine("Python executed in ${ms}ms:")
                    logs.forEach { (lvl, txt) -> appendLine("[$lvl] $txt") }
                }.trim()
            }

            SandboxLanguage.SQL -> {
                val (logs, ms) = SqlSandboxRunner.run(targetCode)
                sandboxHolder.setExecutionResult(logs, ms)
                buildString {
                    appendLine("SQLite executed in ${ms}ms:")
                    logs.forEach { (lvl, txt) -> appendLine("[$lvl] $txt") }
                }.trim()
            }

            SandboxLanguage.HTML_JS, SandboxLanguage.JAVASCRIPT -> {
                "Loaded ${lang.displayName} (${targetCode.lines().size} lines) into the live WebView Code Sandbox. Open Code Sandbox to interact with the rendered preview & console."
            }
        }
    }

    private suspend fun executeGenerateLocalImage(prompt: String, negativePrompt: String): String {
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "Image prompt cannot be empty." }
        val callback = onGenerateLocalImage
            ?: return "Prepared prompt for Image Studio: $cleanPrompt"
        return callback(cleanPrompt, negativePrompt.trim())
    }

    companion object {
        const val BUILT_IN_SERVER_ID = "builtin_local_mcp"
    }
}

/**
 * Safe recursive-descent math parser (no eval/script engine).
 */
private class MathExpressionEvaluator(private val input: String) {
    private var pos = -1
    private var ch = 0

    private fun nextChar() {
        pos++
        ch = if (pos < input.length) input[pos].code else -1
    }

    private fun eat(charToEat: Int): Boolean {
        while (ch == ' '.code || ch == '\t'.code || ch == '\n'.code) nextChar()
        if (ch == charToEat) {
            nextChar()
            return true
        }
        return false
    }

    fun parse(): Double {
        nextChar()
        val x = parseExpression()
        if (pos < input.length) throw IllegalArgumentException("Unexpected character '${ch.toChar()}' at position $pos")
        return x
    }

    private fun parseExpression(): Double {
        var x = parseTerm()
        while (true) {
            x = when {
                eat('+'.code) -> x + parseTerm()
                eat('-'.code) -> x - parseTerm()
                else -> return x
            }
        }
    }

    private fun parseTerm(): Double {
        var x = parseFactor()
        while (true) {
            x = when {
                eat('*'.code) -> x * parseFactor()
                eat('/'.code) -> {
                    val d = parseFactor()
                    if (d == 0.0) throw ArithmeticException("Division by zero")
                    x / d
                }
                eat('%'.code) -> x % parseFactor()
                else -> return x
            }
        }
    }

    private fun parseFactor(): Double {
        if (eat('+'.code)) return parseFactor()
        if (eat('-'.code)) return -parseFactor()

        var x: Double
        val startPos = pos
        if (eat('('.code)) {
            x = parseExpression()
            if (!eat(')'.code)) throw IllegalArgumentException("Missing closing ')'")
        } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
            while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
            x = input.substring(startPos, pos).toDouble()
        } else if (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) {
            while (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code || ch in '0'.code..'9'.code) nextChar()
            val name = input.substring(startPos, pos).lowercase()
            x = when (name) {
                "pi" -> Math.PI
                "e" -> Math.E
                else -> {
                    val arg = parseFactor()
                    when (name) {
                        "sqrt" -> sqrt(arg)
                        "abs" -> abs(arg)
                        "sin" -> sin(Math.toRadians(arg))
                        "cos" -> cos(Math.toRadians(arg))
                        "tan" -> tan(Math.toRadians(arg))
                        "ln" -> ln(arg)
                        "log", "log10" -> log10(arg)
                        "round" -> round(arg)
                        "floor" -> floor(arg)
                        "ceil" -> ceil(arg)
                        else -> throw IllegalArgumentException("Unknown function: $name")
                    }
                }
            }
        } else {
            throw IllegalArgumentException("Unexpected token near position $pos")
        }

        if (eat('^'.code)) x = x.pow(parseFactor())
        return x
    }
}
