package dev.klaiber.cirrus.data.mcp

import dev.klaiber.cirrus.di.GitHubHttp
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** A remote MCP server the user has attached, such as GitHub's. */
data class McpServerConfig(
    val id: String,
    val label: String,
    val url: String,
    /** Sent as `Authorization: Bearer`. Most hosted servers take a PAT here. */
    val token: String? = null,
    val enabled: Boolean = true,
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
 * A Model Context Protocol client over the streamable HTTP transport.
 *
 * Enough of the protocol to attach a remote server and use its tools: `initialize`, then
 * `tools/list` and `tools/call`. Server-initiated requests, sampling and resources are not
 * implemented, because a chat client that only consumes tools never needs them.
 *
 * The transport is deliberately request/response. A server may reply with either a single JSON
 * body or an SSE stream containing one JSON-RPC message; both are handled, but no long-lived
 * stream is held open, so there is no connection to keep alive across process death.
 */
@Singleton
class McpClient @Inject constructor(
    // Shares the GitHub client because both need bearer auth and neither may carry the Ollama key.
    @GitHubHttp private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private val nextId = AtomicLong(1)

    /** Session ids are per-server and handed out by `initialize`. */
    private val sessions = mutableMapOf<String, String>()

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
    }

    /**
     * Performs the handshake once per server.
     *
     * MCP requires `initialize` before anything else, and the session id it returns has to be
     * echoed on every later request.
     */
    private fun ensureInitialized(server: McpServerConfig) {
        synchronized(sessions) { if (sessions.containsKey(server.id)) return }

        val response = request(
            server = server,
            body = jsonRpc(
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", CLIENT_NAME)
                        put("version", CLIENT_VERSION)
                    }
                },
            ),
        )

        response.sessionId?.let { id -> synchronized(sessions) { sessions[server.id] = id } }
        parseResult(response.body)

        // The spec requires this notification before normal operation; it expects no reply.
        runCatching {
            request(
                server = server,
                body = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                }.toString(),
            )
        }
    }

    private fun call(server: McpServerConfig, method: String, params: JsonObject): JsonObject =
        parseResult(request(server, jsonRpc(method, params)).body)

    private fun jsonRpc(method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", nextId.getAndIncrement())
        put("method", method)
        put("params", params)
    }.toString()

    private data class RawResponse(val body: String, val sessionId: String?)

    private fun request(server: McpServerConfig, body: String): RawResponse {
        val builder = Request.Builder()
            .url(server.url)
            .header("Content-Type", "application/json")
            // A server may answer with either; saying we accept both is what the spec asks.
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))

        server.token?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Authorization", "Bearer $it") }
        synchronized(sessions) { sessions[server.id] }
            ?.let { builder.header("Mcp-Session-Id", it) }

        val response = try {
            httpClient.newCall(builder.build()).execute()
        } catch (io: IOException) {
            throw McpException.Transport(io.message ?: "network error")
        }

        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                throw McpException.Remote(it.code, text.take(MAX_ERROR_CHARS))
            }
            return RawResponse(
                body = if (it.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
                    extractSseData(text)
                } else {
                    text
                },
                sessionId = it.header("Mcp-Session-Id"),
            )
        }
    }

    /**
     * Pulls the JSON-RPC message out of an SSE body.
     *
     * Only the last `data:` payload matters here: a request/response exchange carries one
     * message, and any earlier frames are keep-alives or progress notifications.
     */
    private fun extractSseData(text: String): String = text
        .lineSequence()
        .filter { it.startsWith("data:") }
        .map { it.removePrefix("data:").trim() }
        .filter { it.isNotEmpty() }
        .lastOrNull()
        ?: throw McpException.Protocol("event stream contained no data frame")

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
        const val PROTOCOL_VERSION = "2025-06-18"
        const val CLIENT_NAME = "cirrus"
        const val CLIENT_VERSION = "1.0.0"
        const val MAX_ERROR_CHARS = 500
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_SCHEMA: JsonElement = buildJsonObject { put("type", "object") }
    }
}
