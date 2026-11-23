package com.example.data.sandbox

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale
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

enum class SandboxLanguage(
    val id: String,
    val displayName: String,
    val fileExtension: String,
    val usesWebView: Boolean
) {
    HTML_JS("html", "HTML / CSS / JS", "html", true),
    JAVASCRIPT("javascript", "JavaScript", "js", true),
    PYTHON("python", "Python", "py", false),
    SQL("sql", "SQL (SQLite)", "sql", false);

    companion object {
        fun fromTag(raw: String?): SandboxLanguage {
            val cleaned = raw?.trim()?.lowercase().orEmpty()
            return when {
                cleaned in setOf("html", "htm", "css", "svg", "html/js", "web", "jsx") -> HTML_JS
                cleaned in setOf("js", "javascript", "ts", "typescript", "node") -> JAVASCRIPT
                cleaned in setOf("py", "python", "python3") -> PYTHON
                cleaned in setOf("sql", "sqlite", "sqlite3", "postgres", "mysql") -> SQL
                else -> HTML_JS
            }
        }
    }
}

enum class SandboxLogLevel {
    INFO,
    WARN,
    ERROR,
    SYSTEM
}

data class SandboxLogEntry(
    val id: Long,
    val level: SandboxLogLevel,
    val text: String
)

data class SandboxUiState(
    val language: SandboxLanguage = SandboxLanguage.HTML_JS,
    val title: String = "Interactive Prism Canvas",
    val code: String = SandboxTemplates.forLanguage(SandboxLanguage.HTML_JS),
    val consoleLogs: List<SandboxLogEntry> = emptyList(),
    val lastRunDurationMs: Long? = null,
    val runCounter: Int = 1,
    val aiPrompt: String = "",
    val isAiGenerating: Boolean = false,
    val aiStatusText: String? = null
)

/**
 * Shared reactive state holder so the Code Sandbox screen, Chat code blocks,
 * and Autonomous MCP Agents all operate on the same live workspace.
 */
class SandboxStateHolder {
    private val _state = MutableStateFlow(SandboxUiState())
    val state: StateFlow<SandboxUiState> = _state.asStateFlow()

    private var logSeq = 1L

    fun selectLanguage(language: SandboxLanguage, loadTemplateIfUntouched: Boolean = true) {
        _state.update { current ->
            val shouldSwap = loadTemplateIfUntouched &&
                SandboxTemplates.allDefaults().contains(current.code.trim())
            current.copy(
                language = language,
                title = if (shouldSwap) SandboxTemplates.titleFor(language) else current.title,
                code = if (shouldSwap) SandboxTemplates.forLanguage(language) else current.code,
                consoleLogs = emptyList(),
                lastRunDurationMs = null,
                runCounter = current.runCounter + 1
            )
        }
    }

    fun updateCode(newCode: String) {
        _state.update { it.copy(code = newCode) }
    }

    fun updateTitle(newTitle: String) {
        _state.update { it.copy(title = newTitle) }
    }

    fun updateAiPrompt(prompt: String) {
        _state.update { it.copy(aiPrompt = prompt) }
    }

    fun setAiGenerating(generating: Boolean, statusText: String? = null) {
        _state.update { it.copy(isAiGenerating = generating, aiStatusText = statusText) }
    }

    fun loadTemplate(language: SandboxLanguage) {
        _state.update {
            it.copy(
                language = language,
                title = SandboxTemplates.titleFor(language),
                code = SandboxTemplates.forLanguage(language),
                consoleLogs = emptyList(),
                lastRunDurationMs = null,
                runCounter = it.runCounter + 1
            )
        }
    }

    fun loadCodeAndRun(language: SandboxLanguage, code: String, title: String? = null) {
        _state.update {
            it.copy(
                language = language,
                title = title?.takeIf { t -> t.isNotBlank() } ?: SandboxTemplates.titleFor(language),
                code = code,
                consoleLogs = emptyList(),
                lastRunDurationMs = null,
                runCounter = it.runCounter + 1
            )
        }
    }

    fun clearLogs() {
        _state.update { it.copy(consoleLogs = emptyList(), lastRunDurationMs = null) }
    }

    fun appendLog(level: SandboxLogLevel, text: String) {
        val id = logSeq++
        _state.update { current ->
            current.copy(
                consoleLogs = (current.consoleLogs + SandboxLogEntry(id, level, text)).takeLast(200)
            )
        }
    }

    fun markRunTriggered() {
        _state.update {
            it.copy(
                consoleLogs = emptyList(),
                lastRunDurationMs = null,
                runCounter = it.runCounter + 1
            )
        }
    }

    fun setExecutionResult(logs: List<Pair<SandboxLogLevel, String>>, durationMs: Long) {
        val entries = logs.map { (lvl, txt) -> SandboxLogEntry(logSeq++, lvl, txt) }
        _state.update {
            it.copy(
                consoleLogs = entries,
                lastRunDurationMs = durationMs
            )
        }
    }
}

object SandboxTemplates {
    fun allDefaults(): Set<String> = SandboxLanguage.entries.map { forLanguage(it).trim() }.toSet()

    fun titleFor(language: SandboxLanguage): String = when (language) {
        SandboxLanguage.HTML_JS -> "Interactive Prism Canvas"
        SandboxLanguage.JAVASCRIPT -> "Algorithm & Data Playground"
        SandboxLanguage.PYTHON -> "Python Analytics & Fibonacci"
        SandboxLanguage.SQL -> "In-Memory SQLite Analytics"
    }

