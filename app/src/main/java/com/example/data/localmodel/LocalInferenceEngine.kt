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
        displayName = "LiteRT-LM (QNN NPU)",
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
            format: String,
            hardwareBackend: LocalHardwareBackend = LocalHardwareBackend.NPU
        ): LocalInferenceEngine {
            val ext = format.lowercase()
            if (preferred != AUTO && ext in preferred.supportedFormats) {
                return preferred
            }
            return when (ext) {
                "gguf" -> LLAMA_CPP
                "litertlm" -> LITERT_LM
                // When NPU is selected, route .task models through LiteRT-LM so Qualcomm QNN HTP NPU is used
                "task" -> if (hardwareBackend == LocalHardwareBackend.NPU) LITERT_LM else MEDIAPIPE_GENAI
                "bin" -> MEDIAPIPE_GENAI
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
            entries.firstOrNull { it.id == id } ?: NPU
    }
}
