package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.imagegen.DiffusionScheduler
import com.example.data.imagegen.ImageGenBackendMode
import com.example.data.localmodel.LocalHardwareBackend
import com.example.data.localmodel.LocalInferenceEngine
import com.example.data.network.ApiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "localmind_settings"
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED
}

enum class VoiceLocale(val tag: String?) {
    AUTO(null),
    UK("uk-UA"),
    RU("ru-RU"),
    EN("en-US")
}

data class ProviderConfig(
    val baseUrl: String,
    val apiKey: String,
    val selectedModel: String
)

data class AppSettings(
    val activeProvider: ApiProvider = ApiProvider.LM_STUDIO,
    val providerConfigs: Map<ApiProvider, ProviderConfig> = emptyMap(),
    val activeLocalModelId: String = "",
    val localEngine: LocalInferenceEngine = LocalInferenceEngine.AUTO,
    val localBackend: LocalHardwareBackend = LocalHardwareBackend.NPU,
    val localThreads: Int = 4,
    val activeAgentId: String = "",
    val enableBuiltInTools: Boolean = true,
    val huggingFaceToken: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val contextMessageLimit: Int = 20,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = false,
    val voiceLocale: VoiceLocale = VoiceLocale.AUTO,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val imageGenMode: ImageGenBackendMode = ImageGenBackendMode.ON_DEVICE_SD_CPP,
    val imageServerUrl: String = ImageGenBackendMode.LOCAL_A1111_FORGE.defaultUrl,
    val imageServerApiKey: String = "",
    val imageServerModel: String = "",
    val activeImageModelId: String = "",
    val imageWidth: Int = 512,
    val imageHeight: Int = 512,
    val imageSteps: Int = 16,
    val imageCfgScale: Float = 7.0f,
    val imageScheduler: DiffusionScheduler = DiffusionScheduler.DPM_PP_2M_KARRAS,
    val imageClipSkip: Int = 1,
    val imageBatchCount: Int = 1,
    val imageHiResFix: Boolean = false,
    val githubUpdateRepo: String = "",
    val autoCheckUpdates: Boolean = true,
    val dismissedUpdateTag: String = ""
) {
    val activeConfig: ProviderConfig
        get() = providerConfigs[activeProvider] ?: ProviderConfig(
            baseUrl = activeProvider.defaultBaseUrl,
            apiKey = "",
            selectedModel = activeProvider.suggestedModels.firstOrNull().orEmpty()
        )

    val baseUrl: String get() = activeConfig.baseUrl
    val apiKey: String get() = activeConfig.apiKey
    val selectedModel: String get() = activeConfig.selectedModel

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:1234/v1"
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, accurate, and concise AI assistant. Format responses cleanly using Markdown when appropriate, including fenced code blocks with language identifiers."
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val ACTIVE_LOCAL_MODEL_ID = stringPreferencesKey("active_local_model_id")
        val LOCAL_ENGINE = stringPreferencesKey("local_engine")
        val LOCAL_BACKEND = stringPreferencesKey("local_backend")
        val LOCAL_THREADS = intPreferencesKey("local_threads")
        val ACTIVE_AGENT_ID = stringPreferencesKey("active_agent_id")
        val ENABLE_BUILTIN_TOOLS = booleanPreferencesKey("enable_builtin_tools")
        val HF_TOKEN = stringPreferencesKey("hf_token")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val CONTEXT_LIMIT = intPreferencesKey("context_limit")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val VOICE_LOCALE = stringPreferencesKey("voice_locale")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val IMAGE_GEN_MODE = stringPreferencesKey("image_gen_mode")
        val IMAGE_SERVER_URL = stringPreferencesKey("image_server_url")
        val IMAGE_SERVER_KEY = stringPreferencesKey("image_server_key")
        val IMAGE_SERVER_MODEL = stringPreferencesKey("image_server_model")
        val ACTIVE_IMAGE_MODEL_ID = stringPreferencesKey("active_image_model_id")
        val IMAGE_WIDTH = intPreferencesKey("image_width")
        val IMAGE_HEIGHT = intPreferencesKey("image_height")
        val IMAGE_STEPS = intPreferencesKey("image_steps")
        val IMAGE_CFG = floatPreferencesKey("image_cfg")
        val IMAGE_SCHEDULER = stringPreferencesKey("image_scheduler")
        val IMAGE_CLIP_SKIP = intPreferencesKey("image_clip_skip")
        val IMAGE_BATCH_COUNT = intPreferencesKey("image_batch_count")
        val IMAGE_HIRES_FIX = booleanPreferencesKey("image_hires_fix")
        val GITHUB_UPDATE_REPO = stringPreferencesKey("github_update_repo")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val DISMISSED_UPDATE_TAG = stringPreferencesKey("dismissed_update_tag")

        val LEGACY_BASE_URL = stringPreferencesKey("base_url")
        val LEGACY_API_KEY = stringPreferencesKey("api_key")
        val LEGACY_SELECTED_MODEL = stringPreferencesKey("selected_model")

        fun providerUrl(provider: ApiProvider) = stringPreferencesKey("provider_url_${provider.id}")
        fun providerKey(provider: ApiProvider) = stringPreferencesKey("provider_key_${provider.id}")
        fun providerModel(provider: ApiProvider) = stringPreferencesKey("provider_model_${provider.id}")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val activeProvider = ApiProvider.fromId(prefs[Keys.ACTIVE_PROVIDER])
        val configs = ApiProvider.entries.associateWith { provider ->
            val legacyUrl = if (provider == ApiProvider.LM_STUDIO) prefs[Keys.LEGACY_BASE_URL] else null
            val legacyKey = if (provider == ApiProvider.LM_STUDIO) prefs[Keys.LEGACY_API_KEY] else null
            val legacyModel = if (provider == ApiProvider.LM_STUDIO) prefs[Keys.LEGACY_SELECTED_MODEL] else null
            ProviderConfig(
                baseUrl = prefs[Keys.providerUrl(provider)] ?: legacyUrl ?: provider.defaultBaseUrl,
                apiKey = prefs[Keys.providerKey(provider)] ?: legacyKey ?: "",
                selectedModel = prefs[Keys.providerModel(provider)]
                    ?: legacyModel
                    ?: provider.suggestedModels.firstOrNull().orEmpty()
            )
        }

        AppSettings(
            activeProvider = activeProvider,
            providerConfigs = configs,
            activeLocalModelId = prefs[Keys.ACTIVE_LOCAL_MODEL_ID] ?: "",
            localEngine = LocalInferenceEngine.fromId(prefs[Keys.LOCAL_ENGINE]),
            localBackend = LocalHardwareBackend.fromId(prefs[Keys.LOCAL_BACKEND]),
            localThreads = (prefs[Keys.LOCAL_THREADS] ?: 4).coerceIn(1, 8),
            activeAgentId = prefs[Keys.ACTIVE_AGENT_ID] ?: "",
            enableBuiltInTools = prefs[Keys.ENABLE_BUILTIN_TOOLS] ?: true,
            huggingFaceToken = prefs[Keys.HF_TOKEN] ?: "",
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 2048,
            contextMessageLimit = prefs[Keys.CONTEXT_LIMIT] ?: 20,
            themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM)
            } ?: ThemeMode.SYSTEM,
            useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            voiceLocale = prefs[Keys.VOICE_LOCALE]?.let {
                runCatching { VoiceLocale.valueOf(it) }.getOrDefault(VoiceLocale.AUTO)
            } ?: VoiceLocale.AUTO,
            appLanguage = AppLanguage.fromCode(prefs[Keys.APP_LANGUAGE]),
            imageGenMode = ImageGenBackendMode.fromId(prefs[Keys.IMAGE_GEN_MODE]),
            imageServerUrl = prefs[Keys.IMAGE_SERVER_URL] ?: ImageGenBackendMode.LOCAL_A1111_FORGE.defaultUrl,
            imageServerApiKey = prefs[Keys.IMAGE_SERVER_KEY] ?: "",
            imageServerModel = prefs[Keys.IMAGE_SERVER_MODEL] ?: "",
            activeImageModelId = prefs[Keys.ACTIVE_IMAGE_MODEL_ID] ?: "",
            imageWidth = (prefs[Keys.IMAGE_WIDTH] ?: 512).coerceIn(256, 1024),
            imageHeight = (prefs[Keys.IMAGE_HEIGHT] ?: 512).coerceIn(256, 1024),
            imageSteps = (prefs[Keys.IMAGE_STEPS] ?: 16).coerceIn(1, 50),
            imageCfgScale = (prefs[Keys.IMAGE_CFG] ?: 7.0f).coerceIn(1.0f, 15.0f),
            imageScheduler = DiffusionScheduler.fromId(prefs[Keys.IMAGE_SCHEDULER]),
            imageClipSkip = (prefs[Keys.IMAGE_CLIP_SKIP] ?: 1).coerceIn(1, 2),
            imageBatchCount = (prefs[Keys.IMAGE_BATCH_COUNT] ?: 1).coerceIn(1, 4),
            imageHiResFix = prefs[Keys.IMAGE_HIRES_FIX] ?: false,
            githubUpdateRepo = prefs[Keys.GITHUB_UPDATE_REPO] ?: "",
            autoCheckUpdates = prefs[Keys.AUTO_CHECK_UPDATES] ?: true,
            dismissedUpdateTag = prefs[Keys.DISMISSED_UPDATE_TAG] ?: ""
        )
    }

    suspend fun updateActiveProvider(provider: ApiProvider) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_PROVIDER] = provider.id }
    }

    suspend fun updateProviderBaseUrl(provider: ApiProvider, url: String) {
        context.settingsDataStore.edit { it[Keys.providerUrl(provider)] = url.trim() }
    }

    suspend fun updateProviderApiKey(provider: ApiProvider, key: String) {
        context.settingsDataStore.edit { it[Keys.providerKey(provider)] = key.trim() }
    }

    suspend fun updateProviderModel(provider: ApiProvider, modelId: String) {
        context.settingsDataStore.edit { it[Keys.providerModel(provider)] = modelId.trim() }
    }

    suspend fun updateActiveLocalModelId(modelId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_LOCAL_MODEL_ID] = modelId }
    }

    suspend fun updateLocalEngine(engine: LocalInferenceEngine) {
        context.settingsDataStore.edit { it[Keys.LOCAL_ENGINE] = engine.id }
    }

    suspend fun updateLocalBackend(backend: LocalHardwareBackend) {
        context.settingsDataStore.edit { it[Keys.LOCAL_BACKEND] = backend.id }
    }

    suspend fun updateLocalThreads(threads: Int) {
        context.settingsDataStore.edit { it[Keys.LOCAL_THREADS] = threads.coerceIn(1, 8) }
    }

    suspend fun updateActiveAgentId(agentId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_AGENT_ID] = agentId }
    }

    suspend fun updateEnableBuiltInTools(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ENABLE_BUILTIN_TOOLS] = enabled }
    }

    suspend fun updateHuggingFaceToken(token: String) {
        context.settingsDataStore.edit { it[Keys.HF_TOKEN] = token.trim() }
    }

    suspend fun updateSystemPrompt(prompt: String) {
        context.settingsDataStore.edit { it[Keys.SYSTEM_PROMPT] = prompt }
    }

    suspend fun updateTemperature(temperature: Float) {
        context.settingsDataStore.edit { it[Keys.TEMPERATURE] = temperature.coerceIn(0f, 2f) }
    }

    suspend fun updateMaxTokens(maxTokens: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_TOKENS] = maxTokens.coerceIn(128, 16384) }
    }

    suspend fun updateContextLimit(limit: Int) {
        context.settingsDataStore.edit { it[Keys.CONTEXT_LIMIT] = limit.coerceIn(2, 60) }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun updateDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun updateVoiceLocale(locale: VoiceLocale) {
        context.settingsDataStore.edit { it[Keys.VOICE_LOCALE] = locale.name }
    }

    suspend fun updateAppLanguage(language: AppLanguage) {
        AppLocaleManager.saveLanguage(context, language)
        context.settingsDataStore.edit { it[Keys.APP_LANGUAGE] = language.code }
    }

    suspend fun updateImageGenMode(mode: ImageGenBackendMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.IMAGE_GEN_MODE] = mode.id
            if (mode.defaultUrl.isNotBlank() && prefs[Keys.IMAGE_SERVER_URL].isNullOrBlank()) {
                prefs[Keys.IMAGE_SERVER_URL] = mode.defaultUrl
            }
        }
    }

    suspend fun updateImageServerUrl(url: String) {
        context.settingsDataStore.edit { it[Keys.IMAGE_SERVER_URL] = url.trim() }
    }

    suspend fun updateImageServerApiKey(key: String) {
        context.settingsDataStore.edit { it[Keys.IMAGE_SERVER_KEY] = key.trim() }
    }

    suspend fun updateImageServerModel(model: String) {
        context.settingsDataStore.edit { it[Keys.IMAGE_SERVER_MODEL] = model.trim() }
    }

    suspend fun updateActiveImageModelId(modelId: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_IMAGE_MODEL_ID] = modelId }
    }

    suspend fun updateImageSize(width: Int, height: Int) {
        context.settingsDataStore.edit {
            it[Keys.IMAGE_WIDTH] = width.coerceIn(256, 1024)
            it[Keys.IMAGE_HEIGHT] = height.coerceIn(256, 1024)
        }
    }

    suspend fun updateImageSteps(steps: Int) {
        context.settingsDataStore.edit { it[Keys.IMAGE_STEPS] = steps.coerceIn(1, 50) }
    }

    suspend fun updateImageCfgScale(cfg: Float) {
        context.settingsDataStore.edit { it[Keys.IMAGE_CFG] = cfg.coerceIn(1.0f, 15.0f) }
    }

    suspend fun updateImageScheduler(scheduler: DiffusionScheduler) {
        context.settingsDataStore.edit { it[Keys.IMAGE_SCHEDULER] = scheduler.id }
    }

    suspend fun updateImageClipSkip(clipSkip: Int) {
        context.settingsDataStore.edit { it[Keys.IMAGE_CLIP_SKIP] = clipSkip.coerceIn(1, 2) }
    }

    suspend fun updateImageBatchCount(count: Int) {
        context.settingsDataStore.edit { it[Keys.IMAGE_BATCH_COUNT] = count.coerceIn(1, 4) }
    }

    suspend fun updateImageHiResFix(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.IMAGE_HIRES_FIX] = enabled }
    }

    suspend fun updateGitHubUpdateRepo(repo: String) {
        context.settingsDataStore.edit { it[Keys.GITHUB_UPDATE_REPO] = repo.trim() }
    }

    suspend fun updateAutoCheckUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun updateDismissedUpdateTag(tag: String) {
        context.settingsDataStore.edit { it[Keys.DISMISSED_UPDATE_TAG] = tag.trim() }
    }
}
