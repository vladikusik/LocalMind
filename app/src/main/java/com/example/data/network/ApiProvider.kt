package com.example.data.network

enum class ApiProtocol {
    ON_DEVICE_MEDIAPIPE,
    OPENAI_COMPATIBLE,
    OLLAMA,
    ANTHROPIC,
    GEMINI
}

enum class ApiProvider(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val protocol: ApiProtocol,
    val requiresApiKey: Boolean,
    val isLocal: Boolean,
    val isFreeTier: Boolean,
    val suggestedModels: List<String>
) {
    // --- Free Cloud Models ---
    POLLINATIONS_FREE(
        id = "pollinations_free",
        displayName = "Free Cloud (No Key)",
        defaultBaseUrl = "https://text.pollinations.ai/openai",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = false,
        isLocal = false,
        isFreeTier = true,
        suggestedModels = listOf(
            "openai",
            "openai-fast",
            "deepseek",
            "mistral",
            "qwen-coder",
            "llama"
        )
    ),
    OPENROUTER_FREE(
        id = "openrouter_free",
        displayName = "OpenRouter Free (:free)",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = true,
        suggestedModels = listOf(
            "deepseek/deepseek-r1:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemma-3-27b-it:free",
            "qwen/qwen3-32b:free",
            "mistralai/mistral-small-3.1-24b-instruct:free"
        )
    ),
    POLLINATIONS_GEN(
        id = "pollinations_gen",
        displayName = "Pollinations Unified",
        defaultBaseUrl = "https://gen.pollinations.ai/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = false,
        isLocal = false,
        isFreeTier = true,
        suggestedModels = listOf(
            "openai/gpt-5.4-nano",
            "deepseek/deepseek-v4.1-flash",
            "mistralai/mistral-small-4",
            "google/gemini-2.5-flash-lite"
        )
    ),
    CEREBRAS_FREE(
        id = "cerebras_free",
        displayName = "Cerebras Free Tier",
        defaultBaseUrl = "https://api.cerebras.ai/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = true,
        suggestedModels = listOf(
            "llama-3.3-70b",
            "qwen-3-32b",
            "llama3.1-8b"
        )
    ),

    // --- Local & On-Device ---
    LM_STUDIO(
        id = "lm_studio",
        displayName = "LM Studio",
        defaultBaseUrl = "http://10.0.2.2:1234/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = false,
        isLocal = true,
        isFreeTier = false,
        suggestedModels = emptyList()
    ),
    ON_DEVICE(
        id = "on_device",
        displayName = "On-Device (Hugging Face)",
        defaultBaseUrl = "local://device",
        protocol = ApiProtocol.ON_DEVICE_MEDIAPIPE,
        requiresApiKey = false,
        isLocal = true,
        isFreeTier = false,
        suggestedModels = emptyList()
    ),
    OLLAMA(
        id = "ollama",
        displayName = "Ollama",
        defaultBaseUrl = "http://10.0.2.2:11434/v1",
        protocol = ApiProtocol.OLLAMA,
        requiresApiKey = false,
        isLocal = true,
        isFreeTier = false,
        suggestedModels = listOf("llama3.2", "qwen2.5:7b", "deepseek-r1:8b", "gemma3:4b")
    ),
    MLC_LLM_SERVER(
        id = "mlc_llm_server",
        displayName = "MLC-LLM Server",
        defaultBaseUrl = "http://10.0.2.2:8000/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = false,
        isLocal = true,
        isFreeTier = false,
        suggestedModels = listOf(
            "HF://mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
            "HF://mlc-ai/Llama-3.2-3B-Instruct-q4f16_1-MLC",
            "HF://mlc-ai/gemma-2-2b-it-q4f16_1-MLC"
        )
    ),

    // --- Cloud APIs ---
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = listOf("gpt-4o-mini", "gpt-4o", "o3-mini", "gpt-4.1-mini")
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        protocol = ApiProtocol.ANTHROPIC,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = listOf(
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-latest",
            "claude-3-5-haiku-latest"
        )
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini API",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        protocol = ApiProtocol.GEMINI,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = listOf(
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-sonnet",
            "deepseek/deepseek-r1",
            "google/gemini-2.5-flash"
        )
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = listOf("deepseek-chat", "deepseek-reasoner")
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = true,
        suggestedModels = listOf(
            "llama-3.3-70b-versatile",
            "deepseek-r1-distill-llama-70b",
            "mixtral-8x7b-32768"
        )
    ),
    MISTRAL(
        id = "mistral",
        displayName = "Mistral AI",
        defaultBaseUrl = "https://api.mistral.ai/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = listOf("mistral-small-latest", "mistral-large-latest", "codestral-latest")
    ),
    XAI(
        id = "xai",
        displayName = "xAI Grok",
        defaultBaseUrl = "https://api.x.ai/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = listOf("grok-2-latest", "grok-beta")
    ),
    TOGETHER(
        id = "together",
        displayName = "Together AI",
        defaultBaseUrl = "https://api.together.xyz/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = listOf(
            "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "Qwen/Qwen2.5-72B-Instruct-Turbo"
        )
    ),
    HUGGING_FACE_API(
        id = "hf_inference",
        displayName = "Hugging Face API",
        defaultBaseUrl = "https://router.huggingface.co/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = true,
        isLocal = false,
        isFreeTier = true,
        suggestedModels = listOf(
            "Qwen/Qwen2.5-72B-Instruct",
            "meta-llama/Llama-3.1-8B-Instruct",
            "deepseek-ai/DeepSeek-R1-Distill-Qwen-32B"
        )
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom OpenAI API",
        defaultBaseUrl = "http://10.0.2.2:8080/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        requiresApiKey = false,
        isLocal = false,
        isFreeTier = false,
        suggestedModels = emptyList()
    );

    companion object {
        fun fromId(id: String?): ApiProvider =
            entries.firstOrNull { it.id == id } ?: POLLINATIONS_FREE
    }
}
