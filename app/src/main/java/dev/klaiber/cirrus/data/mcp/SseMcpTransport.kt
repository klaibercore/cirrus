package dev.klaiber.cirrus.data.mcp

import dev.klaiber.cirrus.di.GitHubHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The older two-channel MCP transport, still the only one some servers offer.
 *
 * The shape is asymmetric, which is most of what makes it awkward:
 *
 * 1. A GET opens a long-lived event stream.
 * 2. The server's first event, `event: endpoint`, names a *second* URL to post to.
 * 3. Requests are POSTed there and acknowledged with 202 — the actual JSON-RPC reply arrives
 *    later, on the event stream from step 1.
 *
 * So a reply has to be matched back to its request by JSON-RPC id rather than by which socket it
 * came from. Each in-flight request parks on a one-slot queue keyed by that id; the stream
 * listener drops the message into whichever queue matches. Registering the queue *before* the
 * POST goes out matters: a fast server can answer before the caller starts waiting.
 *
 * Kept blocking to match [StreamableHttpMcpTransport], because [McpClient] already confines
 * every call to `Dispatchers.IO`.
 */
@Singleton
class SseMcpTransport @Inject constructor(
    @GitHubHttp private val httpClient: OkHttpClient,
    private val json: Json,
) : McpTransport {

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * One server's open stream, plus everything waiting on it.
     *
     * [endpointReady] is counted down by either outcome — an endpoint event or a failure — so a
     * caller can never park on a stream that has already died.
     */
    private class Session(
        val eventSource: EventSource,
        val endpointReady: CountDownLatch = CountDownLatch(1),
    ) {
        @Volatile
        var endpoint: HttpUrl? = null

        @Volatile
        var failure: String? = null

        val replies = ConcurrentHashMap<String, ArrayBlockingQueue<String>>()

        fun deliver(id: String, message: String) {
            replies[id]?.offer(message)
        }

        fun fail(detail: String) {
            failure = detail
            endpointReady.countDown()
            // Unblock anything already parked; each waiter rechecks `failure` after it wakes.
            replies.values.forEach { it.offer("") }
        }
    }

    override fun send(
        server: McpServerConfig,
        body: String,
        requestId: Long?,
        sessionId: String?,
    ): McpTransportReply {
        val session = connect(server)
        val endpoint = session.endpoint
            ?: throw McpException.Transport(session.failure ?: "the event stream never opened")

        // A notification has no id and no reply, so nothing is registered and nothing is awaited.
        val slot = requestId?.let { id ->
            ArrayBlockingQueue<String>(1).also { session.replies[id.toString()] = it }
        }

        try {
            postMessage(server, endpoint, body)
            if (slot == null) return McpTransportReply(body = "")

            val reply = slot.poll(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?: throw McpException.Transport(
                    "the server accepted the request but sent no reply within " +
                        "$REPLY_TIMEOUT_SECONDS seconds",
                )
            // An empty slot means fail() woke us rather than a real message arriving.
            if (reply.isEmpty()) {
                throw McpException.Transport(session.failure ?: "the event stream closed")
            }
            return McpTransportReply(body = reply)
        } finally {
            requestId?.let { session.replies.remove(it.toString()) }
        }
    }

    override fun close(serverId: String) {
        sessions.remove(serverId)?.let { session ->
            session.eventSource.cancel()
            session.fail("closed")
        }
    }

    /**
     * Opens the event stream, or returns the one already open.
     *
     * Retried with backoff because the failure this protects against is a server that has just
     * been started — a connection refused a moment before it binds its port is not worth
     * surfacing to the user as "could not reach the MCP server".
     */
    private fun connect(server: McpServerConfig): Session {
        sessions[server.id]?.let { existing ->
            if (existing.failure == null) return existing
            // A dead stream is worse than none: drop it so this attempt opens a fresh one.
            sessions.remove(server.id, existing)
        }

        var lastFailure: String? = null
        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                Thread.sleep(BACKOFF_MILLIS shl (attempt - 1))
            }
            val session = openStream(server)
            sessions[server.id] = session

            val opened = session.endpointReady.await(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (opened && session.endpoint != null) return session

            session.eventSource.cancel()
            sessions.remove(server.id, session)
            lastFailure = session.failure
                ?: "the server did not send an endpoint event within " +
                "$HANDSHAKE_TIMEOUT_SECONDS seconds"
        }
        throw McpException.Transport(lastFailure ?: "could not open the event stream")
    }

    private fun openStream(server: McpServerConfig): Session {
        val request = Request.Builder()
            .url(server.url)
            .header("Accept", "text/event-stream")
            .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            .apply {
                server.token?.takeIf { it.isNotBlank() }
                    ?.let { header("Authorization", "Bearer $it") }
            }
            .get()
            .build()

        lateinit var session: Session
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                when (type) {
                    "endpoint" -> session.resolveEndpoint(server, data)
                    // Unnamed events default to "message" per the SSE spec.
                    "message", null -> session.deliverMessage(data)
                    else -> Unit // Keep-alives and progress pings carry nothing to correlate.
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                session.fail(
                    t?.message ?: response?.let { "server returned ${it.code}" } ?: "stream failed",
                )
            }

            override fun onClosed(eventSource: EventSource) {
                session.fail("the server closed the event stream")
            }
        }

        session = Session(EventSources.createFactory(httpClient).newEventSource(request, listener))
        return session
    }

    /** The endpoint event carries a URL that is usually relative to the stream's own. */
    private fun Session.resolveEndpoint(server: McpServerConfig, data: String) {
        val resolved = server.url.toHttpUrl().resolve(data.trim())
        if (resolved == null) {
            fail("the endpoint event named an unusable URL: ${data.take(MAX_ERROR_CHARS)}")
        } else {
            endpoint = resolved
            endpointReady.countDown()
        }
    }

    private fun Session.deliverMessage(data: String) {
        val id = runCatching {
            json.parseToJsonElement(data).jsonObject["id"]?.jsonPrimitive?.content
        }.getOrNull() ?: return
        deliver(id, data)
    }

    private fun postMessage(server: McpServerConfig, endpoint: HttpUrl, body: String) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            .apply {
                server.token?.takeIf { it.isNotBlank() }
                    ?.let { header("Authorization", "Bearer $it") }
            }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (io: IOException) {
            throw McpException.Transport(io.message ?: "network error")
        }
        response.use {
            // 202 is the expected answer; the reply itself comes back on the event stream.
            if (!it.isSuccessful) {
                throw McpException.Remote(it.code, it.body.string().take(MAX_ERROR_CHARS))
            }
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_SECONDS = 10L
        const val REPLY_TIMEOUT_SECONDS = 60L
        const val MAX_CONNECT_ATTEMPTS = 3
        const val BACKOFF_MILLIS = 250L
        const val MAX_ERROR_CHARS = 500
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
