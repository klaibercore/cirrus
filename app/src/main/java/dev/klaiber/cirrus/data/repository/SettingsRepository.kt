package dev.klaiber.cirrus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.klaiber.cirrus.data.prefs.SecretCipher
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.di.ApplicationScope
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.GenerationParams
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
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {

    val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    init {
        // Keep the blocking-readable credential snapshot in step with persisted settings.
        scope.launch {
            dataStore.data.collect { prefs ->
                val encrypted = prefs[Keys.API_KEY]
                credentials.update(
                    apiKey = encrypted?.let(secretCipher::decrypt),
                    baseUrl = prefs[Keys.BASE_URL] ?: ApiCredentials.DEFAULT_BASE_URL,
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
    }

    suspend fun clearApiKey() = setApiKey("")

    suspend fun setBaseUrl(url: String) = edit { it[Keys.BASE_URL] = ApiCredentials.normalizeBaseUrl(url) }

    suspend fun setDefaultModel(model: String) = edit { it[Keys.DEFAULT_MODEL] = model }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }

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
        useDynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
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
    )

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key_encrypted")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
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
    }
}