    fun forLanguage(language: SandboxLanguage): String = when (language) {
        SandboxLanguage.HTML_JS -> """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    min-height: 100vh;
    background: radial-gradient(circle at 20% 20%, #102a43, #05070c 70%);
    color: #eef6ff;
    font-family: system-ui, -apple-system, sans-serif;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 16px;
    padding: 16px;
  }
  .glass {
    background: rgba(255,255,255,0.08);
    border: 1px solid rgba(255,255,255,0.18);
    border-radius: 20px;
    padding: 16px 20px;
    backdrop-filter: blur(12px);
    text-align: center;
    width: 100%;
    max-width: 380px;
  }
  canvas { width: 100%; height: 210px; border-radius: 14px; display: block; }
  button {
    margin-top: 12px;
    padding: 10px 18px;
    border-radius: 999px;
    border: none;
    background: linear-gradient(135deg, #2ef2c9, #3b82f6);
    color: #041016;
    font-weight: 700;
    font-size: 14px;
    cursor: pointer;
  }
</style>
</head>
<body>
  <div class="glass">
    <canvas id="c"></canvas>
    <p id="stats" style="margin-top:10px;font-size:13px;opacity:0.85;">Tap anywhere on canvas to spawn particles</p>
    <button onclick="burst()">Prism Burst ✨</button>
  </div>
<script>
  const canvas = document.getElementById('c');
  const ctx = canvas.getContext('2d');
  let particles = [];
  function resize() {
    canvas.width = canvas.clientWidth * devicePixelRatio;
    canvas.height = canvas.clientHeight * devicePixelRatio;
  }
  resize();
  function spawn(x, y, n = 22) {
    for (let i = 0; i < n; i++) {
      const a = Math.random() * Math.PI * 2;
      const s = 1 + Math.random() * 4;
      particles.push({
        x, y, vx: Math.cos(a) * s, vy: Math.sin(a) * s,
        r: 2 + Math.random() * 4,
        hue: 160 + Math.random() * 140,
        life: 1
      });
    }
    console.log('Active particles:', particles.length);
  }
  function burst() { spawn(canvas.width / 2, canvas.height / 2, 36); }
  canvas.addEventListener('pointerdown', e => {
    const rect = canvas.getBoundingClientRect();
    spawn((e.clientX - rect.left) * devicePixelRatio, (e.clientY - rect.top) * devicePixelRatio);
  });
  burst();
  function loop() {
    ctx.fillStyle = 'rgba(5, 7, 12, 0.22)';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    particles = particles.filter(p => p.life > 0.02);
    for (const p of particles) {
      p.x += p.vx; p.y += p.vy; p.vy += 0.04; p.life *= 0.97;
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
      ctx.fillStyle = `hsla(${'$'}{p.hue}, 95%, 65%, ${'$'}{p.life})`;
      ctx.fill();
    }
    document.getElementById('stats').textContent = `Particles: ${'$'}{particles.length} • 60 FPS Sandbox`;
    requestAnimationFrame(loop);
  }
  loop();
</script>
</body>
</html>
        """.trimIndent()

        SandboxLanguage.JAVASCRIPT -> """
// JavaScript Sandbox — console.log output appears below
function quickSort(arr) {
  if (arr.length <= 1) return arr;
  const pivot = arr[Math.floor(arr.length / 2)];
  const left = arr.filter(x => x < pivot);
  const mid = arr.filter(x => x === pivot);
  const right = arr.filter(x => x > pivot);
  return [...quickSort(left), ...mid, ...quickSort(right)];
}

const data = [42, 7, 19, 88, 3, 55, 19, 102, 11];
console.log("Original:", JSON.stringify(data));
const sorted = quickSort(data);
console.log("Sorted:  ", JSON.stringify(sorted));

const avg = sorted.reduce((a, b) => a + b, 0) / sorted.length;
console.log("Average: ", avg.toFixed(2));
        """.trimIndent()

        SandboxLanguage.PYTHON -> """
# Offline Python Sandbox
def fib(n):
    if n <= 1:
        return n
    return fib(n - 1) + fib(n - 2)

seq = []
for i in range(10):
    seq.append(fib(i))

print("Fibonacci (0..9):", seq)
print("Sum:", sum(seq), "Max:", max(seq))

scores = [91, 84, 97, 76, 89]
avg = sum(scores) / len(scores)
print("Average score:", round(avg, 2))
        """.trimIndent()

        SandboxLanguage.SQL -> """
-- Live In-Memory SQLite Sandbox
CREATE TABLE models (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    provider TEXT NOT NULL,
    params_b REAL,
    free_tier INTEGER
);

INSERT INTO models VALUES
  (1, 'DeepSeek R1', 'OpenRouter', 671.0, 1),
  (2, 'Qwen 2.5 1.5B', 'llama.cpp', 1.5, 1),
  (3, 'Llama 3.3 70B', 'Cerebras', 70.0, 1),
  (4, 'Gemma 3 27B', 'OpenRouter', 27.0, 1),
  (5, 'Claude Sonnet', 'Anthropic', 200.0, 0);

SELECT provider, COUNT(*) AS count, ROUND(AVG(params_b), 1) AS avg_params_b
FROM models
WHERE free_tier = 1
GROUP BY provider
ORDER BY avg_params_b DESC;
        """.trimIndent()
    }
}

