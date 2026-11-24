package com.example.data.mlc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Kotlin models & protocol definitions mirroring MLC Chat Android (`mlc-ai/mlc-llm`):
 * - `mlc-chat-config.json` (context_window_size, prefill_chunk_size, sliding_window_size, quantization)
 * - `ndarray-cache.json` (sharded `params_shard_*.bin` weight manifest)
 * - `OpenAIProtocol` JSON-FFI payloads (`chat.completions.create`, `stream_options.include_usage`)
 * - `MlcRuntimeStats` (separate prefill tok/s, decode tok/s, TTFT ms, KV cache reuse)
 */

data class MlcChatConfig(
    val modelType: String,
    val quantization: String,
    val modelLib: String,
    val contextWindowSize: Int,
    val prefillChunkSize: Int,
    val slidingWindowSize: Int,
    val attentionSinkSize: Int,
    val temperature: Float,
    val topP: Float,
    val convTemplateName: String,
    val tokenizerFiles: List<String>
) {
    companion object {
        fun parse(jsonText: String): MlcChatConfig {
            val obj = JSONObject(jsonText)
            val modelConfig = obj.optJSONObject("model_config")
            val convObj = obj.optJSONObject("conv_template")
            val tokArray = obj.optJSONArray("tokenizer_files")
            val tokFiles = buildList {
                if (tokArray != null) {
                    for (i in 0 until tokArray.length()) {
                        tokArray.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.ifEmpty { listOf("tokenizer.json", "tokenizer_config.json") }

            return MlcChatConfig(
                modelType = obj.optString("model_type", "llama"),
                quantization = obj.optString("quantization", "q4f16_1"),
                modelLib = obj.optString("model_lib", ""),
                contextWindowSize = obj.optInt(
                    "context_window_size",
                    modelConfig?.optInt("context_window_size", 4096) ?: 4096
                ).coerceIn(512, 32768),
                prefillChunkSize = obj.optInt(
                    "prefill_chunk_size",
                    modelConfig?.optInt("prefill_chunk_size", 512) ?: 512
                ).coerceIn(64, 4096),
                slidingWindowSize = obj.optInt(
                    "sliding_window_size",
                    modelConfig?.optInt("sliding_window_size", -1) ?: -1
                ),
                attentionSinkSize = obj.optInt(
                    "attention_sink_size",
                    modelConfig?.optInt("attention_sink_size", 4) ?: 4
                ),
                temperature = obj.optDouble("temperature", 0.7).toFloat(),
                topP = obj.optDouble("top_p", 0.95).toFloat(),
                convTemplateName = convObj?.optString("name")
                    ?: obj.optString("conv_template", "chatml"),
                tokenizerFiles = tokFiles
            )
        }

        fun loadFromDir(modelDir: File): MlcChatConfig? {
            val cfgFile = File(modelDir, "mlc-chat-config.json")
            if (!cfgFile.exists()) return null
            return runCatching { parse(cfgFile.readText()) }.getOrNull()
        }
    }
}

data class MlcShardRecord(
    val dataPath: String,
    val nbytes: Long,
    val md5sum: String
)

data class MlcNdArrayCache(
    val records: List<MlcShardRecord>
) {
    val totalBytes: Long get() = records.sumOf { it.nbytes }

    companion object {
        fun parse(jsonText: String): MlcNdArrayCache {
            val obj = JSONObject(jsonText)
            val arr = obj.optJSONArray("records") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val rec = arr.optJSONObject(i) ?: continue
                    val dataPath = rec.optString("dataPath")
                    if (dataPath.isBlank()) continue
                    add(
                        MlcShardRecord(
                            dataPath = dataPath,
                            nbytes = rec.optLong("nbytes", 0L),
                            md5sum = rec.optString("md5sum", "")
                        )
                    )
                }
            }
            return MlcNdArrayCache(records = list)
        }
    }
}

/**
 * Runtime performance metrics following MLC Chat Android (`RuntimeStats`):
 * separates prompt prefill speed from decode speed and tracks Time-To-First-Token (TTFT)
 * and persistent KV-cache hit status.
 */
data class MlcRuntimeStats(
    val ttftMs: Long = 0L,
    val prefillTokensPerSec: Float? = null,
    val decodeTokensPerSec: Float? = null,
    val kvCacheReused: Boolean = false,
    val backendLabel: String = "",
    val prefillChunkSize: Int = 512,
    val contextWindowSize: Int = 2048
) {
    fun formatCompact(): String = buildString {
        decodeTokensPerSec?.let { append(String.format("%.1f tok/s", it)) }
        if (ttftMs > 0L) {
            if (isNotEmpty()) append(" · ")
            append("TTFT ${ttftMs}ms")
        }
        if (kvCacheReused) {
            if (isNotEmpty()) append(" · ")
            append("KV Hit")
        }
    }
}

data class MlcModelPreset(
    val repoId: String,
    val displayName: String,
    val modelLibPrefix: String,
    val quantization: String,
    val estimatedVramMb: Int
)

object MlcModelCatalog {
    const val HF_AUTHOR = "mlc-ai"

    val curatedPresets: List<MlcModelPreset> = listOf(
        MlcModelPreset(
            repoId = "mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
            displayName = "Qwen2.5 1.5B (q4f16_1 MLC)",
            modelLibPrefix = "qwen2_q4f16_1",
            quantization = "q4f16_1",
            estimatedVramMb = 1400
        ),
        MlcModelPreset(
            repoId = "mlc-ai/Llama-3.2-1B-Instruct-q4f16_1-MLC",
            displayName = "Llama 3.2 1B (q4f16_1 MLC)",
            modelLibPrefix = "llama_q4f16_1",
            quantization = "q4f16_1",
            estimatedVramMb = 1100
        ),
        MlcModelPreset(
            repoId = "mlc-ai/Llama-3.2-3B-Instruct-q4f16_1-MLC",
            displayName = "Llama 3.2 3B (q4f16_1 MLC)",
            modelLibPrefix = "llama_q4f16_1",
            quantization = "q4f16_1",
            estimatedVramMb = 2600
        ),
        MlcModelPreset(
            repoId = "mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC",
            displayName = "Qwen2.5 3B (q4f16_1 MLC)",
            modelLibPrefix = "qwen2_q4f16_1",
            quantization = "q4f16_1",
            estimatedVramMb = 2500
        ),
        MlcModelPreset(
            repoId = "mlc-ai/SmolLM2-1.7B-Instruct-q4f16_1-MLC",
            displayName = "SmolLM2 1.7B (q4f16_1 MLC)",
            modelLibPrefix = "llama_q4f16_1",
            quantization = "q4f16_1",
            estimatedVramMb = 1500
        ),
        MlcModelPreset(
            repoId = "mlc-ai/gemma-2-2b-it-q4f16_1-MLC",
            displayName = "Gemma 2 2B IT (q4f16_1 MLC)",
            modelLibPrefix = "gemma2_q4f16_1",
            quantization = "q4f16_1",
            estimatedVramMb = 2100
        ),
        MlcModelPreset(
            repoId = "mlc-ai/Phi-3.5-mini-instruct-q4f16_1-MLC",
            displayName = "Phi-3.5 Mini 3.8B (q4f16_1 MLC)",
            modelLibPrefix = "phi3_q4f16_1",
            quantization = "q4f16_1",
            estimatedVramMb = 2900
        )
    )

    fun isMlcRepo(repoId: String): Boolean {
        val lower = repoId.lowercase()
        return lower.startsWith("$HF_AUTHOR/") ||
            lower.endsWith("-mlc") ||
            lower.contains("-q4f16_1-mlc") ||
            lower.contains("-q4f32_1-mlc")
    }

    /**
     * Builds an OpenAI-compatible JSON-FFI chat completion request payload
     * matching `ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionRequest`.
     */
    fun buildJsonFfiRequest(
        messages: List<Pair<String, String>>,
        temperature: Float,
        maxTokens: Int,
        topP: Float = 0.95f
    ): String {
        val msgArray = JSONArray()
        messages.forEach { (role, content) ->
            msgArray.put(
                JSONObject().apply {
                    put("role", role)
                    put("content", content)
                }
            )
        }
        return JSONObject().apply {
            put("messages", msgArray)
            put("stream", true)
            put("temperature", temperature.toDouble())
            put("top_p", topP.toDouble())
            put("max_tokens", maxTokens)
            put(
                "stream_options",
                JSONObject().apply { put("include_usage", true) }
            )
        }.toString()
    }
}
