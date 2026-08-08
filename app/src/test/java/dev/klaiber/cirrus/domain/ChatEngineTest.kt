package dev.klaiber.cirrus.domain

import app.cash.turbine.test
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.klaiber.cirrus.data.prefs.SecretCipher
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.OllamaException
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.tools.ToolRegistry
import dev.klaiber.cirrus.domain.tools.WebFetchTool
import dev.klaiber.cirrus.domain.tools.WebSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.io.File

class ChatEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: ChatEngine
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val credentials = ApiCredentials()
        credentials.update(apiKey = null, baseUrl = server.url("/").toString())
        val client = OllamaClient(OkHttpClient(), json, credentials)
        engine = ChatEngine(client, createToolRegistry(client), json)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun createToolRegistry(client: OllamaClient): ToolRegistry {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(System.getProperty("java.io.tmpdir"), "cirrus-settings-${System.nanoTime()}.preferences_pb")
        }
        val settingsRepository = SettingsRepository(
            dataStore = dataStore,
            secretCipher = SecretCipher(),
            credentials = ApiCredentials(),
            json = json,
            scope = scope,
        )
        return ToolRegistry(
            WebSearchTool(client, settingsRepository),
            WebFetchTool(client),
        )
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private fun conversation(
        toolsEnabled: Boolean = false,
        systemPrompt: String? = null,
        params: GenerationParams = GenerationParams.Default,
    ) = Conversation(
        id = "conv-1",
        title = "Test",
        model = "qwen3",
        systemPrompt = systemPrompt,
        params = params,
        toolsEnabled = toolsEnabled,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun userMessage(text: String) = ChatMessage(
        id = "msg-$text",
        conversationId = "conv-1",
        role = Role.USER,
        content = text,
        createdAt = 0L,
        sequence = 0,
    )

    private fun simpleResponse() = MockResponse.Builder()
        .body(
            """
            {"model":"qwen3","message":{"role":"assistant","content":"ok"},"done":false}
            {"model":"qwen3","done":true,"done_reason":"stop"}
            """.trimIndent()
        )
        .build()

    private fun toolCallResponse() = MockResponse.Builder()
        .body(
            """
            {"model":"qwen3","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"web_search","arguments":{"query":"test","max_results":3}}}]},"done":false}
            {"model":"qwen3","done":true,"done_reason":"tool_calls"}
            """.trimIndent()
        )
        .build()

    private fun searchResponse() = MockResponse.Builder()
        .body("""{"results":[{"title":"Result","url":"https://example.com","content":"Snippet"}]}""")
        .build()

    // ---- Streaming ------------------------------------------------------------------------

    @Test
    fun `streams content deltas and finishes with stats`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"model":"qwen3","message":{"role":"assistant","content":"Hello"},"done":false}
                    {"model":"qwen3","message":{"role":"assistant","content":" world"},"done":false}
                    {"model":"qwen3","done":true,"done_reason":"stop","eval_count":5,"eval_duration":500000000}
                    """.trimIndent()
                )
                .build()
        )

        engine.respond(conversation(), listOf(userMessage("Hi")), AppSettings()).test {
            assertTrue(awaitItem() is TurnEvent.RequestPrepared)
            assertEquals("Hello", (awaitItem() as TurnEvent.ContentDelta).text)
            assertEquals(" world", (awaitItem() as TurnEvent.ContentDelta).text)
            val finished = awaitItem() as TurnEvent.Finished
            assertEquals("stop", finished.stats.doneReason)
            assertEquals(5, finished.stats.evalCount)
            awaitComplete()
        }

        val request = server.takeRequest()
        assertEquals("/api/chat", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("\"model\":\"qwen3\""))
    }

    @Test
    fun `emits thinking deltas`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"model":"qwen3","message":{"role":"assistant","thinking":"Let me"},"done":false}
                    {"model":"qwen3","message":{"role":"assistant","thinking":" think"},"done":false}
                    {"model":"qwen3","message":{"role":"assistant","content":"Answer"},"done":false}
                    {"model":"qwen3","done":true,"done_reason":"stop"}
                    """.trimIndent()
                )
                .build()
        )

        engine.respond(conversation(), listOf(userMessage("Hi")), AppSettings()).test {
            assertTrue(awaitItem() is TurnEvent.RequestPrepared)
            assertEquals("Let me", (awaitItem() as TurnEvent.ThinkingDelta).text)
            assertEquals(" think", (awaitItem() as TurnEvent.ThinkingDelta).text)
            assertEquals("Answer", (awaitItem() as TurnEvent.ContentDelta).text)
            assertTrue(awaitItem() is TurnEvent.Finished)
            awaitComplete()
        }
    }

    @Test
    fun `injects system prompt into the request`() = runTest {
        server.enqueue(simpleResponse())
        engine.respond(
            conversation(systemPrompt = "You are helpful."),
            listOf(userMessage("Hi")),
            AppSettings(),
        ).toList()
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("You are helpful."))
    }

    @Test
    fun `limits context to the last N messages`() = runTest {
        server.enqueue(simpleResponse())
        val history = listOf(userMessage("one"), userMessage("two"), userMessage("three"))
        engine.respond(conversation(), history, AppSettings(contextMessageLimit = 2)).toList()
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("two"))
        assertTrue(body.contains("three"))
        assertFalse(body.contains("one"))
    }

    // ---- Tool loop ------------------------------------------------------------------------

    @Test
    fun `executes tool calls and feeds results back`() = runTest {
        server.enqueue(toolCallResponse())
        server.enqueue(searchResponse())
        server.enqueue(simpleResponse())

        val events = engine.respond(
            conversation(toolsEnabled = true),
            listOf(userMessage("Search for test")),
            AppSettings(),
        ).toList()

        val toolStarted = events.filterIsInstance<TurnEvent.ToolStarted>().single()
        assertEquals("web_search", toolStarted.invocation.name)
        val toolFinished = events.filterIsInstance<TurnEvent.ToolFinished>().single()
        assertTrue(toolFinished.invocation.isComplete)
        assertTrue(toolFinished.invocation.resultJson!!.contains("https://example.com"))
        val finished = events.filterIsInstance<TurnEvent.Finished>().single()
        assertEquals("stop", finished.stats.doneReason)

        // First chat request, then the tool's own request, then the follow-up chat request.
        assertEquals("/api/chat", server.takeRequest().url.encodedPath)
        assertEquals("/api/web_search", server.takeRequest().url.encodedPath)
        val followUp = server.takeRequest()
        assertEquals("/api/chat", followUp.url.encodedPath)
        val followUpBody = followUp.body!!.utf8()
        assertTrue(followUpBody.contains("\"tool_name\":\"web_search\""))
        assertTrue(followUpBody.contains("https://example.com"))
    }

    @Test
    fun `reports unknown tools as errors`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"model":"qwen3","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"nonexistent_tool","arguments":{}}}]},"done":false}
                    {"model":"qwen3","done":true,"done_reason":"tool_calls"}
                    """.trimIndent()
                )
                .build()
        )
        server.enqueue(simpleResponse())

        val events = engine.respond(
            conversation(toolsEnabled = true),
            listOf(userMessage("hi")),
            AppSettings(),
        ).toList()

        val toolFinished = events.filterIsInstance<TurnEvent.ToolFinished>().single()
        assertTrue(toolFinished.invocation.errorMessage!!.contains("Unknown tool"))
    }

    @Test
    fun `stops the tool loop at max iterations`() = runTest {
        // The model calls a tool twice; with maxToolIterations=1 the second call is not serviced.
        server.enqueue(toolCallResponse())
        server.enqueue(searchResponse())
        server.enqueue(toolCallResponse())

        val events = engine.respond(
            conversation(toolsEnabled = true),
            listOf(userMessage("hi")),
            AppSettings(maxToolIterations = 1),
        ).toList()

        assertEquals(1, events.filterIsInstance<TurnEvent.ToolStarted>().size)
        assertTrue(events.filterIsInstance<TurnEvent.Finished>().isNotEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `does not execute tools when disabled`() = runTest {
        server.enqueue(toolCallResponse())

        val events = engine.respond(
            conversation(toolsEnabled = false),
            listOf(userMessage("hi")),
            AppSettings(),
        ).toList()

        assertTrue(events.filterIsInstance<TurnEvent.ToolStarted>().isEmpty())
        assertTrue(events.filterIsInstance<TurnEvent.Finished>().isNotEmpty())
        assertEquals(1, server.requestCount)
    }

    // ---- Titles and errors -----------------------------------------------------------------

    @Test
    fun `suggests a title from the first response line`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {"model":"qwen3","message":{"role":"assistant","content":"\n\nQuantum Computing\n"},"done":false}
                    {"model":"qwen3","done":true,"done_reason":"stop"}
                    """.trimIndent()
                )
                .build()
        )
        val title = engine.suggestTitle("qwen3", "What is quantum computing?", "It is a field.")
        assertEquals("Quantum Computing", title)
    }

    @Test
    fun `surfaces http errors as ollama exceptions`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":"unauthorized"}""")
                .build()
        )
        engine.respond(conversation(), listOf(userMessage("Hi")), AppSettings()).test {
            assertTrue(awaitItem() is TurnEvent.RequestPrepared)
            assertTrue(awaitError() is OllamaException.Unauthorized)
        }
    }
}
