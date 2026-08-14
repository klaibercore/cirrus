package dev.klaiber.cirrus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.klaiber.cirrus.data.prefs.SecretCipher
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsCredentials
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.di.ApplicationScope
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ElevenLabsModel
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.SpeechEngine
import dev.klaiber.cirrus.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for user configuration.
 *
 * Besides exposing [settings] to the UI, this class mirrors the connection fields into
 * [ApiCredentials] so the OkHttp layer can read them without suspending.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: SecretCipher,
    private val credentials: ApiCredentials,
    private val gitHubCredentials: GitHubCredentials,
    private val elevenLabsCredentials: ElevenLabsCredentials,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {

    val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    init {
        // Keep the blocking-readable credential snapshots in step with persisted settings.
        scope.launch {
            dataStore.data.collect { prefs ->
                val encrypted = prefs[Keys.API_KEY]
                credentials.update(
                    apiKey = encrypted?.let(secretCipher::decrypt),
                    baseUrl = prefs[Keys.BASE_URL] ?: ApiCredentials.DEFAULT_BASE_URL,
                )
                gitHubCredentials.update(
                    token = prefs[Keys.GITHUB_TOKEN]?.let(secretCipher::decrypt),
                    // Writes need the tools switched on as well; either flag off means no writes.
                    writesAllowed = (prefs[Keys.GITHUB_TOOLS] ?: false) &&
                        (prefs[Keys.GITHUB_WRITES] ?: false),
                )
                elevenLabsCredentials.update(
                    apiKey = prefs[Keys.ELEVENLABS_KEY]?.let(secretCipher::decrypt),
                )
            }
        }
    }

    /** Snapshot used by callers that need settings once rather than as a stream. */
    val current = settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun setApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.API_KEY)
            } else {
                val encrypted = secretCipher.encrypt(trimmed)
                if (encrypted != null) prefs[Keys.API_KEY] = encrypted else prefs.remove(Keys.API_KEY)
            }
        }
        // Applied here as well as by the collector above, and that is not belt and braces: the
        // mirror runs on the application scope, so "save the key, then test it" — which is exactly
        // what the setup wizard does — would otherwise race its own write and test the old one.
        credentials.update(trimmed.takeIf { it.isNotEmpty() }, credentials.baseUrl)
    }

    suspend fun clearApiKey() = setApiKey("")

    suspend fun setBaseUrl(url: String) {
        val normalized = ApiCredentials.normalizeBaseUrl(url)
        edit { it[Keys.BASE_URL] = normalized }
        credentials.update(credentials.apiKey, normalized)
    }

    suspend fun setDefaultModel(model: String) = edit { it[Keys.DEFAULT_MODEL] = model }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDeveloperMode(enabled: Boolean) = edit { it[Keys.DEVELOPER_MODE] = enabled }

    suspend fun setDefaultParams(params: GenerationParams) = edit {
        it[Keys.DEFAULT_PARAMS] = json.encodeToString(GenerationParams.serializer(), params)
    }

    suspend fun setToolsEnabledByDefault(enabled: Boolean) = edit { it[Keys.TOOLS_DEFAULT] = enabled }

    suspend fun setWebSearchMaxResults(count: Int) = edit {
        it[Keys.WEB_MAX_RESULTS] = count.coerceIn(1, 10)
    }

    suspend fun setMaxToolIterations(count: Int) = edit {
        it[Keys.MAX_TOOL_ITERATIONS] = count.coerceIn(1, 20)
    }

    suspend fun setShowStats(enabled: Boolean) = edit { it[Keys.SHOW_STATS] = enabled }

    suspend fun setRenderMarkdown(enabled: Boolean) = edit { it[Keys.RENDER_MARKDOWN] = enabled }

    suspend fun setAutoTitle(enabled: Boolean) = edit { it[Keys.AUTO_TITLE] = enabled }

    suspend fun setContextMessageLimit(limit: Int) = edit {
        it[Keys.CONTEXT_LIMIT] = limit.coerceAtLeast(0)
    }

    suspend fun setSendOnEnter(enabled: Boolean) = edit { it[Keys.SEND_ON_ENTER] = enabled }

    /** Stored with the same Keystore-backed envelope encryption as the Ollama key. */
    suspend fun setGitHubToken(rawToken: String) {
        val trimmed = rawToken.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.GITHUB_TOKEN)
            } else {
                val encrypted = secretCipher.encrypt(trimmed)
                if (encrypted != null) {
                    prefs[Keys.GITHUB_TOKEN] = encrypted
                } else {
                    prefs.remove(Keys.GITHUB_TOKEN)
                }
            }
        }
    }

    suspend fun clearGitHubToken() = setGitHubToken("")

    suspend fun setGitHubToolsEnabled(enabled: Boolean) = edit { it[Keys.GITHUB_TOOLS] = enabled }

    suspend fun setGitHubWritesAllowed(allowed: Boolean) = edit { it[Keys.GITHUB_WRITES] = allowed }

    suspend fun setVoiceInputEnabled(enabled: Boolean) = edit { it[Keys.VOICE_INPUT] = enabled }

    suspend fun setPreferOnDeviceRecognition(enabled: Boolean) = edit {
        it[Keys.ON_DEVICE_RECOGNITION] = enabled
    }

    suspend fun setReadAloudEnabled(enabled: Boolean) = edit { it[Keys.READ_ALOUD] = enabled }

    suspend fun setSpeechEngine(engine: SpeechEngine) = edit { it[Keys.SPEECH_ENGINE] = engine.name }

    /** Stored with the same Keystore-backed envelope encryption as every other secret here. */
    suspend fun setElevenLabsKey(rawKey: String) {
        val trimmed = rawKey.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.ELEVENLABS_KEY)
            } else {
                val encrypted = secretCipher.encrypt(trimmed)
                if (encrypted != null) {
                    prefs[Keys.ELEVENLABS_KEY] = encrypted
                } else {
                    prefs.remove(Keys.ELEVENLABS_KEY)
                }
            }
        }
    }

    suspend fun clearElevenLabsKey() = setElevenLabsKey("")

    suspend fun setElevenLabsVoice(id: String, name: String) = edit {
        it[Keys.ELEVENLABS_VOICE] = id
        it[Keys.ELEVENLABS_VOICE_NAME] = name
    }

    suspend fun setElevenLabsModel(model: ElevenLabsModel) = edit {
        it[Keys.ELEVENLABS_MODEL] = model.id
    }

    suspend fun setMemoryEnabled(enabled: Boolean) = edit { it[Keys.MEMORY_ENABLED] = enabled }

    suspend fun setNotificationToolEnabled(enabled: Boolean) = edit {
        it[Keys.NOTIFICATION_TOOL] = enabled
    }

    suspend fun setMemoryConsolidationEnabled(enabled: Boolean) = edit {
        it[Keys.CONSOLIDATION_ENABLED] = enabled
    }

    suspend fun setMemoryConsolidationHour(hour: Int) = edit {
        it[Keys.CONSOLIDATION_HOUR] = hour.coerceIn(0, 23)
    }

    suspend fun setLastConsolidationAt(at: Long) = edit { it[Keys.LAST_CONSOLIDATION] = at }

    suspend fun setOnboardingCompleted(completed: Boolean) = edit {
        it[Keys.ONBOARDING_DONE] = completed
    }

    suspend fun setShowStarterPrompts(enabled: Boolean) = edit {
        it[Keys.STARTER_PROMPTS] = enabled
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        baseUrl = this[Keys.BASE_URL] ?: ApiCredentials.DEFAULT_BASE_URL,
        hasApiKey = this[Keys.API_KEY] != null,
        defaultModel = this[Keys.DEFAULT_MODEL] ?: "",
        themeMode = this[Keys.THEME_MODE]
            ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        developerMode = this[Keys.DEVELOPER_MODE] ?: false,
        defaultParams = this[Keys.DEFAULT_PARAMS]
            ?.let { raw -> runCatching { json.decodeFromString(GenerationParams.serializer(), raw) }.getOrNull() }
            ?: GenerationParams.Default,
        toolsEnabledByDefault = this[Keys.TOOLS_DEFAULT] ?: false,
        webSearchMaxResults = this[Keys.WEB_MAX_RESULTS] ?: 5,
        maxToolIterations = this[Keys.MAX_TOOL_ITERATIONS] ?: 6,
        showStats = this[Keys.SHOW_STATS] ?: true,
        renderMarkdown = this[Keys.RENDER_MARKDOWN] ?: true,
        autoTitleConversations = this[Keys.AUTO_TITLE] ?: true,
        contextMessageLimit = this[Keys.CONTEXT_LIMIT] ?: 0,
        sendOnEnter = this[Keys.SEND_ON_ENTER] ?: false,
        gitHubToolsEnabled = this[Keys.GITHUB_TOOLS] ?: false,
        hasGitHubToken = this[Keys.GITHUB_TOKEN] != null,
        gitHubWritesAllowed = this[Keys.GITHUB_WRITES] ?: false,
        voiceInputEnabled = this[Keys.VOICE_INPUT] ?: true,
        preferOnDeviceRecognition = this[Keys.ON_DEVICE_RECOGNITION] ?: true,
        readAloudEnabled = this[Keys.READ_ALOUD] ?: true,
        speechEngine = this[Keys.SPEECH_ENGINE]
            ?.let { name -> runCatching { SpeechEngine.valueOf(name) }.getOrNull() }
            ?: SpeechEngine.DEVICE,
        hasElevenLabsKey = this[Keys.ELEVENLABS_KEY] != null,
        elevenLabsVoiceId = this[Keys.ELEVENLABS_VOICE] ?: "",
        elevenLabsVoiceName = this[Keys.ELEVENLABS_VOICE_NAME] ?: "",
        elevenLabsModelId = this[Keys.ELEVENLABS_MODEL] ?: ElevenLabsModel.Default.id,
        memoryEnabled = this[Keys.MEMORY_ENABLED] ?: true,
        notificationToolEnabled = this[Keys.NOTIFICATION_TOOL] ?: true,
        memoryConsolidationEnabled = this[Keys.CONSOLIDATION_ENABLED] ?: true,
        memoryConsolidationHour = this[Keys.CONSOLIDATION_HOUR] ?: 3,
        lastConsolidationAt = this[Keys.LAST_CONSOLIDATION] ?: 0L,
        // A key or a chosen model is proof enough that setup already happened, which is what keeps
        // the wizard from ambushing everyone who upgrades into the version that introduced it.
        onboardingCompleted = this[Keys.ONBOARDING_DONE]
            ?: (this[Keys.API_KEY] != null || !this[Keys.DEFAULT_MODEL].isNullOrBlank()),
        showStarterPrompts = this[Keys.STARTER_PROMPTS] ?: true,
    )

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key_encrypted")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val DEFAULT_PARAMS = stringPreferencesKey("default_params")
        val TOOLS_DEFAULT = booleanPreferencesKey("tools_default")
        val WEB_MAX_RESULTS = intPreferencesKey("web_max_results")
        val MAX_TOOL_ITERATIONS = intPreferencesKey("max_tool_iterations")
        val SHOW_STATS = booleanPreferencesKey("show_stats")
        val RENDER_MARKDOWN = booleanPreferencesKey("render_markdown")
        val AUTO_TITLE = booleanPreferencesKey("auto_title")
        val CONTEXT_LIMIT = intPreferencesKey("context_limit")
        val SEND_ON_ENTER = booleanPreferencesKey("send_on_enter")
        val GITHUB_TOKEN = stringPreferencesKey("github_token_encrypted")
        val GITHUB_TOOLS = booleanPreferencesKey("github_tools")
        val GITHUB_WRITES = booleanPreferencesKey("github_writes")
        val VOICE_INPUT = booleanPreferencesKey("voice_input")
        val ON_DEVICE_RECOGNITION = booleanPreferencesKey("on_device_recognition")
        val READ_ALOUD = booleanPreferencesKey("read_aloud")
        val SPEECH_ENGINE = stringPreferencesKey("speech_engine")
        val ELEVENLABS_KEY = stringPreferencesKey("elevenlabs_key_encrypted")
        val ELEVENLABS_VOICE = stringPreferencesKey("elevenlabs_voice")
        val ELEVENLABS_VOICE_NAME = stringPreferencesKey("elevenlabs_voice_name")
        val ELEVENLABS_MODEL = stringPreferencesKey("elevenlabs_model")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val NOTIFICATION_TOOL = booleanPreferencesKey("notification_tool")
        val CONSOLIDATION_ENABLED = booleanPreferencesKey("consolidation_enabled")
        val CONSOLIDATION_HOUR = intPreferencesKey("consolidation_hour")
        val LAST_CONSOLIDATION = longPreferencesKey("last_consolidation")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val STARTER_PROMPTS = booleanPreferencesKey("starter_prompts")
    }
}
