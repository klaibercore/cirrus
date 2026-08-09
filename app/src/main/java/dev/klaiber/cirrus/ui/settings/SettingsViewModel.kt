package dev.klaiber.cirrus.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.BuildConfig
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val client: OllamaClient,
) : ViewModel() {

    private val connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        modelRepository.models,
        connectionStatus,
    ) { settings, models, status ->
        SettingsUiState(settings = settings, models = models, connectionStatus = status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
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

    fun deleteAllConversations() {
        viewModelScope.launch { conversationRepository.deleteAllConversations() }
    }
}
