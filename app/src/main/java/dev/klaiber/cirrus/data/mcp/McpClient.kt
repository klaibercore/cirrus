package dev.klaiber.cirrus.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** The protocol revision Cirrus implements, sent on every request. */
internal const val MCP_PROTOCOL_VERSION = "2025-06-18"

/** A remote MCP server the user has attached, such as GitHub's. */
data class McpServerConfig(
    val id: String,
    val label: String,
    val url: String,
    /** Sent as `Authorization: Bearer`. Most hosted servers take a PAT here. */
    val token: String? = null,
    val enabled: Boolean = true,
    /**
     * Which wire to speak. New servers default to the current transport and fall back to SSE
     * automatically if the server turns out to want it, so this rarely has to be set by hand.
     */
    val transport: McpTransportKind = McpTransportKind.STREAMABLE_HTTP,
)

/** A tool as the server describes it. [inputSchema] is passed to the model unchanged. */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
)

sealed class McpException(message: String) : IOException(message) {
    class Transport(detail: String) : McpException("Could not reach the MCP server: $detail")
    class Protocol(detail: String) : McpException("MCP server returned something unexpected: $detail")
    class Remote(val code: Int, detail: String) : McpException("MCP error $code: $detail")
}

/**
 * A Model Context Protocol client.
 *
 * Enough of the protocol to attach a remote server and use its tools: `initialize`, then
 * `tools/list` and `tools/call`. Server-initiated requests, sampling and resources are not
 * implemented, because a chat client that only consumes tools never needs them.
 *
 * The wire itself lives behind [McpTransport]. This class holds the protocol — envelope shape,
 * handshake order, session ids — in one place, so supporting a second transport did not mean
 * writing the protocol twice.
 */
@Singleton
class McpClient @Inject constructor(
    private val streamableHttp: StreamableHttpMcpTransport,
    private val sse: SseMcpTransport,
    private val json: Json,
) {
    private val nextId = AtomicLong(1)

    /** Session ids are per-server and handed out by `initialize`. */
    private val sessions = mutableMapOf<String, String>()

    /**
     * Transport actually in use per server, which may differ from the configured one once the
     * server has told us it speaks the other.
     */
    private val transports = mutableMapOf<String, McpTransportKind>()

    suspend fun listTools(server: McpServerConfig): List<McpToolDescriptor> =
        withContext(Dispatchers.IO) {
            ensureInitialized(server)
            val result = call(server, "tools/list", JsonObject(emptyMap()))
            val tools = result["tools"]?.jsonArray
                ?: throw McpException.Protocol("tools/list returned no tools array")

            tools.mapNotNull { element ->
                val tool = element.jsonObject
                val name = tool["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                McpToolDescriptor(
                    name = name,
                    description = tool["description"]?.jsonPrimitive?.content.orEmpty(),
                    inputSchema = tool["inputSchema"] ?: EMPTY_SCHEMA,
                )
            }
        }

    /** Runs a tool and flattens the result into the text a model can read. */
    suspend fun callTool(
        server: McpServerConfig,
        name: String,
        arguments: JsonObject,
    ): String = withContext(Dispatchers.IO) {
        ensureInitialized(server)
        val result = call(
            server = server,
            method = "tools/call",
            params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )

        val isError = result["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val text = result["content"]?.jsonArray
            ?.mapNotNull { entry ->
                val part = entry.jsonObject
                when (part["type"]?.jsonPrimitive?.content) {
                    "text" -> part["text"]?.jsonPrimitive?.content
                    // Images and embedded resources cannot go into a tool result message.
                    else -> null
                }
            }
            ?.joinToString("\n")
            .orEmpty()

        if (isError) {
            buildJsonObject { put("error", text.ifBlank { "the tool reported a failure" }) }
                .toString()
        } else {
            text.ifBlank { result.toString() }
        }
    }

    fun forget(serverId: String) {
        synchronized(sessions) { sessions.remove(serverId) }
        synchronized(transports) { transports.remove(serverId) }
        streamableHttp.close(serverId)
        sse.close(serverId)
    }

    /** Which transport a server ended up on, once it has been talked to. */
    fun transportFor(serverId: String): McpTransportKind? =
        synchronized(transports) { transports[serverId] }

    /**
     * Performs the handshake once per server.
     *
     * MCP requires `initialize` before anything else, and the session id it returns has to be
     * echoed on every later request.
     *
     * A server configured as streamable-HTTP that answers with the SSE handshake is not an error
     * — it is an older server. The handshake is retried on the other transport and the choice
     * remembered, so attaching one costs the user no configuration.
     */
    private fun ensureInitialized(server: McpServerConfig) {
        synchronized(sessions) { if (sessions.containsKey(server.id)) return }

        val response = try {
            initialize(server, server.transport)
        } catch (mismatch: McpTransportMismatch) {
            synchronized(transports) { transports[server.id] = McpTransportKind.SSE }
            initialize(server, McpTransportKind.SSE)
        }

        response.sessionId?.let { id -> synchronized(sessions) { sessions[server.id] = id } }
        parseResult(response.body)

        // The spec requires this notification before normal operation; it expects no reply.
        runCatching {
            send(
                server = server,
                body = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                }.toString(),
                requestId = null,
            )
        }
    }

    private fun initialize(
        server: McpServerConfig,
        kind: McpTransportKind,
    ): McpTransportReply {
        synchronized(transports) { transports[server.id] = kind }
        val id = nextId.getAndIncrement()
        return send(
            server = server,
            body = envelope(
                id = id,
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", CLIENT_NAME)
                        put("version", CLIENT_VERSION)
                    }
                },
            ),
            requestId = id,
        )
    }

    private fun call(server: McpServerConfig, method: String, params: JsonObject): JsonObject {
        val id = nextId.getAndIncrement()
        val reply = send(server, envelope(id, method, params), requestId = id)
        return parseResult(reply.body)
    }

    private fun send(
        server: McpServerConfig,
        body: String,
        requestId: Long?,
    ): McpTransportReply {
        val kind = synchronized(transports) { transports[server.id] } ?: server.transport
        val transport = when (kind) {
            McpTransportKind.STREAMABLE_HTTP -> streamableHttp
            McpTransportKind.SSE -> sse
        }
        val sessionId = synchronized(sessions) { sessions[server.id] }
        return transport.send(server, body, requestId, sessionId)
    }

    private fun envelope(id: Long, method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }.toString()

    private fun parseResult(body: String): JsonObject {
        if (body.isBlank()) return JsonObject(emptyMap())
        val message = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw McpException.Protocol("body was not JSON") }

        message["error"]?.jsonObject?.let { error ->
            throw McpException.Remote(
                code = error["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                detail = error["message"]?.jsonPrimitive?.content ?: "unknown error",
            )
        }
        return message["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private companion object {
        const val CLIENT_NAME = "cirrus"
        const val CLIENT_VERSION = "1.0.0"
        val EMPTY_SCHEMA: JsonElement = buildJsonObject { put("type", "object") }
    }
}
