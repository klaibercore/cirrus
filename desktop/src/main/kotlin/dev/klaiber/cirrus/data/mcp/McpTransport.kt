package dev.klaiber.cirrus.data.mcp

/**
 * Which wire an MCP server speaks.
 *
 * The protocol has had two HTTP transports. [STREAMABLE_HTTP] is the current one, where a POST
 * carries a JSON-RPC message and the reply comes back in the same exchange. [SSE] is the older
 * two-channel arrangement: a long-lived event stream for replies and a separate endpoint to POST
 * requests to. Servers built on earlier `@modelcontextprotocol/server-*` releases only speak the
 * latter, which is the whole reason both exist here.
 */
enum class McpTransportKind {
    STREAMABLE_HTTP,
    SSE,
}

/** One JSON-RPC exchange's raw reply. */
data class McpTransportReply(
    /** The JSON-RPC message body, or empty for a notification that expected no answer. */
    val body: String,
    /** Session id the server assigned, when it assigned one on this exchange. */
    val sessionId: String? = null,
)

/**
 * Moves JSON-RPC envelopes to a server and brings replies back.
 *
 * Deliberately narrow: the transport knows nothing about `initialize`, tools, or what a result
 * means. That lets [McpClient] hold the protocol in one place and swap the wire underneath it.
 */
interface McpTransport {

    /**
     * Sends one JSON-RPC message.
     *
     * [requestId] is the envelope's `id`, or null for a notification — which has no id, expects
     * no reply, and must not block waiting for one.
     */
    fun send(
        server: McpServerConfig,
        body: String,
        requestId: Long?,
        sessionId: String?,
    ): McpTransportReply

    /** Releases anything held for a server: an open stream, a cached endpoint. */
    fun close(serverId: String)
}

/**
 * Thrown when a server answers a streamable-HTTP request with the SSE handshake.
 *
 * Not an error the user should ever see — [McpClient] catches it during `initialize` and retries
 * over the other transport, so pointing Cirrus at an older server just works.
 */
internal class McpTransportMismatch : Exception("server speaks the SSE transport")
