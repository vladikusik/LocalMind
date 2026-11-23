package com.example.data.localmodel

enum class LocalInferenceEngine(
    val id: String,
    val displayName: String,
    val supportedFormats: Set<String>
) {
    AUTO(
        id = "auto",
        displayName = "Auto (Best Match)",
        supportedFormats = setOf("gguf", "litertlm", "task", "bin")
    ),
    LLAMA_CPP(
        id = "llama_cpp",
        displayName = "llama.cpp (GGUF)",
        supportedFormats = setOf("gguf")
    ),
    LITERT_LM(
        id = "litert_lm",
        displayName = "LiteRT-LM (.litertlm)",
        supportedFormats = setOf("litertlm", "task")
    ),
    MEDIAPIPE_GENAI(
        id = "mediapipe",
        displayName = "MediaPipe GenAI (.task/.bin)",
        supportedFormats = setOf("task", "bin")
    );

    companion object {
        fun fromId(id: String?): LocalInferenceEngine =
            entries.firstOrNull { it.id == id } ?: AUTO

        fun resolveForFormat(
            preferred: LocalInferenceEngine,
            format: String
        ): LocalInferenceEngine {
            val ext = format.lowercase()
            if (preferred != AUTO && ext in preferred.supportedFormats) {
                return preferred
            }
            return when (ext) {
                "gguf" -> LLAMA_CPP
                "litertlm" -> LITERT_LM
                "task", "bin" -> MEDIAPIPE_GENAI
                else -> LLAMA_CPP
            }
        }
    }
}

enum class LocalHardwareBackend(val id: String) {
    CPU("cpu"),
    GPU("gpu"),
    NPU("npu");

    companion object {
        fun fromId(id: String?): LocalHardwareBackend =
            entries.firstOrNull { it.id == id } ?: CPU
    }
}
