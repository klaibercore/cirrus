package dev.klaiber.cirrus.data.mcp

import dev.klaiber.cirrus.di.McpHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The current MCP HTTP transport: one POST carries a request, and the reply comes back in the
 * same exchange.
 *
 * Request/response by design. A server may answer with either a plain JSON body or an SSE frame
 * containing one JSON-RPC message; both are handled, but no long-lived stream is held open, so
 * there is no connection to keep alive across process death.
 */
@Singleton
class StreamableHttpMcpTransport @Inject constructor(
    // A client with no auth interceptor: the Authorization header below is the server's own token.
    @McpHttp private val httpClient: OkHttpClient,
) : McpTransport {

    override fun send(
        server: McpServerConfig,
        body: String,
        requestId: Long?,
        sessionId: String?,
    ): McpTransportReply {
        val builder = Request.Builder()
            .url(server.url)
            .header("Content-Type", "application/json")
            // A server may answer with either; saying we accept both is what the spec asks.
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))

        server.token?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Authorization", "Bearer $it") }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }

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
            val isEventStream = it.header("Content-Type").orEmpty()
                .startsWith("text/event-stream")
            return McpTransportReply(
                body = if (isEventStream) extractSseData(text) else text,
                sessionId = it.header("Mcp-Session-Id"),
            )
        }
    }

    override fun close(serverId: String) {
        // Nothing is held open, so there is nothing to release.
    }

    /**
     * Pulls the JSON-RPC message out of an SSE body.
     *
     * Only the last `data:` payload matters: a request/response exchange carries one message, and
     * any earlier frames are keep-alives or progress notifications.
     *
     * An `endpoint` event means this is not a streamable-HTTP server at all but the older
     * two-channel transport, which cannot be served from a single exchange.
     */
    private fun extractSseData(text: String): String {
        if (text.lineSequence().any { it.startsWith("event:") && it.contains("endpoint") }) {
            throw McpTransportMismatch()
        }
        return text
            .lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull()
            ?: throw McpException.Protocol("event stream contained no data frame")
    }

    private companion object {
        const val MAX_ERROR_CHARS = 500
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