object HtmlSandboxBuilder {
    private val bridgeScript = """
<script>
(function() {
  function fmt(args) {
    return Array.from(args).map(function(a) {
      if (typeof a === 'object' && a !== null) {
        try { return JSON.stringify(a); } catch (e) { return String(a); }
      }
      return String(a);
    }).join(' ');
  }
  var origLog = console.log, origWarn = console.warn, origErr = console.error;
  console.log = function() {
    if (window.AndroidSandbox) window.AndroidSandbox.postLog('INFO', fmt(arguments));
    if (origLog) origLog.apply(console, arguments);
  };
  console.warn = function() {
    if (window.AndroidSandbox) window.AndroidSandbox.postLog('WARN', fmt(arguments));
    if (origWarn) origWarn.apply(console, arguments);
  };
  console.error = function() {
    if (window.AndroidSandbox) window.AndroidSandbox.postLog('ERROR', fmt(arguments));
    if (origErr) origErr.apply(console, arguments);
  };
  window.onerror = function(msg, src, line, col) {
    if (window.AndroidSandbox) window.AndroidSandbox.postLog('ERROR', msg + ' (line ' + line + ')');
  };
  window.onunhandledrejection = function(e) {
    if (window.AndroidSandbox) window.AndroidSandbox.postLog('ERROR', 'Unhandled Promise: ' + (e.reason || e));
  };
})();
</script>
    """.trimIndent()

    fun buildHtmlDocument(language: SandboxLanguage, code: String): String {
        return when (language) {
            SandboxLanguage.HTML_JS -> {
                val lower = code.lowercase()
                when {
                    lower.contains("<head>") -> code.replaceFirst(Regex("(?i)<head>"), "<head>$bridgeScript")
                    lower.contains("<html") -> "$bridgeScript\n$code"
                    else -> """
                        <!DOCTYPE html>
                        <html><head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1.0"/>$bridgeScript</head>
                        <body style="margin:0;padding:12px;background:#0a0f18;color:#eef4fc;font-family:system-ui,sans-serif;">
                        $code
                        </body></html>
                    """.trimIndent()
                }
            }

            SandboxLanguage.JAVASCRIPT -> {
                val safeScript = code.replace("</script>", "<\\/script>")
                """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                  $bridgeScript
                </head>
                <body style="margin:0;padding:16px;background:#090d14;color:#d6e4f5;font-family:monospace;font-size:13px;">
                  <div id="js-root" style="opacity:0.8;">JavaScript engine ready. Output is streamed to Console.</div>
                  <script>
                    (function() {
                      var t0 = performance.now();
                      try {
                        var __result = (function() {
                          $safeScript
                        })();
                        if (__result !== undefined) {
                          console.log('=>', __result);
                        }
                        var elapsed = Math.max(1, Math.round(performance.now() - t0));
                        if (window.AndroidSandbox) window.AndroidSandbox.postDone(elapsed);
                      } catch (err) {
                        console.error(err && err.stack ? err.stack : String(err));
                        if (window.AndroidSandbox) window.AndroidSandbox.postDone(Math.max(1, Math.round(performance.now() - t0)));
                      }
                    })();
                  </script>
                </body>
                </html>
                """.trimIndent()
            }

            else -> ""
        }
    }
}

/**
 * Executes SQL statements in a clean, isolated in-memory Android SQLite database.
 */
