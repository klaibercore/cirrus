package dev.klaiber.cirrus.data.remote

import app.cash.turbine.test
import dev.klaiber.cirrus.data.remote.dto.ChatRequestDto
import dev.klaiber.cirrus.data.remote.dto.MessageDto
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OllamaClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OllamaClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val credentials = ApiCredentials()
        credentials.update(apiKey = null, baseUrl = server.url("/").toString())
        client = OllamaClient(OkHttpClient(), json, credentials)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun request() = ChatRequestDto(
        model = "qwen3",
        messages = listOf(MessageDto(role = "user", content = "hi")),
    )

    @Test
    fun `streamChat emits chunks and stops at done`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"model":"qwen3","message":{"role":"assistant","content":"a"},"done":false}
                    {"model":"qwen3","message":{"role":"assistant","content":"b"},"done":false}
                    {"model":"qwen3","done":true,"done_reason":"stop"}
                    """.trimIndent()
                )
                .build()
        )
        val chunks = client.streamChat(request()).toList()
        assertEquals(3, chunks.size)
        assertEquals("a", chunks[0].message?.content)
        assertEquals("b", chunks[1].message?.content)
        assertTrue(chunks[2].done)
        assertEquals("stop", chunks[2].doneReason)
    }

    @Test
    fun `streamChat maps 401 to Unauthorized`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":"bad key"}""")
                .build()
        )
        client.streamChat(request()).test {
            assertTrue(awaitError() is OllamaException.Unauthorized)
        }
    }

    @Test
    fun `streamChat maps 404 to ModelNotFound`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .body("""{"error":"model not found"}""")
                .build()
        )
        client.streamChat(request()).test {
            assertTrue(awaitError() is OllamaException.ModelNotFound)
        }
    }

    @Test
    fun `streamChat maps 429 to RateLimited with retry hint`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "30")
                .body("""{"error":"slow down"}""")
                .build()
        )
        client.streamChat(request()).test {
            val error = awaitError()
            assertTrue(error is OllamaException.RateLimited)
            assertEquals(30L, (error as OllamaException.RateLimited).retryAfterSeconds)
        }
    }

    @Test
    fun `streamChat surfaces error field in 200 response`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"error":"model not found"}""")
                .build()
        )
        client.streamChat(request()).test {
            val error = awaitError()
            assertTrue(error is OllamaException.ServerError)
            assertEquals(200, (error as OllamaException.ServerError).code)
        }
    }

    @Test
    fun `listModels parses the tags response`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"models":[{"name":"qwen3:8b","size":1000,"details":{"parameter_size":"8B","quantization_level":"Q4_K_M"}}]}
                    """.trimIndent()
                )
                .build()
        )
        val models = client.listModels()
        assertEquals(1, models.size)
        assertEquals("qwen3:8b", models[0].name)
        assertEquals("8B", models[0].details?.parameterSize)
        assertEquals("Q4_K_M", models[0].details?.quantizationLevel)
    }

    @Test
    fun `webSearch posts to the search endpoint`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"results":[{"title":"T","url":"U","content":"C"}]}""")
                .build()
        )
        val response = client.webSearch("test query", 3)
        assertEquals(1, response.results.size)
        assertEquals("T", response.results[0].title)

        val request = server.takeRequest()
        assertEquals("/api/web_search", request.url.encodedPath)
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"query\":\"test query\""))
        assertTrue(body.contains("\"max_results\":3"))
    }

    @Test
    fun `encodeRequest serializes the wire shape`() {
        val encoded = client.encodeRequest(request())
        assertTrue(encoded.contains("\"model\":\"qwen3\""))
        assertTrue(encoded.contains("\"role\":\"user\""))
        assertTrue(encoded.contains("\"content\":\"hi\""))
        // Default-valued fields are omitted (encodeDefaults=false), so stream is absent.
        assertFalse(encoded.contains("\"stream\""))
    }

    @Test
    fun `encodeRequest includes non-default stream value`() {
        val encoded = client.encodeRequest(request().copy(stream = false))
        assertTrue(encoded.contains("\"stream\":false"))
    }
}
