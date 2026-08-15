package dev.klaiber.cirrus.domain

import app.cash.turbine.test
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.klaiber.cirrus.data.prefs.SecretCipher
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.OllamaException
import dev.klaiber.cirrus.data.mcp.McpClient
import dev.klaiber.cirrus.data.mcp.SseMcpTransport
import dev.klaiber.cirrus.data.mcp.StreamableHttpMcpTransport
import dev.klaiber.cirrus.data.repository.McpServerRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.data.local.dao.MemoryDao
import dev.klaiber.cirrus.data.local.entity.MemoryEntity
import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.domain.notify.Notifier
import dev.klaiber.cirrus.domain.tools.ForgetTool
import dev.klaiber.cirrus.domain.tools.MemoryToolSet
import dev.klaiber.cirrus.domain.tools.RecallTool
import dev.klaiber.cirrus.domain.tools.RememberTool
import dev.klaiber.cirrus.domain.tools.SendNotificationTool
import dev.klaiber.cirrus.domain.tools.ShellToolSet
import dev.klaiber.cirrus.domain.tools.ToolRegistry
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsCredentials
import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.domain.tools.GitHubToolSet
import dev.klaiber.cirrus.domain.tools.McpToolSet
import dev.klaiber.cirrus.domain.tools.github.CommentTool
import dev.klaiber.cirrus.domain.tools.github.CreateIssueTool
import dev.klaiber.cirrus.domain.tools.github.GetIssueTool
import dev.klaiber.cirrus.domain.tools.github.GetPullRequestTool
import dev.klaiber.cirrus.domain.tools.github.ListDirectoryTool
import dev.klaiber.cirrus.domain.tools.github.ListIssuesTool
import dev.klaiber.cirrus.domain.tools.github.ListPullRequestsTool
import dev.klaiber.cirrus.domain.tools.github.ListReposTool
import dev.klaiber.cirrus.domain.tools.github.ReadFileTool
import dev.klaiber.cirrus.domain.tools.github.ReviewPullRequestTool
import dev.klaiber.cirrus.domain.tools.github.SearchCodeTool
import dev.klaiber.cirrus.domain.tools.github.WriteFileTool
import dev.klaiber.cirrus.domain.tools.WebFetchTool
import dev.klaiber.cirrus.domain.tools.WebSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val gitHubCredentials = GitHubCredentials()
        val memoryRepository = MemoryRepository(EmptyMemoryDao())
        val settingsRepository = SettingsRepository(
            dataStore = dataStore,
            secretCipher = SecretCipher(),
            credentials = ApiCredentials(),
            gitHubCredentials = gitHubCredentials,
            elevenLabsCredentials = ElevenLabsCredentials(),
            json = json,
            scope = scope,
        )
        // No GitHub token is configured, so the registry offers only the web tools here.
        val gitHubClient = GitHubClient(OkHttpClient(), json, gitHubCredentials)
        val http = OkHttpClient()
        val mcpClient = McpClient(
            StreamableHttpMcpTransport(http),
            SseMcpTransport(http, json),
            json,
        )
        return ToolRegistry(
            webSearchTool = WebSearchTool(client, settingsRepository),
            webFetchTool = WebFetchTool(client),
            gitHubTools = GitHubToolSet(
                listRepos = ListReposTool(gitHubClient),
                searchCode = SearchCodeTool(gitHubClient),
                readFile = ReadFileTool(gitHubClient),
                listDirectory = ListDirectoryTool(gitHubClient),
                listIssues = ListIssuesTool(gitHubClient),
                getIssue = GetIssueTool(gitHubClient),
                listPulls = ListPullRequestsTool(gitHubClient),
                getPull = GetPullRequestTool(gitHubClient),
                createIssue = CreateIssueTool(gitHubClient),
                comment = CommentTool(gitHubClient),
                reviewPull = ReviewPullRequestTool(gitHubClient),
                writeFile = WriteFileTool(gitHubClient),
            ),
            // No MCP server is attached, so this contributes nothing to the offered definitions.
            mcpTools = McpToolSet(
                repository = McpServerRepository(
                    dataStore = dataStore,
                    secretCipher = SecretCipher(),
                    client = mcpClient,
                    json = json,
                    scope = scope,
                ),
                client = mcpClient,
            ),
            memoryTools = MemoryToolSet(
                RememberTool(memoryRepository),
                RecallTool(memoryRepository),
                ForgetTool(memoryRepository),
            ),
            notificationTool = SendNotificationTool(RecordingNotifier()),
            // The device tools need a Context, so none is offered here. The turn protocol does not
            // care which tools exist, only that the loop services whatever the model asks for.
            shellTools = ShellToolSet(device = emptyList(), apps = emptyList()),
            settingsRepository = settingsRepository,
            gitHubCredentials = gitHubCredentials,
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

    /** One streamed reply whose whole content is [content], escaped as the wire would carry it. */
    private fun titleResponse(content: String) = MockResponse.Builder()
        .body(
            """
            {"model":"qwen3","message":{"role":"assistant","content":${JsonPrimitive(content)}},"done":false}
            {"model":"qwen3","done":true,"done_reason":"stop"}
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
        // Asserted on the messages rather than the whole body: tool descriptions are in there too,
        // and one of them legitimately contains the word "one".
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(""""content":"two""""))
        assertTrue(body.contains(""""content":"three""""))
        assertFalse(body.contains(""""content":"one""""))
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

    /**
     * The turn used to end the moment the budget ran out, on whatever the model had said before
     * reaching for a tool — which reads like the model choosing to stop mid-task. It now gets one
     * more pass, without tools, to answer with what it has.
     */
    @Test
    fun `answers instead of stopping when the tool budget is spent`() = runTest {
        server.enqueue(toolCallResponse()) // Round one: asks for a tool, and gets it.
        server.enqueue(searchResponse())
        server.enqueue(toolCallResponse()) // Budget spent, but the model asks again anyway.
        server.enqueue(simpleResponse()) // The wrap-up round, where it finally answers.

        val events = engine.respond(
            conversation(toolsEnabled = true),
            listOf(userMessage("hi")),
            AppSettings(maxToolIterations = 1),
        ).toList()

        val ran = events.filterIsInstance<TurnEvent.ToolFinished>()
        assertEquals(2, ran.size)
        assertTrue(ran.first().invocation.resultJson!!.contains("https://example.com"))
        assertTrue(ran.last().invocation.errorMessage!!.contains("Tool budget spent"))

        // The turn's last word is an answer, not an unanswered call.
        val answer = events.filterIsInstance<TurnEvent.ContentDelta>().joinToString("") { it.text }
        assertEquals("ok", answer)
        assertTrue(events.last() is TurnEvent.Finished)
        assertEquals(4, server.requestCount)

        server.takeRequest() // The first chat request, which did offer tools.
        server.takeRequest() // The tool's own request.
        // Nothing is gained by offering tools that can no longer run, and a model that sees them
        // will keep calling them.
        assertFalse(server.takeRequest().body!!.utf8().contains("\"tools\""))
        assertTrue(server.takeRequest().body!!.utf8().contains("Tool budget spent"))
    }

    @Test
    fun `stops asking after one wrap-up round`() = runTest {
        // A model that ignores the missing tool list and keeps calling must not loop forever.
        server.enqueue(toolCallResponse())
        server.enqueue(searchResponse())
        server.enqueue(toolCallResponse())
        server.enqueue(toolCallResponse())

        val events = engine.respond(
            conversation(toolsEnabled = true),
            listOf(userMessage("hi")),
            AppSettings(maxToolIterations = 1),
        ).toList()

        assertTrue(events.last() is TurnEvent.Finished)
        // Chat, web_search, chat, chat — and then it gives up rather than asking a fourth time.
        assertEquals(4, server.requestCount)
    }

    // ---- Interrupted streams -----------------------------------------------------------------

    /** A stream that ends without its terminal chunk was cut short, not finished. */
    @Test
    fun `surfaces a stream cut short after content as an error`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"model":"qwen3","message":{"role":"assistant","content":"Half an ans"},"done":false}""")
                .build()
        )

        engine.respond(conversation(), listOf(userMessage("Hi")), AppSettings()).test {
            assertTrue(awaitItem() is TurnEvent.RequestPrepared)
            assertEquals("Half an ans", (awaitItem() as TurnEvent.ContentDelta).text)
            assertTrue(awaitError() is OllamaException.Truncated)
        }
    }

    /** Nothing reached the screen, so re-issuing the round cannot duplicate anything. */
    @Test
    fun `retries a round that died before producing anything`() = runTest {
        server.enqueue(MockResponse.Builder().body("").build())
        server.enqueue(simpleResponse())

        val events = engine.respond(
            conversation(),
            listOf(userMessage("Hi")),
            AppSettings(),
        ).toList()

        assertEquals("ok", events.filterIsInstance<TurnEvent.ContentDelta>().single().text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `does not execute an external tool when the conversation has them switched off`() = runTest {
        // The switch governs what may reach off the phone. A model that asks for web_search anyway
        // — from an earlier turn, or by guessing — has to be told the tool does not exist, or the
        // switch is decoration.
        server.enqueue(toolCallResponse())
        server.enqueue(simpleResponse())

        val events = engine.respond(
            conversation(toolsEnabled = false),
            listOf(userMessage("hi")),
            AppSettings(),
        ).toList()

        val finished = events.filterIsInstance<TurnEvent.ToolFinished>()
        assertTrue(finished.all { it.invocation.errorMessage?.contains("Unknown tool") == true })
        assertTrue(events.filterIsInstance<TurnEvent.Finished>().isNotEmpty())
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
        val title = engine.suggestTitle(
            model = "qwen3",
            transcript = "User: What is quantum computing?\n\nAssistant: It is a field.",
        )
        assertEquals("Quantum Computing", title)
    }

    @Test
    fun `omits think for a model that cannot think`() = runTest {
        server.enqueue(titleResponse("Centering a div"))
        engine.suggestTitle(model = "llama3.2", transcript = "User: hi", supportsThinking = false)

        val body = server.takeRequest().body!!.utf8()
        assertFalse(body.contains("\"think\""))
        assertTrue(body.contains("\"num_predict\":24"))
    }

    /**
     * Ollama enables thinking by default on a model that supports it, so the flag has to be sent
     * explicitly — and the budget has to survive a model that reasons anyway.
     */
    @Test
    fun `disables thinking and widens the budget for a thinking model`() = runTest {
        server.enqueue(titleResponse("Centering a div"))
        engine.suggestTitle(model = "qwen3", transcript = "User: hi", supportsThinking = true)

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"think\":false"))
        assertTrue(body.contains("\"num_predict\":320"))
    }

    @Test
    fun `ignores reasoning a model leaves in the content`() = runTest {
        val reasoned = "<think>\nThe user asks about Room.\n</think>\n\nRoom schema migrations"
        server.enqueue(titleResponse(reasoned))

        val title = engine.suggestTitle("qwen3", "User: hi", supportsThinking = true)
        assertEquals("Room schema migrations", title)
    }

    @Test
    fun `returns null when the budget was spent entirely on reasoning`() = runTest {
        server.enqueue(titleResponse("<think>\nOkay, the user wants a title for"))
        assertNull(engine.suggestTitle("qwen3", "User: hi", supportsThinking = true))
    }

    @Test
    fun `strips quoting and labels a model wraps the title in`() {
        assertEquals("Quantum computing", engine.extractTitle("**Title:** Quantum computing."))
        assertEquals("Quantum computing", engine.extractTitle("\"Quantum computing\""))
        assertEquals("Quantum computing", engine.extractTitle("- Quantum computing"))
        assertEquals("Quantum computing", engine.extractTitle("## Quantum computing"))
        assertNull(engine.extractTitle("   \n\n  "))
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

/**
 * A memory store with nothing in it.
 *
 * The tool loop under test never touches memory; this exists so the registry can be built without
 * dragging Room into a JVM test.
 */
private class EmptyMemoryDao : MemoryDao {
    override fun observeActive() = kotlinx.coroutines.flow.flowOf(emptyList<MemoryEntity>())
    override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<MemoryEntity>())
    override fun observeActiveCount() = kotlinx.coroutines.flow.flowOf(0)
    override suspend fun activeMemories(): List<MemoryEntity> = emptyList()
    override suspend fun pinned(): List<MemoryEntity> = emptyList()
    override suspend fun byId(id: String): MemoryEntity? = null
    override suspend fun upsert(memory: MemoryEntity) = Unit
    override suspend fun update(memory: MemoryEntity) = Unit
    override suspend fun markRecalled(ids: List<String>, at: Long) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun deleteAll() = Unit
}

/** Records rather than posts, since there is no notification manager in a JVM test. */
private class RecordingNotifier : Notifier {
    val posted = mutableListOf<Pair<String, String>>()

    override fun notify(
        title: String,
        body: String,
        channel: Notifier.Channel,
        conversationId: String?,
    ): Boolean {
        posted += title to body
        return true
    }
}