object SqlSandboxRunner {
    suspend fun run(sqlScript: String): Pair<List<Pair<SandboxLogLevel, String>>, Long> =
        withContext(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            val logs = mutableListOf<Pair<SandboxLogLevel, String>>()
            val db = SQLiteDatabase.create(null)
            try {
                val statements = splitSqlStatements(sqlScript)
                if (statements.isEmpty()) {
                    logs.add(SandboxLogLevel.WARN to "No SQL statements to execute.")
                }
                for (stmt in statements) {
                    val upper = stmt.trimStart().uppercase(Locale.US)
                    val isQuery = upper.startsWith("SELECT") ||
                        upper.startsWith("PRAGMA") ||
                        upper.startsWith("WITH") ||
                        upper.startsWith("EXPLAIN")

                    if (isQuery) {
                        db.rawQuery(stmt, null).use { cursor ->
                            val cols = cursor.columnNames
                            val rows = mutableListOf<List<String>>()
                            while (cursor.moveToNext() && rows.size < 100) {
                                rows.add(
                                    List(cols.size) { idx ->
                                        if (cursor.isNull(idx)) "NULL" else cursor.getString(idx).orEmpty()
                                    }
                                )
                            }
                            logs.add(SandboxLogLevel.INFO to formatTable(cols.toList(), rows))
                            logs.add(SandboxLogLevel.SYSTEM to "Returned ${rows.size} row(s).")
                        }
                    } else {
                        db.execSQL(stmt)
                        val firstWord = upper.substringBefore(' ').take(16)
                        logs.add(SandboxLogLevel.SYSTEM to "OK ($firstWord)")
                    }
                }
            } catch (e: Exception) {
                logs.add(SandboxLogLevel.ERROR to (e.localizedMessage ?: e.javaClass.simpleName))
            } finally {
                runCatching { db.close() }
            }
            logs to (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }

    private fun splitSqlStatements(sql: String): List<String> {
        val cleaned = sql.lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (ch in cleaned) {
            when {
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(ch)
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(ch)
                }
                ch == ';' && !inSingle && !inDouble -> {
                    val s = current.toString().trim()
                    if (s.isNotEmpty()) result.add(s)
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }

    private fun formatTable(cols: List<String>, rows: List<List<String>>): String {
        if (cols.isEmpty()) return "(empty result set)"
        val widths = cols.indices.map { i ->
            maxOf(cols[i].length, rows.maxOfOrNull { it.getOrElse(i) { "" }.length } ?: 0).coerceAtMost(28)
        }
        return buildString {
            appendLine(cols.mapIndexed { i, c -> c.take(28).padEnd(widths[i]) }.joinToString(" | "))
            appendLine(widths.joinToString("-+-") { "-".repeat(it) })
            rows.forEach { r ->
                appendLine(r.mapIndexed { i, cell -> cell.take(28).padEnd(widths[i]) }.joinToString(" | "))
            }
        }.trimEnd()
    }
}

/**
 * Safe, offline pure-Kotlin Python-subset interpreter (zero DCL, bounded steps & recursion).
 * Supports variables, arithmetic, booleans, strings, lists, dicts, indexing, if/elif/else,
 * for/while loops, def/return functions, list.append(), and common built-ins.
 */
object PythonMiniInterpreter {

    private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
    private class BreakSignal : RuntimeException(null, null, false, false)
    private class ContinueSignal : RuntimeException(null, null, false, false)

    private data class PyFunc(
        val params: List<String>,
        val body: List<Line>
    )

    private data class Line(val indent: Int, val text: String, val lineNo: Int)

    suspend fun run(source: String): Pair<List<Pair<SandboxLogLevel, String>>, Long> =
        withContext(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            val logs = mutableListOf<Pair<SandboxLogLevel, String>>()
            try {
                val lines = preprocessLines(source)
                val runner = Runner(logs)
                runner.execBlock(lines, runner.globals)
                if (logs.isEmpty()) {
                    logs.add(SandboxLogLevel.SYSTEM to "Execution finished (no stdout).")
                }
            } catch (e: Exception) {
                logs.add(SandboxLogLevel.ERROR to (e.localizedMessage ?: e.javaClass.simpleName))
            }
            logs to (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }

    private fun preprocessLines(source: String): List<Line> {
        val result = mutableListOf<Line>()
        source.lines().forEachIndexed { idx, raw ->
            val expanded = raw.replace("\t", "    ")
            val strippedComment = stripInlineComment(expanded)
            val trimmed = strippedComment.trim()
            if (trimmed.isNotEmpty()) {
                val indent = strippedComment.indexOfFirst { it != ' ' }.coerceAtLeast(0)
                result.add(Line(indent, trimmed, idx + 1))
            }
        }
        return result
    }

    private fun stripInlineComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '\'' && !inDouble) inSingle = !inSingle
            else if (c == '"' && !inSingle) inDouble = !inDouble
            else if (c == '#' && !inSingle && !inDouble) return line.substring(0, i)
        }
        return line
    }

    private class Runner(private val logs: MutableList<Pair<SandboxLogLevel, String>>) {
        val globals = mutableMapOf<String, Any?>()
        val functions = mutableMapOf<String, PyFunc>()
        private var stepCounter = 0
        private val maxSteps = 25_000

        private fun tick(lineNo: Int) {
            if (++stepCounter > maxSteps) {
                throw IllegalStateException("Execution step limit reached ($maxSteps steps) at line $lineNo")
            }
        }

        fun execBlock(lines: List<Line>, env: MutableMap<String, Any?>) {
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                tick(line.lineNo)
                val text = line.text

                when {
                    text.startsWith("import ") || text.startsWith("from ") -> {
                        i++
                    }

                    text == "pass" -> i++
                    text == "break" -> throw BreakSignal()
                    text == "continue" -> throw ContinueSignal()

                    text.startsWith("return") && (text.length == 6 || text[6] == ' ') -> {
                        val expr = text.removePrefix("return").trim()
                        val value = if (expr.isEmpty()) null else evalExpr(expr, env)
                        throw ReturnSignal(value)
                    }

                    text.startsWith("def ") && text.endsWith(":") -> {
                        val header = text.removePrefix("def ").removeSuffix(":").trim()
                        val parenStart = header.indexOf('(')
                        val parenEnd = header.lastIndexOf(')')
                        require(parenStart > 0 && parenEnd > parenStart) { "Invalid def syntax at line ${line.lineNo}" }
                        val fnName = header.substring(0, parenStart).trim()
                        val params = header.substring(parenStart + 1, parenEnd)
                            .split(',')
                            .map { it.trim().substringBefore('=').trim() }
                            .filter { it.isNotEmpty() }
                        val (body, nextIdx) = collectIndentedBlock(lines, i + 1, line.indent)
                        functions[fnName] = PyFunc(params, body)
                        i = nextIdx
                    }

                    text.startsWith("if ") && text.endsWith(":") -> {
                        val branches = mutableListOf<Pair<String?, List<Line>>>()
                        val firstCond = text.removePrefix("if ").removeSuffix(":").trim()
                        val (firstBody, nextAfterIf) = collectIndentedBlock(lines, i + 1, line.indent)
                        branches.add(firstCond to firstBody)
                        var cursor = nextAfterIf
                        while (cursor < lines.size && lines[cursor].indent == line.indent) {
                            val nextText = lines[cursor].text
                            if (nextText.startsWith("elif ") && nextText.endsWith(":")) {
                                val cond = nextText.removePrefix("elif ").removeSuffix(":").trim()
                                val (b, n) = collectIndentedBlock(lines, cursor + 1, line.indent)
                                branches.add(cond to b)
                                cursor = n
                            } else if (nextText == "else:") {
                                val (b, n) = collectIndentedBlock(lines, cursor + 1, line.indent)
                                branches.add(null to b)
                                cursor = n
                                break
                            } else break
                        }
                        for ((cond, body) in branches) {
                            if (cond == null || isTruthy(evalExpr(cond, env))) {
                                execBlock(body, env)
                                break
                            }
                        }
                        i = cursor
                    }

                    text.startsWith("for ") && text.endsWith(":") -> {
                        val header = text.removePrefix("for ").removeSuffix(":").trim()
                        val inIdx = header.indexOf(" in ")
                        require(inIdx > 0) { "Invalid for loop at line ${line.lineNo}" }
                        val varNames = header.substring(0, inIdx).split(',').map { it.trim() }
                        val iterable = evalExpr(header.substring(inIdx + 4).trim(), env)
                        val items = toIterable(iterable, line.lineNo)
                        val (body, nextIdx) = collectIndentedBlock(lines, i + 1, line.indent)
                        for (item in items) {
                            tick(line.lineNo)
                            if (varNames.size == 1) {
                                env[varNames[0]] = item
                            } else if (item is List<*>) {
                                varNames.forEachIndexed { idx, v -> env[v] = item.getOrNull(idx) }
                            }
                            try {
                                execBlock(body, env)
                            } catch (_: ContinueSignal) {
                                continue
                            } catch (_: BreakSignal) {
                                break
                            }
                        }
                        i = nextIdx
                    }

                    text.startsWith("while ") && text.endsWith(":") -> {
                        val cond = text.removePrefix("while ").removeSuffix(":").trim()
                        val (body, nextIdx) = collectIndentedBlock(lines, i + 1, line.indent)
                        while (isTruthy(evalExpr(cond, env))) {
                            tick(line.lineNo)
                            try {
                                execBlock(body, env)
                            } catch (_: ContinueSignal) {
                                continue
                            } catch (_: BreakSignal) {
                                break
                            }
                        }
                        i = nextIdx
                    }

                    else -> {
                        execSimpleStatement(text, env, line.lineNo)
                        i++
                    }
                }
            }
        }

        private fun collectIndentedBlock(lines: List<Line>, startIdx: Int, parentIndent: Int): Pair<List<Line>, Int> {
            val block = mutableListOf<Line>()
            var j = startIdx
            while (j < lines.size && lines[j].indent > parentIndent) {
                block.add(lines[j])
                j++
            }
            return block to j
        }

        private fun execSimpleStatement(stmt: String, env: MutableMap<String, Any?>, lineNo: Int) {
            // Compound assignments: +=, -=, *=, /=
            for (op in listOf("+=", "-=", "*=", "/=")) {
                val idx = findTopLevelToken(stmt, op)
                if (idx > 0) {
                    val target = stmt.substring(0, idx).trim()
                    val rhs = evalExpr(stmt.substring(idx + op.length).trim(), env)
                    val cur = evalExpr(target, env)
                    val updated = when (op) {
                        "+=" -> addValues(cur, rhs)
                        "-=" -> toDouble(cur) - toDouble(rhs)
                        "*=" -> toDouble(cur) * toDouble(rhs)
                        "/=" -> toDouble(cur) / toDouble(rhs)
                        else -> rhs
                    }
                    assignTarget(target, updated, env)
                    return
                }
            }

            // Regular assignment (=, not ==, !=, <=, >=)
            val eqIdx = findAssignmentEquals(stmt)
            if (eqIdx > 0) {
                val lhs = stmt.substring(0, eqIdx).trim()
                val rhs = evalExpr(stmt.substring(eqIdx + 1).trim(), env)
                if (lhs.contains(',') && rhs is List<*>) {
                    lhs.split(',').forEachIndexed { idx, name ->
                        assignTarget(name.trim(), rhs.getOrNull(idx), env)
                    }
                } else {
                    assignTarget(lhs, rhs, env)
                }
                return
            }

            // Expression statement (e.g., print(...), list.append(...))
            evalExpr(stmt, env)
        }

        @Suppress("UNCHECKED_CAST")
        private fun assignTarget(target: String, value: Any?, env: MutableMap<String, Any?>) {
            val bracketIdx = target.indexOf('[')
            if (bracketIdx > 0 && target.endsWith("]")) {
                val containerName = target.substring(0, bracketIdx).trim()
                val keyExpr = target.substring(bracketIdx + 1, target.length - 1).trim()
                val container = env[containerName] ?: globals[containerName]
                val key = evalExpr(keyExpr, env)
                when (container) {
                    is MutableList<*> -> {
                        val list = container as MutableList<Any?>
                        val idx = toDouble(key).toInt()
                        val resolved = if (idx < 0) list.size + idx else idx
                        list[resolved] = value
                    }
                    is MutableMap<*, *> -> {
                        (container as MutableMap<Any?, Any?>)[key] = value
                    }
                }
            } else {
                env[target] = value
            }
        }

        fun evalExpr(raw: String, env: MutableMap<String, Any?>): Any? {
            val s = raw.trim()
            if (s.isEmpty()) return null

            // Unwrap outer matching parens
            if (s.startsWith("(") && s.endsWith(")") && matchingCloseIndex(s, 0) == s.lastIndex) {
                return evalExpr(s.substring(1, s.lastIndex), env)
            }

            // Logical OR
            findTopLevelKeyword(s, "or")?.let { idx ->
                val l = evalExpr(s.substring(0, idx), env)
                return if (isTruthy(l)) l else evalExpr(s.substring(idx + 2), env)
            }
            // Logical AND
            findTopLevelKeyword(s, "and")?.let { idx ->
                val l = evalExpr(s.substring(0, idx), env)
                return if (!isTruthy(l)) l else evalExpr(s.substring(idx + 3), env)
            }
            // Logical NOT
            if (s.startsWith("not ")) {
                return !isTruthy(evalExpr(s.removePrefix("not "), env))
            }

            // Comparisons
            for (op in listOf("==", "!=", "<=", ">=", "<", ">")) {
                val idx = findTopLevelComparison(s, op)
                if (idx > 0) {
                    val l = evalExpr(s.substring(0, idx), env)
                    val r = evalExpr(s.substring(idx + op.length), env)
                    return when (op) {
                        "==" -> valuesEqual(l, r)
                        "!=" -> !valuesEqual(l, r)
                        "<" -> toDouble(l) < toDouble(r)
                        ">" -> toDouble(l) > toDouble(r)
                        "<=" -> toDouble(l) <= toDouble(r)
                        ">=" -> toDouble(l) >= toDouble(r)
                        else -> false
                    }
                }
            }

            // Addition / Subtraction (rightmost top-level)
            findLastTopLevelAddSub(s)?.let { (idx, op) ->
                val l = evalExpr(s.substring(0, idx), env)
                val r = evalExpr(s.substring(idx + 1), env)
                return if (op == '+') addValues(l, r) else numericSubtract(l, r)
            }

            // Multiplication / Division / Modulo
            findLastTopLevelMulDiv(s)?.let { (idx, op) ->
                val l = evalExpr(s.substring(0, idx), env)
                val r = evalExpr(s.substring(idx + op.length), env)
                return when (op) {
                    "*" -> multiplyValues(l, r)
                    "//" -> floor(toDouble(l) / toDouble(r)).toLong()
                    "/" -> toDouble(l) / toDouble(r)
                    "%" -> toDouble(l) % toDouble(r)
                    else -> 0
                }
            }

            // Power **
            val powIdx = findTopLevelToken(s, "**")
            if (powIdx > 0) {
                val l = toDouble(evalExpr(s.substring(0, powIdx), env))
                val r = toDouble(evalExpr(s.substring(powIdx + 2), env))
                return simplifyNumber(l.pow(r))
            }

            // Unary minus
            if (s.startsWith("-")) {
                val v = toDouble(evalExpr(s.substring(1), env))
                return simplifyNumber(-v)
            }

            // List literal [...]
            if (s.startsWith("[") && s.endsWith("]") && matchingCloseIndex(s, 0) == s.lastIndex) {
                val inner = s.substring(1, s.lastIndex).trim()
                if (inner.isEmpty()) return mutableListOf<Any?>()
                return splitTopLevelArgs(inner).map { evalExpr(it, env) }.toMutableList()
            }

            // Dict literal {...}
            if (s.startsWith("{") && s.endsWith("}") && matchingCloseIndex(s, 0) == s.lastIndex) {
                val inner = s.substring(1, s.lastIndex).trim()
                val map = linkedMapOf<Any?, Any?>()
                if (inner.isNotEmpty()) {
                    splitTopLevelArgs(inner).forEach { pair ->
                        val colon = findTopLevelToken(pair, ":")
                        if (colon > 0) {
                            val k = evalExpr(pair.substring(0, colon), env)
                            val v = evalExpr(pair.substring(colon + 1), env)
                            map[k] = v
                        }
                    }
                }
                return map
            }

            // f-string
            if ((s.startsWith("f\"") && s.endsWith("\"")) || (s.startsWith("f'") && s.endsWith("'"))) {
                val template = s.substring(2, s.lastIndex)
                return Regex("\\{([^}]+)\\}").replace(template) { match ->
                    formatPyValue(evalExpr(match.groupValues[1], env))
                }
            }

            // String literal
            if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
                return s.substring(1, s.lastIndex)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
            }

            // Literals
            when (s) {
                "True" -> return true
                "False" -> return false
                "None" -> return null
            }
            s.toLongOrNull()?.let { return it }
            s.toDoubleOrNull()?.let { return it }

            // Indexing obj[idx]
            if (s.endsWith("]")) {
                val openBracket = findMatchingOpen(s, s.lastIndex, '[', ']')
                if (openBracket > 0) {
                    val target = evalExpr(s.substring(0, openBracket), env)
                    val indexStr = s.substring(openBracket + 1, s.lastIndex).trim()
                    val key = evalExpr(indexStr, env)
                    return when (target) {
                        is List<*> -> {
                            val idx = toDouble(key).toInt()
                            val r = if (idx < 0) target.size + idx else idx
                            target.getOrNull(r)
                        }
                        is Map<*, *> -> target[key]
                        is String -> {
                            val idx = toDouble(key).toInt()
                            val r = if (idx < 0) target.length + idx else idx
                            target.getOrNull(r)?.toString()
                        }
                        else -> null
                    }
                }
            }

            // Method call obj.method(args) or Function call fn(args)
            if (s.endsWith(")")) {
                val openParen = findMatchingOpen(s, s.lastIndex, '(', ')')
                if (openParen > 0) {
                    val callee = s.substring(0, openParen).trim()
                    val argStr = s.substring(openParen + 1, s.lastIndex).trim()
                    val args = if (argStr.isEmpty()) emptyList() else splitTopLevelArgs(argStr).map { evalExpr(it, env) }
                    val dotIdx = findLastTopLevelDot(callee)
                    if (dotIdx > 0) {
                        val receiverName = callee.substring(0, dotIdx).trim()
                        val methodName = callee.substring(dotIdx + 1).trim()
                        if (receiverName == "math") {
                            return callMath(methodName, args)
                        }
                        val receiver = evalExpr(receiverName, env)
                        return callMethod(receiver, methodName, args)
                    }
                    return callFunction(callee, args)
                }
            }

            // Variable lookup
            if (env.containsKey(s)) return env[s]
            if (globals.containsKey(s)) return globals[s]
            if (s == "math.pi") return Math.PI
            if (s == "math.e") return Math.E
            throw IllegalArgumentException("Undefined identifier: $s")
        }

