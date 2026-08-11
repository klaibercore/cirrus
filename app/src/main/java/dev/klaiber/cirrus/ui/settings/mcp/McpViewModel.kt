package dev.klaiber.cirrus.ui.settings.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.mcp.MCP_CATALOG
import dev.klaiber.cirrus.data.mcp.McpCatalogEntry
import dev.klaiber.cirrus.data.mcp.McpServerConfig
import dev.klaiber.cirrus.data.mcp.McpTransportKind
import dev.klaiber.cirrus.data.repository.McpProbeResult
import dev.klaiber.cirrus.data.repository.McpServerRepository
import dev.klaiber.cirrus.data.repository.McpServerState
import dev.klaiber.cirrus.data.repository.newMcpServerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class McpUiState(
    val servers: List<McpServerConfig> = emptyList(),
    val states: Map<String, McpServerState> = emptyMap(),
    val catalog: List<McpCatalogEntry> = MCP_CATALOG,
) {
    /** Catalogue entries the user has not already attached, matched on URL. */
    val unattachedCatalog: List<McpCatalogEntry>
        get() = catalog.filterNot { entry ->
            servers.any { it.url.trimEnd('/').equals(entry.url.trimEnd('/'), ignoreCase = true) }
        }
}

/**
 * The editor's own state, separate from the list.
 *
 * [probe] is deliberately not cleared when the URL changes — seeing "7 tools" next to a URL you
 * have since edited would be a lie, so [isProbeStale] tracks that instead and the UI says so.
 */
data class McpEditorState(
    val editingId: String? = null,
    val label: String = "",
    val url: String = "",
    val token: String = "",
    val isProbing: Boolean = false,
    val probe: McpProbeResult? = null,
    /** The URL/token the [probe] result actually describes. */
    val probedAgainst: String? = null,
) {
    val isNew: Boolean get() = editingId == null

    val canProbe: Boolean get() = url.isNotBlank() && !isProbing

    /** A server can be saved once it has been reached; guessing is what the probe is for. */
    val canSave: Boolean
        get() = url.isNotBlank() &&
            label.isNotBlank() &&
            probe is McpProbeResult.Success &&
            !isProbeStale

    val isProbeStale: Boolean
        get() = probe != null && probedAgainst != signature

    val signature: String get() = "${url.trim()}|${token.trim()}"
}

@HiltViewModel
class McpViewModel @Inject constructor(
    private val repository: McpServerRepository,
) : ViewModel() {

    val uiState: StateFlow<McpUiState> = combine(
        repository.servers,
        repository.states,
    ) { servers, states ->
        McpUiState(servers = servers, states = states)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), McpUiState())

    private val _editor = MutableStateFlow<McpEditorState?>(null)

    /** Non-null while the add/edit sheet is open. */
    val editor: StateFlow<McpEditorState?> = _editor.asStateFlow()

    fun startAdding() {
        _editor.value = McpEditorState()
    }

    /** Prefills from the catalogue, then behaves exactly like a hand-typed server. */
    fun startAdding(entry: McpCatalogEntry) {
        _editor.value = McpEditorState(label = entry.label, url = entry.url)
    }

    fun startEditing(server: McpServerConfig) {
        _editor.value = McpEditorState(
            editingId = server.id,
            label = server.label,
            url = server.url,
            token = server.token.orEmpty(),
        )
    }

    fun dismissEditor() {
        _editor.value = null
    }

    fun onLabelChange(value: String) {
        _editor.value = _editor.value?.copy(label = value)
    }

    fun onUrlChange(value: String) {
        _editor.value = _editor.value?.copy(url = value)
    }

    fun onTokenChange(value: String) {
        _editor.value = _editor.value?.copy(token = value)
    }

    /**
     * Discovery: reach the server and report what it offers, without saving anything.
     */
    fun probe() {
        val editing = _editor.value ?: return
        if (!editing.canProbe) return
        val signature = editing.signature

        viewModelScope.launch {
            _editor.value = _editor.value?.copy(isProbing = true)
            val result = repository.probe(editing.url, editing.token)
            _editor.value = _editor.value?.let { latest ->
                // The user may have kept typing; only apply if this still describes their input.
                latest.copy(
                    isProbing = false,
                    probe = result,
                    probedAgainst = signature,
                    // A server that names itself is a better default than an empty field.
                    label = latest.label.ifBlank { suggestLabel(latest.url) },
                )
            }
        }
    }

    fun save() {
        val editing = _editor.value ?: return
        if (!editing.canSave) return

        viewModelScope.launch {
            val transport = (editing.probe as? McpProbeResult.Success)?.transport
            val existing = editing.editingId?.let { id ->
                repository.current.value.firstOrNull { it.id == id }
            }
            repository.upsert(
                McpServerConfig(
                    id = editing.editingId ?: newMcpServerId(),
                    label = editing.label.trim(),
                    url = editing.url.trim(),
                    token = editing.token.trim().takeIf { it.isNotBlank() },
                    enabled = existing?.enabled ?: true,
                    // Save the wire the probe actually succeeded on, so the first real call does
                    // not have to rediscover it.
                    transport = transport ?: McpTransportKind.STREAMABLE_HTTP,
                ),
            )
            _editor.value = null
        }
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(serverId, enabled) }
    }

    fun remove(serverId: String) {
        viewModelScope.launch { repository.remove(serverId) }
    }

    fun refresh(serverId: String) = repository.refresh(serverId)

    /** `https://mcp.example.com/mcp` → `mcp.example.com`, which is what people call it anyway. */
    private fun suggestLabel(url: String): String =
        runCatching { java.net.URI(url.trim()).host.orEmpty() }
            .getOrDefault("")
            .removePrefix("www.")
            .ifBlank { "MCP server" }
}
