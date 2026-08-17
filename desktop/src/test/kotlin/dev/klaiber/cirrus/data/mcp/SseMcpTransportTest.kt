package dev.klaiber.cirrus.data.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The SSE transport against a mock server that actually holds a stream open.
 *
 * A queued `MockResponse` cannot express this transport: the reply to a POST arrives on a
 * *different*, already-open connection, so the test server has to keep the event stream alive
 * and push into it. [SseServer] does that with a streaming [MockResponseBody].
 */
class SseMcpTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var sse: SseServer
    private lateinit var transport: SseMcpTransport
    private lateinit var config: McpServerConfig
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        sse = SseServer()
        server.dispatcher = sse
        server.start()
        transport = SseMcpTransport(OkHttpClient(), json)
        config = McpServerConfig(
            id = "everything",
            label = "Everything",
            url = server.url("/sse").toString(),
            token = "test-token",
            transport = McpTransportKind.SSE,
        )
    }

    @After
    fun tearDown() {
        transport.close(config.id)
        sse.release()
        server.close()
    }

    private fun envelope(id: Long, method: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", JsonObject(emptyMap()))
    }.toString()

    @Test
    fun `the handshake reads the message endpoint from the endpoint event`() = runTest {
        sse.endpointPath = "/messages?sessionId=abc123"
        sse.replyTo(id = 1, result = """{"protocolVersion":"2025-06-18"}""")

        val reply = transport.send(config, envelope(1, "initialize"), requestId = 1, sessionId = null)

        assertTrue(reply.body.contains("2025-06-18"))
        // The request went to the endpoint the stream named, not to the stream's own URL.
        val posted = sse.posts.take()
        assertEquals("/messages", posted.url.encodedPath)
        assertEquals("abc123", posted.url.queryParameter("sessionId"))
    }

    @Test
    fun `a relative endpoint is resolved against the stream url`() = runTest {
        sse.endpointPath = "messages"
        sse.replyTo(id = 1, result = "{}")

        transport.send(config, envelope(1, "initialize"), requestId = 1, sessionId = null)

        assertEquals("/messages", sse.posts.take().url.encodedPath)
    }

    @Test
    fun `the json-rpc envelope reaches the server intact`() = runTest {
        sse.replyTo(id = 7, result = """{"tools":[]}""")

        transport.send(config, envelope(7, "tools/list"), requestId = 7, sessionId = null)

        val body = json.parseToJsonElement(sse.posts.take().body!!.utf8()) as JsonObject
        assertEquals("2.0", body["jsonrpc"]!!.toString().trim('"'))
        assertEquals("tools/list", body["method"]!!.toString().trim('"'))
        assertEquals("7", body["id"]!!.toString())
    }

    @Test
    fun `the bearer token is sent on both channels`() = runTest {
        sse.replyTo(id = 1, result = "{}")

        transport.send(config, envelope(1, "initialize"), requestId = 1, sessionId = null)

        assertEquals("Bearer test-token", sse.streamAuthorization)
        assertEquals("Bearer test-token", sse.posts.take().headers["Authorization"])
    }

    @Test
    fun `replies are matched to requests by id, not by arrival order`() = runTest {
        // The stream carries someone else's answer first. Correlating by arrival rather than by
        // id would hand this back as the reply to request 1.
        sse.pushOnPost(
            id = 1,
            frames = listOf(
                sse.rpc(id = 99, result = """{"which":"not yours"}"""),
                sse.rpc(id = 1, result = """{"which":"yours"}"""),
            ),
        )

        val reply = transport.send(config, envelope(1, "a"), requestId = 1, sessionId = null)

        assertTrue(reply.body, reply.body.contains("yours"))
        assertTrue(reply.body, !reply.body.contains("not yours"))
    }

    @Test
    fun `a notification is posted without waiting for a reply`() = runTest {
        sse.endpointPath = "/messages"
        // Nothing is ever pushed for this one; a notification that blocked would hang the test.
        transport.send(
            config,
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            requestId = null,
            sessionId = null,
        )

        assertEquals("/messages", sse.posts.take().url.encodedPath)
    }

    @Test
    fun `closing cancels the event source`() = runTest {
        sse.replyTo(id = 1, result = "{}")
        transport.send(config, envelope(1, "initialize"), requestId = 1, sessionId = null)
        assertEquals(1, sse.streamsOpened.get())

        transport.close(config.id)

        // The server sees the stream go away rather than it lingering until the process exits.
        assertTrue(sse.streamClosed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a closed stream is reopened on the next send`() = runTest {
        sse.replyTo(id = 1, result = "{}")
        transport.send(config, envelope(1, "initialize"), requestId = 1, sessionId = null)
        transport.close(config.id)

        sse.replyTo(id = 2, result = """{"reconnected":true}""")
        val reply = transport.send(config, envelope(2, "tools/list"), requestId = 2, sessionId = null)

        assertTrue(reply.body.contains("reconnected"))
        assertEquals(2, sse.streamsOpened.get())
    }

    @Test
    fun `a stream that never sends an endpoint is retried and then reported`() = runTest {
        sse.withholdEndpoint = true

        try {
            transport.send(config, envelope(1, "initialize"), requestId = 1, sessionId = null)
            fail("expected the handshake to fail")
        } catch (expected: McpException.Transport) {
            assertTrue(expected.message!!.contains("Could not reach the MCP server"))
        }

        // Backoff means retries, not a single attempt given up on immediately.
        assertTrue("expected more than one attempt, got ${sse.streamsOpened.get()}", sse.streamsOpened.get() > 1)
    }

    @Test
    fun `a rejected stream surfaces the status code`() = runTest {
        sse.streamStatus = 401

        try {
            transport.send(config, envelope(1, "initialize"), requestId = 1, sessionId = null)
            fail("expected the handshake to fail")
        } catch (expected: McpException.Transport) {
            assertTrue(expected.message!!.contains("401"))
        }
    }

    @Test
    fun `a rejected post surfaces as a remote error`() = runTest {
        sse.postStatus = 403
        sse.endpointPath = "/messages"

        try {
            transport.send(config, envelope(1, "initialize"), requestId = 1, sessionId = null)
            fail("expected the post to fail")
        } catch (expected: McpException.Remote) {
            assertEquals(403, expected.code)
        }
    }

    /**
     * A mock MCP server speaking the two-channel transport.
     *
     * GET opens an event stream that stays open; POST returns 202 and *then* pushes the matching
     * reply into that stream. Replying only once the request has arrived is what makes this
     * faithful — a server cannot answer a request it has not been sent, and a mock that pushes
     * early tests a race no real deployment produces.
     */
    private class SseServer : Dispatcher() {

        var endpointPath: String = "/messages?sessionId=abc123"
        var withholdEndpoint: Boolean = false
        var streamStatus: Int = 200
        var postStatus: Int = 202

        val posts = LinkedBlockingQueue<RecordedRequest>()
        val streamsOpened = AtomicInteger()
        val streamClosed = CountDownLatch(1)

        @Volatile
        var streamAuthorization: String? = null

        /** JSON-RPC id -> the frames to push once a POST carrying that id arrives. */
        private val scripted = ConcurrentHashMap<String, List<String>>()

        /**
         * The stream frames are pushed into.
         *
         * Per-stream rather than shared: after a reconnect the previous writer may still be
         * parked on its queue, and a frame it took would be written to a socket the client has
         * already dropped.
         */
        @Volatile
        private var current: EventStream? = null

        @Volatile
        private var stopped = false

        /** Answers a request with a single result. */
        fun replyTo(id: Long, result: String) {
            pushOnPost(id, listOf(rpc(id, result)))
        }

        /** Answers a request with an arbitrary sequence of frames, in the given order. */
        fun pushOnPost(id: Long, frames: List<String>) {
            scripted[id.toString()] = frames
        }

        fun rpc(id: Long, result: String): String =
            """{"jsonrpc":"2.0","id":$id,"result":$result}"""

        fun release() {
            stopped = true
            // A sentinel so a parked writer wakes and returns instead of blocking teardown.
            current?.queue?.put("")
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method == "POST") {
                posts.put(request)
                if (postStatus in 200..299) {
                    idOf(request)?.let { id ->
                        val stream = current
                        scripted[id]?.forEach { frame -> stream?.queue?.put(frame) }
                    }
                }
                return MockResponse.Builder().code(postStatus).body("").build()
            }

            streamsOpened.incrementAndGet()
            streamAuthorization = request.headers["Authorization"]
            if (streamStatus != 200) {
                return MockResponse.Builder().code(streamStatus).body("").build()
            }

            return MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(EventStream().also { current = it })
                .build()
        }

        /** Pulls the JSON-RPC id back out of a posted envelope. A notification has none. */
        private fun idOf(request: RecordedRequest): String? =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(request.body?.utf8().orEmpty())?.groupValues?.get(1)

        override fun close() {
            release()
        }

        /** Writes SSE frames for as long as the client keeps reading. */
        private inner class EventStream : MockResponseBody {
            val queue = LinkedBlockingQueue<String>()

            // Unknown up front: the stream is open-ended, so it must be chunked.
            override val contentLength: Long get() = -1L

            override fun writeTo(sink: BufferedSink) {
                try {
                    if (!withholdEndpoint) {
                        sink.writeUtf8("event: endpoint\ndata: $endpointPath\n\n")
                        sink.flush()
                    }
                    while (!stopped) {
                        val message = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                        if (message == null) {
                            // An SSE comment. Nothing consumes it, but writing something is the
                            // only way to notice the client has gone away.
                            sink.writeUtf8(":\n\n")
                            sink.flush()
                            continue
                        }
                        if (message.isEmpty()) break
                        sink.writeUtf8("event: message\ndata: $message\n\n")
                        sink.flush()
                    }
                } catch (ignored: Exception) {
                    // The client cancelled; that is the tested behaviour, not a failure.
                } finally {
                    streamClosed.countDown()
                }
            }
        }

        private companion object {
            const val POLL_MILLIS = 100L
        }
    }
}