        @Suppress("UNCHECKED_CAST")
        private fun callMethod(receiver: Any?, method: String, args: List<Any?>): Any? {
            return when (receiver) {
                is MutableList<*> -> {
                    val list = receiver as MutableList<Any?>
                    when (method) {
                        "append" -> { list.add(args.firstOrNull()); null }
                        "pop" -> if (list.isEmpty()) null else list.removeAt(list.lastIndex)
                        "reverse" -> { list.reverse(); null }
                        "sort" -> { list.sortBy { toDoubleOrZero(it) }; null }
                        else -> throw IllegalArgumentException("Unsupported list method: $method")
                    }
                }
                is String -> when (method) {
                    "upper" -> receiver.uppercase()
                    "lower" -> receiver.lowercase()
                    "strip" -> receiver.trim()
                    "split" -> receiver.split(args.firstOrNull()?.toString() ?: " ").toMutableList()
                    "replace" -> receiver.replace(
                        args.getOrNull(0)?.toString().orEmpty(),
                        args.getOrNull(1)?.toString().orEmpty()
                    )
                    "join" -> {
                        val seq = args.firstOrNull() as? List<*> ?: emptyList<Any?>()
                        seq.joinToString(receiver) { formatPyValue(it) }
                    }
                    else -> throw IllegalArgumentException("Unsupported str method: $method")
                }
                is Map<*, *> -> when (method) {
                    "keys" -> receiver.keys.toMutableList()
                    "values" -> receiver.values.toMutableList()
                    "get" -> receiver[args.getOrNull(0)] ?: args.getOrNull(1)
                    else -> throw IllegalArgumentException("Unsupported dict method: $method")
                }
                else -> throw IllegalArgumentException("Cannot invoke .$method() on $receiver")
            }
        }

