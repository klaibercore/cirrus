package dev.klaiber.cirrus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.klaiber.cirrus.data.mcp.McpClient
import dev.klaiber.cirrus.data.mcp.McpException
import dev.klaiber.cirrus.data.mcp.McpServerConfig
import dev.klaiber.cirrus.data.mcp.McpToolDescriptor
import dev.klaiber.cirrus.data.mcp.McpTransportKind
import dev.klaiber.cirrus.data.prefs.SecretCipher
import dev.klaiber.cirrus.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What a server is currently doing, as far as the UI is concerned. */
sealed interface McpServerState {
    data object Idle : McpServerState

    data object Connecting : McpServerState

    data class Ready(val tools: List<McpToolDescriptor>) : McpServerState

    data class Failed(val message: String) : McpServerState
}

/** The outcome of pointing Cirrus at a URL before committing to it. */
sealed interface McpProbeResult {
    data class Success(
        val tools: List<McpToolDescriptor>,
        /** Which wire the server turned out to speak, which may not be the one we tried first. */
        val transport: McpTransportKind,
    ) : McpProbeResult

    data class Failure(val message: String) : McpProbeResult
}

/**
 * The MCP servers the user has attached, and what each one offers.
 *
 * Two things live here that look separable but are not. The configs are durable and belong in
 * DataStore; the tool lists are derived, only obtainable over the network, and go stale the
 * moment a server is edited. Keeping them together means there is one place that knows a
 * server's tools were fetched for *this* version of its config.
 *
 * Tokens get the same Keystore-backed envelope encryption as the Ollama and GitHub keys — an MCP
 * token is a third-party credential and no less worth protecting.
 */
