package dev.klaiber.cirrus.data.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: McpClient
    private lateinit var config: McpServerConfig

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = McpClient(OkHttpClient(), Json { ignoreUnknownKeys = true })
        config = McpServerConfig(
            id = "github",
            label = "GitHub",
            url = server.url("/mcp").toString(),
            token = "test-token",
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun initializeResponse() = MockResponse.Builder()
        .addHeader("Mcp-Session-Id", "session-123")
        .body("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""")
        .build()

    private fun emptyResponse() = MockResponse.Builder().body("").build()

    @Test
    fun `handshake happens before the first call and carries client info`() = runTest {
        server.enqueue(initializeResponse())
        server.enqueue(emptyResponse())
        server.enqueue(
            MockResponse.Builder()
                .body("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}""")
                .build()
        )

        client.listTools(config)

        val initialize = server.takeRequest()
        val body = initialize.body!!.utf8()
        assertTrue(body.contains("\"method\":\"initialize\""))
        assertTrue(body.contains("cirrus"))
        assertEquals("2025-06-18", initialize.headers["MCP-Protocol-Version"])
        assertEquals("Bearer test-token", initialize.headers["Authorization"])
    }

    @Test
    fun `the session id from initialize is echoed on later requests`() = runTest {
        server.enqueue(initializeResponse())
        server.enqueue(emptyResponse())
        server.enqueue(
            MockResponse.Builder()
                .body("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}""")
                .build()
        )

        client.listTools(config)

        server.takeRequest() // initialize
        server.takeRequest() // notifications/initialized
        assertEquals("session-123", server.takeRequest().headers["Mcp-Session-Id"])
    }

    @Test
    fun `tools are parsed with their schema left intact`() = runTest {
        server.enqueue(initializeResponse())
        server.enqueue(emptyResponse())
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"jsonrpc":"2.0","id":2,"result":{"tools":[
                      {"name":"create_issue","description":"Open an issue",
                       "inputSchema":{"type":"object","properties":{"title":{"type":"string"}}}}
                    ]}}
                    """.trimIndent()
                )
                .build()
        )

        val tools = client.listTools(config)
        assertEquals(1, tools.size)
        assertEquals("create_issue", tools[0].name)
        assertEquals("Open an issue", tools[0].description)
        // The schema is forwarded to the model unchanged, so it must survive the round trip.
        assertTrue(tools[0].inputSchema.toString().contains("\"title\""))
    }

    @Test
    fun `an sse reply is unwrapped`() = runTest {
        server.enqueue(initializeResponse())
        server.enqueue(emptyResponse())
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"ping\"}]}}\n\n"
                )
                .build()
        )

        val tools = client.listTools(config)
        assertEquals("ping", tools.single().name)
    }

    @Test
    fun `tool results are flattened to their text parts`() = runTest {
        server.enqueue(initializeResponse())
        server.enqueue(emptyResponse())
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"jsonrpc":"2.0","id":2,"result":{"content":[
                      {"type":"text","text":"line one"},
                      {"type":"image","data":"ignored"},
                      {"type":"text","text":"line two"}
                    ]}}
                    """.trimIndent()
                )
                .build()
        )

        val result = client.callTool(config, "ping", JsonObject(emptyMap()))
        assertEquals("line one\nline two", result)
    }

    @Test
    fun `a tool error becomes a readable error object`() = runTest {
        server.enqueue(initializeResponse())
        server.enqueue(emptyResponse())
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"jsonrpc":"2.0","id":2,"result":{"isError":true,
                     "content":[{"type":"text","text":"repository not found"}]}}
                    """.trimIndent()
                )
                .build()
        )

        val result = client.callTool(config, "read", buildJsonObject { put("repo", "x") })
        assertTrue(result.contains("\"error\""))
        assertTrue(result.contains("repository not found"))
    }

    @Test
    fun `a json-rpc error is raised rather than returned`() = runTest {
        server.enqueue(initializeResponse())
        server.enqueue(emptyResponse())
        server.enqueue(
            MockResponse.Builder()
                .body("""{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found"}}""")
                .build()
        )

        val error = runCatching { client.listTools(config) }.exceptionOrNull()
        assertTrue(error is McpException.Remote)
        assertEquals(-32601, (error as McpException.Remote).code)
    }

    @Test
    fun `an http failure is reported with its status`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("unauthorized").build())
        val error = runCatching { client.listTools(config) }.exceptionOrNull()
        assertTrue(error is McpException.Remote)
        assertEquals(401, (error as McpException.Remote).code)
    }
}
