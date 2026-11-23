package com.example.data.mcp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val endpointUrl: String,
    val authHeader: String = "",
    val isEnabled: Boolean = true,
    val cachedToolsJson: String = "[]",
    val lastStatus: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_profiles")
data class AgentProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val iconKey: String = "spark",
    val maxSteps: Int = 5,
    val enableBuiltInTools: Boolean = true,
    val enableMcpServers: Boolean = true,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_notes")
data class AgentNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
)

data class McpToolDefinition(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}"
) {
    companion object {
        fun encodeList(tools: List<McpToolDefinition>): String {
            val arr = JSONArray()
            tools.forEach { t ->
                arr.put(
                    JSONObject().apply {
                        put("serverId", t.serverId)
                        put("serverName", t.serverName)
                        put("name", t.name)
                        put("description", t.description)
                        put("inputSchemaJson", t.inputSchemaJson)
                    }
                )
            }
            return arr.toString()
        }

        fun decodeList(raw: String?): List<McpToolDefinition> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val name = obj.optString("name").trim()
                        if (name.isEmpty()) continue
                        add(
                            McpToolDefinition(
                                serverId = obj.optString("serverId"),
                                serverName = obj.optString("serverName"),
                                name = name,
                                description = obj.optString("description"),
                                inputSchemaJson = obj.optString("inputSchemaJson", "{}")
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}

@Dao
interface McpAgentDao {
    @Query("SELECT * FROM mcp_servers ORDER BY updatedAt DESC")
    fun observeMcpServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE isEnabled = 1 ORDER BY updatedAt DESC")
    suspend fun getEnabledMcpServers(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id LIMIT 1")
    suspend fun getMcpServerById(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMcpServer(server: McpServerEntity)

    @Query("UPDATE mcp_servers SET isEnabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setMcpServerEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteMcpServer(id: String)

    @Query("SELECT * FROM agent_profiles ORDER BY isBuiltIn DESC, createdAt ASC")
    fun observeAgents(): Flow<List<AgentProfileEntity>>

    @Query("SELECT * FROM agent_profiles WHERE id = :id LIMIT 1")
    suspend fun getAgentById(id: String): AgentProfileEntity?

    @Query("SELECT COUNT(*) FROM agent_profiles")
    suspend fun countAgents(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgent(agent: AgentProfileEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAgentsIfAbsent(agents: List<AgentProfileEntity>)

    @Query("DELETE FROM agent_profiles WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustomAgent(id: String)

    @Query("SELECT * FROM agent_notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<AgentNoteEntity>>

    @Query("SELECT * FROM agent_notes ORDER BY updatedAt DESC LIMIT 50")
    suspend fun getNotesSnapshot(): List<AgentNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: AgentNoteEntity): Long

    @Query("DELETE FROM agent_notes WHERE id = :id")
    suspend fun deleteNote(id: Long)
}

object DefaultAgents {
    fun builtInList(): List<AgentProfileEntity> = listOf(
        AgentProfileEntity(
            id = "agent_sandbox_builder",
            name = "Sandbox App Builder",
            description = "Builds live interactive HTML/CSS/JS apps, Canvas games, Python scripts & SQL demos directly inside the Code Sandbox.",
            systemPrompt = "You are an autonomous Code Sandbox Builder Agent. Whenever the user asks for an app, mini-game, calculator, visualization, animation, or script, ALWAYS call `sandbox_write_code` with complete, self-contained runnable code (HTML/CSS/JS, JavaScript, Python, or SQL) and call `sandbox_run_code` to verify Python/SQL output. Make UI visuals gorgeous, responsive, and immediately usable, then summarize what you built in the user's language.",
            iconKey = "sandbox",
            maxSteps = 6,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 1L
        ),
        AgentProfileEntity(
            id = "agent_coder",
            name = "Code Architect",
            description = "Designs clean software, tests algorithms in the Code Sandbox, and writes production-grade code.",
            systemPrompt = "You are a senior Code Architect Agent. Think systematically about edge cases, architecture, and performance. You can use `sandbox_write_code` and `sandbox_run_code` to prototype and verify algorithms. Respond with clean, runnable code blocks and concise explanations in the user's language.",
            iconKey = "code",
            maxSteps = 6,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 2L
        ),
        AgentProfileEntity(
            id = "agent_debugger",
            name = "Bug Hunter & Debugger",
            description = "Finds root causes in stack traces, reproduces bugs in the Code Sandbox, and provides verified fixes.",
            systemPrompt = "You are a relentless Bug Hunter & Debugger Agent. Inspect code carefully for off-by-one errors, null hazards, race conditions, and logic bugs. Reproduce and verify fixes using `sandbox_run_code` or `calculate_math` whenever possible, then explain the root cause and exact patch clearly in the user's language.",
            iconKey = "debug",
            maxSteps = 6,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 3L
        ),
        AgentProfileEntity(
            id = "agent_ui_designer",
            name = "Frontend & UI Designer",
            description = "Creates modern Liquid Glass web interfaces, CSS animations, and interactive prototypes directly in HTML Sandbox.",
            systemPrompt = "You are an elite Frontend & UI Designer Agent. Craft sleek, responsive, mobile-friendly HTML/CSS/JS prototypes with modern typography, smooth micro-interactions, and clean layout. Write prototypes directly into the sandbox via `sandbox_write_code` with language='html' so the user can interact with them immediately.",
            iconKey = "design",
            maxSteps = 5,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 4L
        ),
        AgentProfileEntity(
            id = "agent_sql",
            name = "SQL & Database Engineer",
            description = "Designs normalized schemas, writes analytical queries, and tests them live in the in-memory SQLite Sandbox.",
            systemPrompt = "You are a Principal SQL & Database Engineer Agent. Design clean schemas, window functions, joins, and indexes. Proactively test your SQLite queries using `sandbox_run_code` with language='sql' so every query is verified before answering in the user's language.",
            iconKey = "sql",
            maxSteps = 6,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 5L
        ),
        AgentProfileEntity(
            id = "agent_research",
            name = "Research Agent",
            description = "Verifies facts via Wikipedia, fetches web pages, checks dates, and synthesizes clear answers.",
            systemPrompt = "You are an autonomous Research Agent. Break down questions carefully, use available Wikipedia, web fetch, datetime, and MCP tools whenever external facts or up-to-date details help, and always deliver a clear, well-structured Markdown answer in the user's language.",
            iconKey = "research",
            maxSteps = 6,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 6L
        ),
        AgentProfileEntity(
            id = "agent_analyst",
            name = "Math & Data Analyst",
            description = "Uses exact math evaluation and Python Sandbox scripts to solve calculations, finance, and analytics.",
            systemPrompt = "You are a precision Math & Data Analyst Agent. Never guess arithmetic or formulas—always call `calculate_math` or `sandbox_run_code` (Python) for numerical computations and verify assumptions step by step. Present results clearly with Markdown tables in the user's language.",
            iconKey = "math",
            maxSteps = 6,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 7L
        ),
        AgentProfileEntity(
            id = "agent_security",
            name = "Cybersecurity Auditor",
            description = "Audits code for OWASP vulnerabilities, auth flaws, injection risks, and provides hardened patches.",
            systemPrompt = "You are a Cybersecurity Auditor Agent. Analyze code, configs, and architectures for security risks (SQLi, XSS, SSRF, insecure storage, weak auth, secret leaks). Rate severity clearly and provide concrete secure replacements in the user's language.",
            iconKey = "security",
            maxSteps = 5,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 8L
        ),
        AgentProfileEntity(
            id = "agent_tutor",
            name = "Study Mentor & Tutor",
            description = "Explains programming, math, and science with crystal-clear analogies, mini-quizzes, and live sandbox demos.",
            systemPrompt = "You are an encouraging Study Mentor & Tutor Agent. Adapt explanations to the learner's level, break complex topics into intuitive steps, create interactive examples in the Code Sandbox when helpful, and check understanding with short practice questions in the user's language.",
            iconKey = "tutor",
            maxSteps = 5,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 9L
        ),
        AgentProfileEntity(
            id = "agent_translator",
            name = "Translator & Linguist",
            description = "Translates naturally across Ukrainian, Russian, English & more with tone adaptation and nuance notes.",
            systemPrompt = "You are a professional Translator & Linguist Agent. Preserve meaning, idioms, tone, and formatting accurately instead of literal word-for-word translation. Offer brief alternative phrasings when tone matters.",
            iconKey = "translate",
            maxSteps = 4,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 10L
        ),
        AgentProfileEntity(
            id = "agent_writer",
            name = "Copywriter & Editor",
            description = "Polishes articles, technical docs, pitches, emails, and social posts with sharp structure and clarity.",
            systemPrompt = "You are an expert Copywriter & Editor Agent. Eliminate fluff, sharpen hooks and structure, match the requested voice and audience, and deliver publication-ready Markdown copy in the user's language.",
            iconKey = "writer",
            maxSteps = 4,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 11L
        ),
        AgentProfileEntity(
            id = "agent_memory",
            name = "Personal Memory Agent",
            description = "Remembers your preferences, plans, and project notes locally using persistent MCP memory tools.",
            systemPrompt = "You are a Personal Memory Agent. Check `list_memory_notes` when the user refers to past details or preferences, and use `save_memory_note` whenever the user asks you to remember something or shares key personal/project facts. Reply warmly and concisely in the user's language.",
            iconKey = "memory",
            maxSteps = 5,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 12L
        ),
        AgentProfileEntity(
            id = "agent_image_artist",
            name = "Local Diffusion Artist",
            description = "Crafts rich Stable Diffusion / Flux prompts and generates images locally via the on-device Image Studio tool.",
            systemPrompt = "You are an expert Local Diffusion Prompt Engineer & Visual Artist Agent. When the user asks to create, draw, or render an image, craft a vivid, detailed English Stable Diffusion prompt (subject, lighting, lens, composition, atmosphere) plus a clean negative prompt, and call `generate_local_image`. After calling it, summarize the prompt and let the user know the artwork is ready in Image Studio in their language.",
            iconKey = "image",
            maxSteps = 4,
            enableBuiltInTools = true,
            enableMcpServers = true,
            isBuiltIn = true,
            createdAt = 13L
        )
    )
}
