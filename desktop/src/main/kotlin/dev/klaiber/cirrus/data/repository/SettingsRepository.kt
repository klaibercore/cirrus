package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Single source of truth for user configuration, backed by a JSON file.
 *
 * Besides exposing [settings] to the UI, this mirrors the connection fields into
 * [ApiCredentials] and [GitHubCredentials] so the OkHttp interceptors can read them without
 * suspending. The desktop build has no Keystore, so the key and token are stored in the clear
 * inside Cirrus's own data directory — the same trust boundary as the Android build's encrypted
 * store, just without the envelope.
 */
class SettingsRepository(
    private val store: JsonStore,
    private val credentials: ApiCredentials,
    private val gitHubCredentials: GitHubCredentials,
) {

    private val _settings = MutableStateFlow(AppSettings())

    /** The settings as a stream, for the UI to collect. */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Snapshot used by callers that need settings once rather than as a stream. */
    val current: StateFlow<AppSettings> = _settings.asStateFlow()

    private var apiKey: String? = null
    private var gitHubToken: String? = null

    /** Loads the persisted file, or leaves the defaults when there is none. */
    suspend fun load() {
        val persisted = store.read(PersistedSettings.serializer()) { PersistedSettings() }
        apiKey = persisted.apiKey
        gitHubToken = persisted.gitHubToken
        _settings.value = persisted.settings.copy(
            hasApiKey = apiKey != null,
            hasGitHubToken = gitHubToken != null,
        )
        mirror()
    }

    private fun mirror() {
        credentials.update(apiKey, _settings.value.baseUrl)
        gitHubCredentials.update(gitHubToken, _settings.value.writeToolsAllowed)
    }

    private suspend fun update(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
        mirror()
        persist()
    }

    private suspend fun persist() {
        store.write(
            PersistedSettings.serializer(),
            PersistedSettings(apiKey = apiKey, gitHubToken = gitHubToken, settings = _settings.value),
        )
    }

    suspend fun setApiKey(rawKey: String) {
        apiKey = rawKey.trim().takeIf { it.isNotEmpty() }
        _settings.value = _settings.value.copy(hasApiKey = apiKey != null)
        mirror()
        persist()
    }

    suspend fun clearApiKey() = setApiKey("")

    suspend fun setBaseUrl(url: String) {
        val normalized = ApiCredentials.normalizeBaseUrl(url)
        _settings.value = _settings.value.copy(baseUrl = normalized)
        mirror()
        persist()
    }

    suspend fun setDefaultModel(model: String) = update { it.copy(defaultModel = model.trim()) }

    suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    suspend fun setDeveloperMode(enabled: Boolean) = update { it.copy(developerMode = enabled) }

    suspend fun setDefaultParams(params: GenerationParams) = update { it.copy(defaultParams = params) }

    suspend fun setToolsEnabledByDefault(enabled: Boolean) =
        update { it.copy(toolsEnabledByDefault = enabled) }

    suspend fun setWebSearchMaxResults(count: Int) =
        update { it.copy(webSearchMaxResults = count.coerceIn(1, 10)) }

    suspend fun setMaxToolIterations(count: Int) =
        update { it.copy(maxToolIterations = count.coerceIn(1, 20)) }

    suspend fun setShowStats(enabled: Boolean) = update { it.copy(showStats = enabled) }

    suspend fun setRenderMarkdown(enabled: Boolean) = update { it.copy(renderMarkdown = enabled) }

    suspend fun setAutoTitle(enabled: Boolean) = update { it.copy(autoTitleConversations = enabled) }

    suspend fun setContextMessageLimit(limit: Int) =
        update { it.copy(contextMessageLimit = limit.coerceAtLeast(0)) }

    suspend fun setSendOnEnter(enabled: Boolean) = update { it.copy(sendOnEnter = enabled) }

    suspend fun setGitHubToken(rawToken: String) {
        gitHubToken = rawToken.trim().takeIf { it.isNotEmpty() }
        _settings.value = _settings.value.copy(hasGitHubToken = gitHubToken != null)
        mirror()
        persist()
    }

    suspend fun clearGitHubToken() = setGitHubToken("")

    suspend fun setGitHubToolsEnabled(enabled: Boolean) =
        update { it.copy(gitHubToolsEnabled = enabled) }

    suspend fun setWriteToolsAllowed(allowed: Boolean) = update { it.copy(writeToolsAllowed = allowed) }

    suspend fun setShellToolsEnabled(enabled: Boolean) =
        update { it.copy(shellToolsEnabled = enabled) }

    suspend fun setAppControlEnabled(enabled: Boolean) =
        update { it.copy(appControlEnabled = enabled) }

    suspend fun setMemoryEnabled(enabled: Boolean) = update { it.copy(memoryEnabled = enabled) }

    suspend fun setNotificationToolEnabled(enabled: Boolean) =
        update { it.copy(notificationToolEnabled = enabled) }

    suspend fun setOnboardingCompleted(completed: Boolean) =
        update { it.copy(onboardingCompleted = completed) }

    suspend fun setShowStarterPrompts(enabled: Boolean) =
        update { it.copy(showStarterPrompts = enabled) }
}

/** The on-disk shape: the settings plus the two secrets that [AppSettings] only mirrors. */
@Serializable
private data class PersistedSettings(
    val apiKey: String? = null,
    val gitHubToken: String? = null,
    val settings: AppSettings = AppSettings(),
)
