package com.example.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Model Context Protocol (MCP) JSON-RPC 2.0 client over HTTP / Streamable-HTTP.
 * Supports initialize handshake, Mcp-Session-Id propagation, application/json & SSE responses,
 * tools/list discovery, and tools/call execution.
 */
class McpClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val rpcId = AtomicInteger(1)

    suspend fun discoverTools(server: McpServerEntity): Result<List<McpToolDefinition>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = normalizeEndpoint(server.endpointUrl)
                val sessionId = initializeSession(url, server.authHeader)

                val listResp = sendRpc(
                    url = url,
                    authHeader = server.authHeader,
                    sessionId = sessionId,
                    method = "tools/list",
                    params = JSONObject()
                )

                val resultObj = listResp.optJSONObject("result")
                    ?: throw IOException(extractRpcError(listResp) ?: "MCP server returned no tools/list result.")

                val toolsArr = resultObj.optJSONArray("tools") ?: JSONArray()
                buildList {
                    for (i in 0 until toolsArr.length()) {
                        val t = toolsArr.optJSONObject(i) ?: continue
                        val name = t.optString("name").trim()
                        if (name.isEmpty()) continue
                        val desc = t.optString("description").trim()
                        val schema = t.optJSONObject("inputSchema")?.toString() ?: "{}"
                        add(
                            McpToolDefinition(
                                serverId = server.id,
                                serverName = server.name,
                                name = name,
                                description = desc,
                                inputSchemaJson = schema
                            )
                        )
                    }
                }
            }
        }

    suspend fun callTool(
        server: McpServerEntity,
        toolName: String,
        arguments: JSONObject
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalizeEndpoint(server.endpointUrl)
            val sessionId = initializeSession(url, server.authHeader)

            val params = JSONObject().apply {
                put("name", toolName)
                put("arguments", arguments)
            }
            val callResp = sendRpc(
                url = url,
                authHeader = server.authHeader,
                sessionId = sessionId,
                method = "tools/call",
                params = params
            )

            extractRpcError(callResp)?.let { throw IOException(it) }

            val resultObj = callResp.optJSONObject("result")
                ?: return@runCatching callResp.toString()

            val contentArr = resultObj.optJSONArray("content")
            if (contentArr != null && contentArr.length() > 0) {
                buildString {
                    for (i in 0 until contentArr.length()) {
                        val part = contentArr.optJSONObject(i) ?: continue
                        val text = part.optString("text")
                        if (text.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append(text)
                        } else if (part.has("data")) {
                            if (isNotEmpty()) append("\n")
                            append("[${part.optString("type", "data")}: ${part.optString("mimeType")}]")
                        }
                    }
                }.ifBlank { resultObj.toString() }
            } else {
                resultObj.toString()
            }
        }
    }

    private fun initializeSession(url: String, authHeader: String): String? {
        val initParams = JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject())
            put(
                "clientInfo",
                JSONObject().apply {
                    put("name", "LocalMind")
                    put("version", "1.0.0")
                }
            )
        }
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", rpcId.getAndIncrement())
            put("method", "initialize")
            put("params", initParams)
        }
        val reqBuilder = buildBaseRequest(url, authHeader, sessionId = null)
            .post(payload.toString().toRequestBody(jsonMediaType))

        var sessionId: String? = null
        runCatching {
            client.newCall(reqBuilder.build()).execute().use { response ->
                sessionId = response.header("Mcp-Session-Id")
                    ?: response.header("mcp-session-id")
            }
        }

        // Send notifications/initialized (best-effort, per MCP spec)
        runCatching {
            val notify = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
            val notifyReq = buildBaseRequest(url, authHeader, sessionId)
                .post(notify.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(notifyReq).execute().close()
        }
        return sessionId
    }

    private fun sendRpc(
        url: String,
        authHeader: String,
        sessionId: String?,
        method: String,
        params: JSONObject
    ): JSONObject {
        val id = rpcId.getAndIncrement()
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val request = buildBaseRequest(url, authHeader, sessionId)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("MCP HTTP ${response.code}: ${rawBody.take(220).ifBlank { response.message }}")
            }

            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) {
                throw IOException("Empty response from MCP server.")
            }

            // Streamable-HTTP servers may reply with text/event-stream (SSE)
            if (trimmed.startsWith("event:") || trimmed.startsWith("data:") ||
                response.header("Content-Type")?.contains("text/event-stream", ignoreCase = true) == true
            ) {
                val parsedFromSse = parseJsonFromSse(trimmed)
                if (parsedFromSse != null) return parsedFromSse
            }

            return JSONObject(trimmed)
        }
    }

    private fun parseJsonFromSse(sseText: String): JSONObject? {
        var lastJson: JSONObject? = null
        val dataLines = sseText.lines()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }

        for (line in dataLines) {
            runCatching {
                val obj = JSONObject(line)
                if (obj.has("result") || obj.has("error")) {
                    lastJson = obj
                }
            }
        }
        return lastJson
    }

    private fun buildBaseRequest(
        url: String,
        authHeader: String,
        sessionId: String?
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")

        if (!sessionId.isNullOrBlank()) {
            builder.header("Mcp-Session-Id", sessionId)
        }

        val trimmedAuth = authHeader.trim()
        if (trimmedAuth.isNotEmpty()) {
            val colonIdx = trimmedAuth.indexOf(':')
            if (colonIdx > 0 && !trimmedAuth.startsWith("Bearer ", ignoreCase = true)) {
                val name = trimmedAuth.substring(0, colonIdx).trim()
                val value = trimmedAuth.substring(colonIdx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    builder.header(name, value)
                }
            } else {
                val value = if (trimmedAuth.startsWith("Bearer ", ignoreCase = true)) {
                    trimmedAuth
                } else {
                    "Bearer $trimmedAuth"
                }
                builder.header("Authorization", value)
            }
        }
        return builder
    }

    private fun normalizeEndpoint(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "MCP server URL must start with http:// or https://"
        }
        return trimmed
    }

    private fun extractRpcError(obj: JSONObject): String? {
        val err = obj.optJSONObject("error") ?: return null
        val code = err.optInt("code", -1)
        val msg = err.optString("message", "Unknown JSON-RPC error")
        return "MCP error ($code): $msg"
    }
}
