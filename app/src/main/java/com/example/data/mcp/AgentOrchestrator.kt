package com.example.data.mcp

import com.example.data.network.ChatMessageDto
import com.example.data.network.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Multi-step Autonomous Agent & MCP Tool Orchestrator.
 * Works across ALL LLM backends (Free Cloud, OpenAI, Anthropic, Gemini, LM Studio, Ollama, and On-Device engines)
 * by augmenting the system prompt with active MCP tools and executing real <tool_call> cycles.
 */
class AgentOrchestrator(
    private val mcpAgentDao: McpAgentDao,
    private val mcpClient: McpClient,
    private val builtInTools: BuiltInMcpTools
) {

    data class ActiveToolContext(
        val agent: AgentProfileEntity?,
        val tools: List<McpToolDefinition>,
        val serverMap: Map<String, McpServerEntity>
    )

    suspend fun resolveToolContext(
        activeAgentId: String,
        globalBuiltInToolsEnabled: Boolean
    ): ActiveToolContext {
        val agent = activeAgentId.takeIf { it.isNotBlank() }?.let { mcpAgentDao.getAgentById(it) }
        val enabledServers = mcpAgentDao.getEnabledMcpServers()
        val serverMap = enabledServers.associateBy { it.id }

        val allowBuiltIn = if (agent != null) agent.enableBuiltInTools else globalBuiltInToolsEnabled
        val allowMcp = agent?.enableMcpServers ?: true

        val tools = buildList {
            if (allowBuiltIn) {
                addAll(builtInTools.definitions)
            }
            if (allowMcp) {
                enabledServers.forEach { server ->
                    addAll(McpToolDefinition.decodeList(server.cachedToolsJson))
                }
            }
        }

        return ActiveToolContext(
            agent = agent,
            tools = tools,
            serverMap = serverMap
        )
    }

    fun runAgentStream(
        toolContext: ActiveToolContext,
        baseSystemPrompt: String,
        conversationMessages: List<ChatMessageDto>,
        streamStep: (List<ChatMessageDto>) -> Flow<StreamEvent>
    ): Flow<StreamEvent> = flow {
        val maxSteps = (toolContext.agent?.maxSteps ?: 4).coerceIn(1, 8)
        val systemPrompt = buildAugmentedSystemPrompt(
            baseSystemPrompt = baseSystemPrompt,
            agent = toolContext.agent,
            tools = toolContext.tools
        )

        val workingMessages = mutableListOf<ChatMessageDto>()
        if (systemPrompt.isNotBlank()) {
            workingMessages.add(ChatMessageDto(role = "system", content = systemPrompt))
        }
        workingMessages.addAll(conversationMessages)

        var lastModelName: String? = null
        var lastTps: Float? = null

        if (toolContext.agent != null) {
            emit(
                StreamEvent.Chunk(
                    reasoningDelta = "🤖 Agent: ${toolContext.agent.name} (${toolContext.tools.size} tools ready)\n"
                )
            )
        }

        for (step in 1..maxSteps) {
            val stepRawContent = StringBuilder()
            var emittedContentLength = 0
            var detectedToolCallPrefix = false

            streamStep(workingMessages).collect { event ->
                when (event) {
                    is StreamEvent.Chunk -> {
                        if (!event.modelName.isNullOrBlank()) {
                            lastModelName = event.modelName
                        }
                        if (event.reasoningDelta.isNotEmpty()) {
                            emit(
                                StreamEvent.Chunk(
                                    reasoningDelta = event.reasoningDelta,
                                    modelName = lastModelName
                                )
                            )
                        }
                        if (event.contentDelta.isNotEmpty()) {
                            stepRawContent.append(event.contentDelta)
                            val currentText = stepRawContent.toString()

                            if (!detectedToolCallPrefix) {
                                val tagIdx = currentText.indexOf("<tool_call>")
                                val partialTagIdx = findTrailingPartialTag(currentText, "<tool_call>")
                                if (tagIdx != -1) {
                                    detectedToolCallPrefix = true
                                    // Flush any clean prefix before <tool_call> into reasoning trace
                                    val beforeTag = currentText.substring(emittedContentLength, tagIdx)
                                    if (beforeTag.isNotBlank()) {
                                        emit(
                                            StreamEvent.Chunk(
                                                reasoningDelta = beforeTag.trim() + "\n",
                                                modelName = lastModelName
                                            )
                                        )
                                    }
                                    emittedContentLength = currentText.length
                                } else {
                                    // Hold back potential trailing partial "<tool_call>" characters
                                    val safeEnd = if (partialTagIdx != -1) partialTagIdx else currentText.length
                                    if (safeEnd > emittedContentLength) {
                                        val delta = currentText.substring(emittedContentLength, safeEnd)
                                        emittedContentLength = safeEnd
                                        emit(
                                            StreamEvent.Chunk(
                                                contentDelta = delta,
                                                modelName = lastModelName
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is StreamEvent.Completed -> {
                        if (!event.modelName.isNullOrBlank()) {
                            lastModelName = event.modelName
                        }
                        if (event.tokensPerSecond != null) {
                            lastTps = event.tokensPerSecond
                        }
                    }
                }
            }

            val fullStepText = stepRawContent.toString()
            val toolCalls = parseToolCalls(fullStepText)

            if (toolCalls.isEmpty() || step == maxSteps) {
                // Flush any held-back non-tool-call tail
                if (!detectedToolCallPrefix && emittedContentLength < fullStepText.length) {
                    val remaining = fullStepText.substring(emittedContentLength)
                    emit(StreamEvent.Chunk(contentDelta = remaining, modelName = lastModelName))
                } else if (detectedToolCallPrefix && toolCalls.isEmpty()) {
                    // Malformed tag; emit stripped text as answer
                    val cleaned = fullStepText
                        .replace("<tool_call>", "")
                        .replace("</tool_call>", "")
                        .trim()
                    if (cleaned.isNotEmpty()) {
                        emit(StreamEvent.Chunk(contentDelta = cleaned, modelName = lastModelName))
                    }
                }
                break
            }

            // Execute each requested tool call and feed results back to the model
            workingMessages.add(ChatMessageDto(role = "assistant", content = fullStepText))
            val observationBuilder = StringBuilder()

            for (call in toolCalls) {
                val argsPreview = call.arguments.toString().take(180)
                emit(
                    StreamEvent.Chunk(
                        reasoningDelta = "\n⚡ Step $step • MCP Tool: `${call.name}`\nArgs: $argsPreview\n",
                        modelName = lastModelName
                    )
                )

                val result = executeToolCall(call, toolContext)
                val outputText = result.fold(
                    onSuccess = { it.take(4000) },
                    onFailure = { "Tool error: ${it.localizedMessage ?: it.javaClass.simpleName}" }
                )

                val shortTrace = outputText.replace('\n', ' ').take(240)
                emit(
                    StreamEvent.Chunk(
                        reasoningDelta = "↳ Result: $shortTrace${if (outputText.length > 240) "…" else ""}\n",
                        modelName = lastModelName
                    )
                )

                observationBuilder.appendLine("<tool_result name=\"${call.name}\">")
                observationBuilder.appendLine(outputText)
                observationBuilder.appendLine("</tool_result>")
            }

            workingMessages.add(
                ChatMessageDto(
                    role = "user",
                    content = buildString {
                        appendLine("Tool execution results:")
                        append(observationBuilder.toString())
                        appendLine("Use these results to continue. Call another tool if needed, or provide the final answer directly to the user without <tool_call> tags.")
                    }
                )
            )
        }

        emit(StreamEvent.Completed(tokensPerSecond = lastTps, modelName = lastModelName))
    }

    private suspend fun executeToolCall(
        call: ParsedToolCall,
        context: ActiveToolContext
    ): Result<String> {
        if (builtInTools.isBuiltIn(call.name)) {
            return builtInTools.execute(call.name, call.arguments)
        }
        val toolDef = context.tools.firstOrNull { it.name == call.name }
            ?: return Result.failure(IllegalArgumentException("Tool '${call.name}' is not registered or enabled."))

        val server = context.serverMap[toolDef.serverId]
            ?: return Result.failure(IllegalStateException("MCP server '${toolDef.serverName}' is not enabled."))

        return mcpClient.callTool(server, call.name, call.arguments)
    }

    private fun buildAugmentedSystemPrompt(
        baseSystemPrompt: String,
        agent: AgentProfileEntity?,
        tools: List<McpToolDefinition>
    ): String {
        return buildString {
            if (agent != null) {
                appendLine(agent.systemPrompt.trim())
                if (baseSystemPrompt.isNotBlank()) {
                    appendLine()
                    appendLine("Additional user instructions:")
                    appendLine(baseSystemPrompt.trim())
                }
            } else if (baseSystemPrompt.isNotBlank()) {
                appendLine(baseSystemPrompt.trim())
            }

            if (tools.isNotEmpty()) {
                appendLine()
                appendLine("You have access to the following MCP tools:")
                tools.take(24).forEach { tool ->
                    appendLine("- ${tool.name} [${tool.serverName}]: ${tool.description} | schema: ${tool.inputSchemaJson}")
                }
                appendLine()
                appendLine("TOOL CALLING PROTOCOL:")
                appendLine("When you need to call a tool, output ONLY a block in this exact format:")
                appendLine("""<tool_call>{"name":"tool_name","arguments":{"param":"value"}}</tool_call>""")
                appendLine("Do NOT fabricate tool results. Wait for the <tool_result> response. When you have enough information to answer the user, respond directly in clean Markdown without any <tool_call> tags.")
            }
        }.trim()
    }

    private data class ParsedToolCall(
        val name: String,
        val arguments: JSONObject
    )

    private fun parseToolCalls(text: String): List<ParsedToolCall> {
        val regex = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL))
        return regex.findAll(text).mapNotNull { match ->
            val jsonStr = match.groupValues[1].trim()
            runCatching {
                val obj = JSONObject(jsonStr)
                val name = obj.optString("name").trim()
                if (name.isEmpty()) return@runCatching null
                val args = obj.optJSONObject("arguments") ?: JSONObject()
                ParsedToolCall(name = name, arguments = args)
            }.getOrNull()
        }.toList()
    }

    private fun findTrailingPartialTag(text: String, tag: String): Int {
        val maxCheck = minOf(text.length, tag.length - 1)
        for (len in maxCheck downTo 1) {
            if (text.endsWith(tag.substring(0, len))) {
                return text.length - len
            }
        }
        return -1
    }
}