        private fun callMath(fn: String, args: List<Any?>): Any {
            val x = toDouble(args.firstOrNull())
            return simplifyNumber(
                when (fn) {
                    "sqrt" -> sqrt(x)
                    "sin" -> sin(x)
                    "cos" -> cos(x)
                    "tan" -> tan(x)
                    "log" -> ln(x)
                    "log10" -> log10(x)
                    "floor" -> floor(x)
                    "ceil" -> ceil(x)
                    "fabs" -> abs(x)
                    else -> throw IllegalArgumentException("Unsupported math.$fn")
                }
            )
        }

        private fun callFunction(name: String, args: List<Any?>): Any? {
            functions[name]?.let { fn ->
                val localEnv = globals.toMutableMap()
                fn.params.forEachIndexed { idx, p -> localEnv[p] = args.getOrNull(idx) }
                return try {
                    execBlock(fn.body, localEnv)
                    null
                } catch (ret: ReturnSignal) {
                    ret.value
                }
            }

            return when (name) {
                "print" -> {
                    logs.add(SandboxLogLevel.INFO to args.joinToString(" ") { formatPyValue(it) })
                    null
                }
                "range" -> {
                    val (start, stop, step) = when (args.size) {
                        1 -> Triple(0L, toDouble(args[0]).toLong(), 1L)
                        2 -> Triple(toDouble(args[0]).toLong(), toDouble(args[1]).toLong(), 1L)
                        else -> Triple(toDouble(args[0]).toLong(), toDouble(args[1]).toLong(), toDouble(args[2]).toLong())
                    }
                    require(step != 0L) { "range() step cannot be 0" }
                    val out = mutableListOf<Any?>()
                    var v = start
                    while (if (step > 0) v < stop else v > stop) {
                        out.add(v)
                        v += step
                        if (out.size > 5000) break
                    }
                    out
                }
                "len" -> when (val x = args.firstOrNull()) {
                    is List<*> -> x.size.toLong()
                    is Map<*, *> -> x.size.toLong()
                    is String -> x.length.toLong()
                    else -> 0L
                }
                "sum" -> {
                    val list = args.firstOrNull() as? List<*> ?: emptyList<Any?>()
                    simplifyNumber(list.sumOf { toDouble(it) })
                }
                "min" -> {
                    val list = if (args.size == 1 && args[0] is List<*>) args[0] as List<*> else args
                    simplifyNumber(list.minOf { toDouble(it) })
                }
                "max" -> {
                    val list = if (args.size == 1 && args[0] is List<*>) args[0] as List<*> else args
                    simplifyNumber(list.maxOf { toDouble(it) })
                }
                "abs" -> simplifyNumber(abs(toDouble(args.firstOrNull())))
                "round" -> {
                    val value = toDouble(args.firstOrNull())
                    val digits = args.getOrNull(1)?.let { toDouble(it).toInt() } ?: 0
                    if (digits <= 0) round(value).toLong()
                    else {
                        val factor = 10.0.pow(digits)
                        round(value * factor) / factor
                    }
                }
                "sorted" -> {
                    val list = (args.firstOrNull() as? List<*>)?.toMutableList() ?: mutableListOf()
                    list.sortBy { toDoubleOrZero(it) }
                    list
                }
                "int" -> toDouble(args.firstOrNull()).toLong()
                "float" -> toDouble(args.firstOrNull())
                "str" -> formatPyValue(args.firstOrNull())
                "bool" -> isTruthy(args.firstOrNull())
                "enumerate" -> {
                    val list = args.firstOrNull() as? List<*> ?: emptyList<Any?>()
                    list.mapIndexed { idx, v -> listOf(idx.toLong(), v) }.toMutableList()
                }
                "zip" -> {
                    val a = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
                    val b = args.getOrNull(1) as? List<*> ?: emptyList<Any?>()
                    a.zip(b) { x, y -> listOf(x, y) }.toMutableList()
                }
                else -> throw IllegalArgumentException("Unknown function: $name()")
            }
        }

