package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsCredentials
import dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ElevenLabsModel
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.SpeechEngine
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
    private val spotifyCredentials: SpotifyCredentials = SpotifyCredentials(),
    private val elevenLabsCredentials: ElevenLabsCredentials = ElevenLabsCredentials(),
) {

    private val _settings = MutableStateFlow(AppSettings())

    /** The settings as a stream, for the UI to collect. */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Snapshot used by callers that need settings once rather than as a stream. */
    val current: StateFlow<AppSettings> = _settings.asStateFlow()

    private var apiKey: String? = null
    private var gitHubToken: String? = null
    private var elevenLabsKey: String? = null
    private var spotifyAccessToken: String? = null
    private var spotifyRefreshToken: String? = null
    private var spotifyExpiresAt: Long = 0L

    /** A sign-in in flight. In memory on purpose — see [setSpotifyPendingAuth]. */
    @Volatile
    private var pendingAuth: PendingAuth? = null

    /** Loads the persisted file, or leaves the defaults when there is none. */
    suspend fun load() {
        val persisted = store.read(PersistedSettings.serializer()) { PersistedSettings() }
        apiKey = persisted.apiKey
        gitHubToken = persisted.gitHubToken
        elevenLabsKey = persisted.elevenLabsKey
        spotifyAccessToken = persisted.spotifyAccessToken
        spotifyRefreshToken = persisted.spotifyRefreshToken
        spotifyExpiresAt = persisted.spotifyExpiresAt
        _settings.value = persisted.settings.copy(
            hasApiKey = apiKey != null,
            hasGitHubToken = gitHubToken != null,
            hasElevenLabsKey = elevenLabsKey != null,
            hasSpotifyAccount = spotifyRefreshToken != null,
        )
        mirror()
    }

    private fun mirror() {
        credentials.update(apiKey, _settings.value.baseUrl)
        gitHubCredentials.update(gitHubToken, _settings.value.writeToolsAllowed)
        elevenLabsCredentials.update(elevenLabsKey)
        spotifyCredentials.update(
            clientId = _settings.value.spotifyClientId,
            accessToken = spotifyAccessToken,
            refreshToken = spotifyRefreshToken,
            expiresAt = spotifyExpiresAt,
            writesAllowed = _settings.value.writeToolsAllowed,
        )
    }

    private suspend fun update(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
        mirror()
        persist()
    }

    private suspend fun persist() {
        store.write(
            PersistedSettings.serializer(),
            PersistedSettings(
                apiKey = apiKey,
                gitHubToken = gitHubToken,
                elevenLabsKey = elevenLabsKey,
                spotifyAccessToken = spotifyAccessToken,
                spotifyRefreshToken = spotifyRefreshToken,
                spotifyExpiresAt = spotifyExpiresAt,
                settings = _settings.value,
            ),
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

    suspend fun setMemoryConsolidationEnabled(enabled: Boolean) =
        update { it.copy(memoryConsolidationEnabled = enabled) }

    suspend fun setMemoryConsolidationHour(hour: Int) =
        update { it.copy(memoryConsolidationHour = hour.coerceIn(0, 23)) }

    suspend fun setLastConsolidationAt(at: Long) = update { it.copy(lastConsolidationAt = at) }

    // ---- Read aloud ----------------------------------------------------------------------------

    suspend fun setReadAloudEnabled(enabled: Boolean) = update { it.copy(readAloudEnabled = enabled) }

    suspend fun setSpeechEngine(engine: SpeechEngine) = update { it.copy(speechEngine = engine) }

    suspend fun setElevenLabsKey(rawKey: String) {
        elevenLabsKey = rawKey.trim().takeIf { it.isNotEmpty() }
        _settings.value = _settings.value.copy(hasElevenLabsKey = elevenLabsKey != null)
        mirror()
        persist()
    }

    suspend fun clearElevenLabsKey() = setElevenLabsKey("")

    suspend fun setElevenLabsVoice(id: String, name: String) =
        update { it.copy(elevenLabsVoiceId = id, elevenLabsVoiceName = name) }

    suspend fun setElevenLabsModel(model: ElevenLabsModel) =
        update { it.copy(elevenLabsModelId = model.id) }

    // ---- Spotify -------------------------------------------------------------------------------

    suspend fun setSpotifyEnabled(enabled: Boolean) = update { it.copy(spotifyEnabled = enabled) }

    suspend fun setSpotifyClientId(clientId: String) {
        _settings.value = _settings.value.copy(spotifyClientId = clientId.trim())
        mirror()
        persist()
    }

    /**
     * Stores a freshly issued pair of tokens.
     *
     * A refresh token is worth as much as a password — it mints access tokens until it is revoked.
     * The Android build wraps both in a Keystore envelope; there is none here, so they sit in the
     * data directory beside the Ollama key, which is the same trust boundary this build already
     * asks the user to accept.
     */
    suspend fun setSpotifyTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAt: Long,
        accountName: String? = null,
        premium: Boolean? = null,
    ) {
        spotifyAccessToken = accessToken
        // Spotify omits refresh_token on a refresh response; the previous one stays valid, so an
        // absent value must not clear the stored one.
        refreshToken?.let { spotifyRefreshToken = it }
        spotifyExpiresAt = expiresAt
        _settings.value = _settings.value.copy(
            hasSpotifyAccount = spotifyRefreshToken != null,
            spotifyAccountName = accountName ?: _settings.value.spotifyAccountName,
            spotifyPremium = premium ?: _settings.value.spotifyPremium,
        )
        mirror()
        persist()
    }

    /**
     * Remembers what a sign-in in progress will need when the browser comes back.
     *
     * Held in memory rather than written down, unlike on Android. There the verifier is persisted
     * because the OS is free to kill the app while somebody reads Spotify's consent screen; a
     * desktop process stays up for the length of the trip, and the listener waiting for the
     * redirect is in this same process, so a verifier that died with it would have nothing left
     * to serve anyway.
     */
    fun setSpotifyPendingAuth(verifier: String, state: String) {
        pendingAuth = PendingAuth(verifier, state)
    }

    /** Reads the pending sign-in and clears it, so a code can never be replayed against it. */
    fun consumeSpotifyPendingAuth(): PendingAuth? = pendingAuth.also { pendingAuth = null }

    data class PendingAuth(val verifier: String, val state: String)

    suspend fun clearSpotifyAccount() {
        spotifyAccessToken = null
        spotifyRefreshToken = null
        spotifyExpiresAt = 0L
        _settings.value = _settings.value.copy(
            hasSpotifyAccount = false,
            spotifyAccountName = "",
            spotifyPremium = false,
        )
        mirror()
        persist()
    }

    suspend fun setOnboardingCompleted(completed: Boolean) =
        update { it.copy(onboardingCompleted = completed) }

    suspend fun setShowStarterPrompts(enabled: Boolean) =
        update { it.copy(showStarterPrompts = enabled) }
}

/** The on-disk shape: the settings plus the secrets that [AppSettings] only mirrors the presence of. */
@Serializable
private data class PersistedSettings(
    val apiKey: String? = null,
    val gitHubToken: String? = null,
    val elevenLabsKey: String? = null,
    val spotifyAccessToken: String? = null,
    val spotifyRefreshToken: String? = null,
    val spotifyExpiresAt: Long = 0L,
    val settings: AppSettings = AppSettings(),
)