@Singleton
class McpServerRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: SecretCipher,
    private val client: McpClient,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {

    val servers: Flow<List<McpServerConfig>> = dataStore.data.map { prefs ->
        prefs[Keys.SERVERS]?.let(::decode).orEmpty()
    }

    val current: StateFlow<List<McpServerConfig>> =
        servers.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _states = MutableStateFlow<Map<String, McpServerState>>(emptyMap())

    /** Per-server connection state, keyed by [McpServerConfig.id]. */
    val states: StateFlow<Map<String, McpServerState>> = _states.asStateFlow()

    /**
     * Every tool from every enabled server, flattened for the tool registry.
     *
     * A snapshot rather than a suspend call on purpose: `ToolRegistry.definitions` is read
     * synchronously while a turn is being built, and that is the wrong moment to discover a
     * server is unreachable. Refreshes happen when the server list changes, not mid-turn.
     */
    private val _bindings = MutableStateFlow<List<McpToolBinding>>(emptyList())
    val bindings: StateFlow<List<McpToolBinding>> = _bindings.asStateFlow()

    init {
        // Re-resolve tools whenever the set of enabled servers changes in a way that matters.
        scope.launch {
            var previous: List<McpServerConfig> = emptyList()
            servers.collect { configs ->
                val relevant = configs.filter { it.enabled }
                if (relevant.connectionSignature() != previous.connectionSignature()) {
                    previous = relevant
                    refreshAll(relevant)
                }
            }
        }
    }

    suspend fun upsert(server: McpServerConfig) {
        mutate { existing ->
            val index = existing.indexOfFirst { it.id == server.id }
            if (index >= 0) existing.toMutableList().also { it[index] = server } else existing + server
        }
        // The old session was negotiated against the old URL and token.
        client.forget(server.id)
    }

    suspend fun remove(serverId: String) {
        mutate { existing -> existing.filterNot { it.id == serverId } }
        client.forget(serverId)
        _states.value = _states.value - serverId
    }

    suspend fun setEnabled(serverId: String, enabled: Boolean) {
        mutate { existing ->
            existing.map { if (it.id == serverId) it.copy(enabled = enabled) else it }
        }
    }

    /**
     * Talks to a server without saving it.
     *
     * This is the whole of discovery: `initialize` then `tools/list`, which is the only honest way
     * to answer "what would attaching this give me". A URL that parses tells you nothing.
     */
    suspend fun probe(url: String, token: String?): McpProbeResult {
        val candidate = McpServerConfig(
            id = PROBE_ID,
            label = "probe",
            url = url.trim(),
            token = token?.trim()?.takeIf { it.isNotBlank() },
        )
        client.forget(PROBE_ID)
        return try {
            val tools = client.listTools(candidate)
            McpProbeResult.Success(
                tools = tools,
                transport = client.transportFor(PROBE_ID) ?: McpTransportKind.STREAMABLE_HTTP,
            )
        } catch (mcp: McpException) {
            McpProbeResult.Failure(mcp.message ?: "the server could not be reached")
        } catch (other: Exception) {
            McpProbeResult.Failure(other.message ?: "the server could not be reached")
        } finally {
            client.forget(PROBE_ID)
        }
    }

    /** Re-reads one server's tools, for the refresh affordance on its row. */
    fun refresh(serverId: String) {
        val server = current.value.firstOrNull { it.id == serverId } ?: return
        scope.launch { refreshOne(server) }
    }

    private suspend fun refreshAll(configs: List<McpServerConfig>) {
        // Drop tools from servers that are gone or switched off before doing any I/O.
        _bindings.value = _bindings.value.filter { binding ->
            configs.any { it.id == binding.server.id }
        }
        configs.forEach { refreshOne(it) }
    }

    private suspend fun refreshOne(server: McpServerConfig) {
        _states.value = _states.value + (server.id to McpServerState.Connecting)
        client.forget(server.id)
        try {
            val tools = client.listTools(server)
            _states.value = _states.value + (server.id to McpServerState.Ready(tools))
            _bindings.value = _bindings.value.filterNot { it.server.id == server.id } +
                tools.map { McpToolBinding(server, it) }
        } catch (failure: Exception) {
            _states.value = _states.value +
                (server.id to McpServerState.Failed(failure.message ?: "could not be reached"))
            // A server that cannot be listed must not keep offering stale tools to the model.
            _bindings.value = _bindings.value.filterNot { it.server.id == server.id }
        }
    }

    private suspend fun mutate(block: (List<McpServerConfig>) -> List<McpServerConfig>) {
        dataStore.edit { prefs ->
            val existing = prefs[Keys.SERVERS]?.let(::decode).orEmpty()
            prefs[Keys.SERVERS] = encode(block(existing))
        }
    }

    private fun encode(servers: List<McpServerConfig>): String = json.encodeToString(
        StoredServers.serializer(),
        StoredServers(
            servers.map { server ->
                StoredServer(
                    id = server.id,
                    label = server.label,
                    url = server.url,
                    encryptedToken = server.token
                        ?.takeIf { it.isNotBlank() }
                        ?.let(secretCipher::encrypt),
                    enabled = server.enabled,
                    transport = server.transport.name,
                )
            },
        ),
    )

    private fun decode(raw: String): List<McpServerConfig> =
        runCatching { json.decodeFromString(StoredServers.serializer(), raw) }
            .getOrNull()
            ?.servers
            ?.map { stored ->
                McpServerConfig(
                    id = stored.id,
                    label = stored.label,
                    url = stored.url,
                    token = stored.encryptedToken?.let(secretCipher::decrypt),
                    enabled = stored.enabled,
                    transport = runCatching { McpTransportKind.valueOf(stored.transport) }
                        .getOrDefault(McpTransportKind.STREAMABLE_HTTP),
                )
            }
            .orEmpty()

    /**
     * What has to change before the tool lists are worth re-fetching.
     *
     * Renaming a server is not a reason to go back to the network; changing where it is or how we
     * authenticate to it is.
     */
    private fun List<McpServerConfig>.connectionSignature(): List<String> =
        map { "${it.id}|${it.url}|${it.token.orEmpty()}|${it.transport}" }

    private object Keys {
        val SERVERS = stringPreferencesKey("mcp_servers")
    }

    private companion object {
        /** Reserved id for the not-yet-saved server behind the probe button. */
        const val PROBE_ID = "__probe__"
    }

    @Serializable
    private data class StoredServers(val servers: List<StoredServer> = emptyList())

    @Serializable
    private data class StoredServer(
        val id: String,
        val label: String,
        val url: String,
        val encryptedToken: String? = null,
        val enabled: Boolean = true,
        val transport: String = McpTransportKind.STREAMABLE_HTTP.name,
    )
}

/** One tool, and the server it came from. */
data class McpToolBinding(
    val server: McpServerConfig,
    val descriptor: McpToolDescriptor,
)

/** Ids are generated here so the UI never has to invent one. */
fun newMcpServerId(): String = UUID.randomUUID().toString()
