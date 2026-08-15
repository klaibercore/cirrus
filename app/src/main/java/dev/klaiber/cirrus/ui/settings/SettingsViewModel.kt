package dev.klaiber.cirrus.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.BuildConfig
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsClient
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsVoice
import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.data.repository.McpServerRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.ElevenLabsModel
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.domain.model.SpeechEngine
import dev.klaiber.cirrus.domain.model.ThemeMode
import dev.klaiber.cirrus.domain.spotify.SpotifySession
import dev.klaiber.cirrus.domain.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the voice list is up to, shown under the picker. */
sealed interface VoiceStatus {
    data object Idle : VoiceStatus
    data object Loading : VoiceStatus
    data object Loaded : VoiceStatus
    data class Failure(val message: String) : VoiceStatus
}

/** Result of the "test connection" probe, rendered inline under the key field. */
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Testing : ConnectionStatus
    data class Success(val model: String) : ConnectionStatus
    data class Failure(val message: String) : ConnectionStatus
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val models: List<ModelInfo> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    val versionName: String = BuildConfig.VERSION_NAME,
    val mcpServerCount: Int = 0,
    /** Tools actually resolved from enabled servers, not the number configured. */
    val mcpToolCount: Int = 0,
    val memoryCount: Int = 0,
    val agentCount: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val spotify: SpotifySession,
    mcpServerRepository: McpServerRepository,
    memoryRepository: MemoryRepository,
    agentRepository: AgentRepository,
    private val client: OllamaClient,
    private val elevenLabs: ElevenLabsClient,
) : ViewModel() {

    /** The voices on the ElevenLabs account, fetched on demand because most users never look. */
    private val _voices = MutableStateFlow<List<ElevenLabsVoice>>(emptyList())
    val voices: StateFlow<List<ElevenLabsVoice>> = _voices

    private val _voiceStatus = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    val voiceStatus: StateFlow<VoiceStatus> = _voiceStatus

    private val connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)

    /** Grouped into two combines because `combine` tops out at five flows. */
    private val mcpCounts = combine(
        mcpServerRepository.servers,
        mcpServerRepository.bindings,
        memoryRepository.activeCount,
        agentRepository.agents,
    ) { servers, bindings, memories, agents ->
        Counts(servers.size, bindings.size, memories, agents.size)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        modelRepository.models,
        connectionStatus,
        mcpCounts,
    ) { settings, models, status, counts ->
        SettingsUiState(
            settings = settings,
            models = models,
            connectionStatus = status,
            mcpServerCount = counts.mcpServers,
            mcpToolCount = counts.mcpTools,
            memoryCount = counts.memories,
            agentCount = counts.agents,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private data class Counts(
        val mcpServers: Int,
        val mcpTools: Int,
        val memories: Int,
        val agents: Int,
    )

    init {
        viewModelScope.launch { modelRepository.refreshIfEmpty() }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            settingsRepository.setApiKey(key)
            connectionStatus.value = ConnectionStatus.Idle
            // A new key usually means a different account and therefore a different catalogue.
            modelRepository.refresh()
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            settingsRepository.clearApiKey()
            connectionStatus.value = ConnectionStatus.Idle
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            connectionStatus.value = ConnectionStatus.Testing
            val model = settingsRepository.current.value.defaultModel.ifBlank {
                modelRepository.models.value.firstOrNull()?.name.orEmpty()
            }
            if (model.isBlank()) {
                modelRepository.refresh()
            }
            val resolved = model.ifBlank { modelRepository.models.value.firstOrNull()?.name.orEmpty() }
            if (resolved.isBlank()) {
                connectionStatus.value = ConnectionStatus.Failure(
                    "Could not list any models from this host.",
                )
                return@launch
            }
            connectionStatus.value = client.validateCredentials(resolved).fold(
                onSuccess = { ConnectionStatus.Success(resolved) },
                onFailure = { ConnectionStatus.Failure(it.message ?: "Connection failed.") },
            )
        }
    }

    fun setBaseUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setBaseUrl(url)
            connectionStatus.value = ConnectionStatus.Idle
            modelRepository.refresh()
        }
    }

    fun setDefaultModel(model: String) {
        viewModelScope.launch { settingsRepository.setDefaultModel(model) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDeveloperMode(enabled) }
    }

    fun setShowStats(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowStats(enabled) }
    }

    fun setRenderMarkdown(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRenderMarkdown(enabled) }
    }

    fun setAutoTitle(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTitle(enabled) }
    }

    fun setSendOnEnter(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSendOnEnter(enabled) }
    }

    fun setShowStarterPrompts(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowStarterPrompts(enabled) }
    }

    fun setWriteToolsAllowed(allowed: Boolean) {
        viewModelScope.launch { settingsRepository.setWriteToolsAllowed(allowed) }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMemoryEnabled(enabled) }
    }

    fun setMemoryConsolidationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMemoryConsolidationEnabled(enabled) }
    }

    fun setNotificationToolEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationToolEnabled(enabled) }
    }

    /**
     * Records the switch and what Android actually said about the permission.
     *
     * Both, because they are different facts: the user can want location on and Android can still
     * have refused it, and the settings catalogue exists to tell those two apart rather than
     * sending somebody to a switch that is already in the right position.
     */
    fun setLocationEnabled(enabled: Boolean, permissionGranted: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocationPermissionGranted(permissionGranted)
            settingsRepository.setLocationEnabled(enabled && permissionGranted)
        }
    }

    fun setSpotifyClientId(clientId: String) {
        viewModelScope.launch { settingsRepository.setSpotifyClientId(clientId) }
    }

    fun setSpotifyEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSpotifyEnabled(enabled) }
    }

    fun disconnectSpotify() {
        viewModelScope.launch {
            spotify.signOut()
            settingsRepository.setSpotifyEnabled(false)
        }
    }

    /**
     * Builds the sign-in URL, or null when there is no client ID to build it from.
     *
     * Returned rather than opened here: launching a browser needs an Activity, and a ViewModel that
     * held one would outlive it.
     */
    suspend fun beginSpotifySignIn(): String? =
        if (spotify.canSignIn) spotify.beginSignIn().url else null

    fun setShellToolsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShellToolsEnabled(enabled) }
    }

    fun setAppControlEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAppControlEnabled(enabled) }
    }

    fun saveGitHubToken(token: String) {
        viewModelScope.launch { settingsRepository.setGitHubToken(token) }
    }

    fun clearGitHubToken() {
        viewModelScope.launch { settingsRepository.clearGitHubToken() }
    }

    fun setGitHubToolsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setGitHubToolsEnabled(enabled) }
    }


    fun setVoiceInputEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVoiceInputEnabled(enabled) }
    }

    fun setPreferOnDeviceRecognition(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPreferOnDeviceRecognition(enabled) }
    }

    fun setToolsDefault(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setToolsEnabledByDefault(enabled) }
    }

    fun setWebSearchMaxResults(count: Int) {
        viewModelScope.launch { settingsRepository.setWebSearchMaxResults(count) }
    }

    fun setMaxToolIterations(count: Int) {
        viewModelScope.launch { settingsRepository.setMaxToolIterations(count) }
    }

    fun setContextMessageLimit(limit: Int) {
        viewModelScope.launch { settingsRepository.setContextMessageLimit(limit) }
    }

    fun setDefaultParams(params: GenerationParams) {
        viewModelScope.launch { settingsRepository.setDefaultParams(params) }
    }

    fun setReadAloudEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setReadAloudEnabled(enabled) }
    }

    fun setSpeechEngine(engine: SpeechEngine) {
        viewModelScope.launch { settingsRepository.setSpeechEngine(engine) }
    }

    fun saveElevenLabsKey(key: String) {
        viewModelScope.launch {
            settingsRepository.setElevenLabsKey(key)
            // A key is only useful once you can pick a voice with it, so fetch them straight away.
            if (key.isNotBlank()) loadVoices()
        }
    }

    fun clearElevenLabsKey() {
        viewModelScope.launch {
            settingsRepository.clearElevenLabsKey()
            _voices.value = emptyList()
            _voiceStatus.value = VoiceStatus.Idle
        }
    }

    fun setElevenLabsModel(model: ElevenLabsModel) {
        viewModelScope.launch { settingsRepository.setElevenLabsModel(model) }
    }

    fun setElevenLabsVoice(voice: ElevenLabsVoice) {
        viewModelScope.launch { settingsRepository.setElevenLabsVoice(voice.id, voice.name) }
    }

    fun loadVoices() {
        viewModelScope.launch {
            _voiceStatus.value = VoiceStatus.Loading
            runCatching { elevenLabs.voices() }.fold(
                onSuccess = { list ->
                    _voices.value = list
                    _voiceStatus.value = if (list.isEmpty()) {
                        VoiceStatus.Failure("That account has no voices.")
                    } else {
                        VoiceStatus.Loaded
                    }
                },
                onFailure = { _voiceStatus.value = VoiceStatus.Failure(it.userMessage()) },
            )
        }
    }

    fun deleteAllConversations() {
        viewModelScope.launch { conversationRepository.deleteAllConversations() }
    }
}