        private fun toIterable(value: Any?, lineNo: Int): List<Any?> = when (value) {
            is List<*> -> value
            is String -> value.map { it.toString() }
            is Map<*, *> -> value.keys.toList()
            else -> throw IllegalArgumentException("Object is not iterable at line $lineNo")
        }

        private fun isTruthy(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            is Collection<*> -> v.isNotEmpty()
            is Map<*, *> -> v.isNotEmpty()
            else -> true
        }

        private fun valuesEqual(a: Any?, b: Any?): Boolean {
            if (a is Number && b is Number) return a.toDouble() == b.toDouble()
            return a == b
        }

        private fun addValues(a: Any?, b: Any?): Any {
            if (a is String || b is String) return formatPyValue(a) + formatPyValue(b)
            if (a is List<*> && b is List<*>) return (a + b).toMutableList()
            return simplifyNumber(toDouble(a) + toDouble(b))
        }

        private fun numericSubtract(a: Any?, b: Any?): Any =
            simplifyNumber(toDouble(a) - toDouble(b))

        private fun multiplyValues(a: Any?, b: Any?): Any {
            if (a is String && b is Number) return a.repeat(b.toInt().coerceIn(0, 500))
            return simplifyNumber(toDouble(a) * toDouble(b))
        }

        private fun toDouble(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            is String -> v.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        private fun toDoubleOrZero(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            else -> 0.0
        }

        private fun simplifyNumber(d: Double): Number {
            return if (d == d.toLong().toDouble() && abs(d) < 1e15) d.toLong() else d
        }

        private fun formatPyValue(v: Any?): String = when (v) {
            null -> "None"
            is Boolean -> if (v) "True" else "False"
            is List<*> -> "[${v.joinToString(", ") { formatPyValue(it) }}]"
            is Map<*, *> -> "{${v.entries.joinToString(", ") { "${formatPyValue(it.key)}: ${formatPyValue(it.value)}" }}}"
            else -> v.toString()
        }

        private fun scanTopLevel(s: String, onChar: (Int, Char) -> Boolean): Int {
            var depth = 0
            var inSingle = false
            var inDouble = false
            for (i in s.indices) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                        else -> if (depth == 0 && onChar(i, c)) return i
                    }
                }
            }
            return -1
        }

        private fun findTopLevelToken(s: String, token: String): Int {
            var depth = 0
            var inSingle = false
            var inDouble = false
            for (i in 0..(s.length - token.length)) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                    }
                    if (depth == 0 && s.startsWith(token, i)) return i
                }
            }
            return -1
        }

        private fun findAssignmentEquals(s: String): Int {
            var depth = 0
            var inSingle = false
            var inDouble = false
            for (i in s.indices) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                        '=' -> if (depth == 0) {
                            val prev = s.getOrNull(i - 1)
                            val next = s.getOrNull(i + 1)
                            if (prev !in setOf('=', '!', '<', '>') && next != '=') return i
                        }
                    }
                }
            }
            return -1
        }

        private fun findTopLevelKeyword(s: String, kw: String): Int? {
            var depth = 0
            var inSingle = false
            var inDouble = false
            for (i in 0..(s.length - kw.length)) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                    }
                    if (depth == 0 && s.startsWith(kw, i)) {
                        val before = s.getOrNull(i - 1)
                        val after = s.getOrNull(i + kw.length)
                        if (before == ' ' && after == ' ') return i
                    }
                }
            }
            return null
        }

        private fun findTopLevelComparison(s: String, op: String): Int {
            var depth = 0
            var inSingle = false
            var inDouble = false
            for (i in 0..(s.length - op.length)) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                    }
                    if (depth == 0 && s.startsWith(op, i)) {
                        if ((op == "<" || op == ">") && s.getOrNull(i + 1) == '=') continue
                        return i
                    }
                }
            }
            return -1
        }

        private fun findLastTopLevelAddSub(s: String): Pair<Int, Char>? {
            var depth = 0
            var inSingle = false
            var inDouble = false
            var found: Pair<Int, Char>? = null
            for (i in s.indices) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                        '+', '-' -> if (depth == 0 && i > 0) {
                            val prev = s.substring(0, i).trimEnd().lastOrNull()
                            if (prev != null && prev !in setOf('+', '-', '*', '/', '%', '(', '[', '{', ',', '=', '<', '>', '!')) {
                                found = i to c
                            }
                        }
                    }
                }
            }
            return found
        }

        private fun findLastTopLevelMulDiv(s: String): Pair<Int, String>? {
            var depth = 0
            var inSingle = false
            var inDouble = false
            var found: Pair<Int, String>? = null
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                        '*' -> if (depth == 0) {
                            if (s.getOrNull(i + 1) == '*') {
                                i += 2
                                continue
                            }
                            found = i to "*"
                        }
                        '/' -> if (depth == 0) {
                            if (s.getOrNull(i + 1) == '/') {
                                found = i to "//"
                                i += 2
                                continue
                            }
                            found = i to "/"
                        }
                        '%' -> if (depth == 0) found = i to "%"
                    }
                }
                i++
            }
            return found
        }

        private fun findLastTopLevelDot(s: String): Int {
            var depth = 0
            var inSingle = false
            var inDouble = false
            var found = -1
            for (i in s.indices) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                        '.' -> if (depth == 0) found = i
                    }
                }
            }
            return found
        }

        private fun matchingCloseIndex(s: String, openIdx: Int): Int {
            val open = s[openIdx]
            val close = when (open) { '(' -> ')'; '[' -> ']'; '{' -> '}'; else -> return -1 }
            var depth = 0
            var inSingle = false
            var inDouble = false
            for (i in openIdx until s.length) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    if (c == open) depth++
                    else if (c == close && --depth == 0) return i
                }
            }
            return -1
        }

        private fun findMatchingOpen(s: String, closeIdx: Int, open: Char, close: Char): Int {
            var depth = 0
            var inSingle = false
            var inDouble = false
            for (i in closeIdx downTo 0) {
                val c = s[i]
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    if (c == close) depth++
                    else if (c == open && --depth == 0) return i
                }
            }
            return -1
        }

        private fun splitTopLevelArgs(s: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var depth = 0
            var inSingle = false
            var inDouble = false
            for (c in s) {
                if (c == '\'' && !inDouble) inSingle = !inSingle
                else if (c == '"' && !inSingle) inDouble = !inDouble
                else if (!inSingle && !inDouble) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                        ',' -> if (depth == 0) {
                            out.add(cur.toString().trim())
                            cur.clear()
                            continue
                        }
                    }
                }
                cur.append(c)
            }
            if (cur.isNotBlank()) out.add(cur.toString().trim())
            return out
        }
    }
}
