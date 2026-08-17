package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.mcp.McpClient
import dev.klaiber.cirrus.data.mcp.SseMcpTransport
import dev.klaiber.cirrus.data.mcp.StreamableHttpMcpTransport
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials
import dev.klaiber.cirrus.data.repository.JsonStore
import dev.klaiber.cirrus.data.repository.McpServerRepository
import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.notify.Notifier
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Which tools are offered, and which may actually run.
 *
 * These have to be the same question. `definitions` decides what the model is shown; `find` decides
 * what happens when it names something anyway — carried over from an earlier turn of the same
 * thread, or simply guessed. A gate applied to only one of the two is not a gate, so every case
 * here asserts both halves together.
 *
 * The Android version of this test waits on wall-clock barriers after every write, because its
 * settings go through a DataStore on another dispatcher. The desktop store publishes to its
 * `StateFlow` before it suspends to write the file, so a setter has taken effect by the time it
 * returns and there is nothing to wait for.
 */
class ToolRegistryTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var file: File
    private lateinit var settings: SettingsRepository
    private lateinit var gitHubCredentials: GitHubCredentials
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        file = File.createTempFile("cirrus-registry-", ".json").also { it.delete() }
        gitHubCredentials = GitHubCredentials()
        settings = SettingsRepository(
            store = JsonStore(file, json),
            credentials = ApiCredentials(),
            gitHubCredentials = gitHubCredentials,
            spotifyCredentials = SpotifyCredentials(),
        )

        val http = OkHttpClient()
        val ollama = OllamaClient(http, json, ApiCredentials())
        val gitHub = GitHubClient(http, json, gitHubCredentials)
        val memories = MemoryRepository(
            JsonStore(File(file.absolutePath + ".memories"), json),
        )
        val mcpClient = McpClient(
            StreamableHttpMcpTransport(http),
            SseMcpTransport(http, json),
            json,
        )
        val mcpServerRepository = McpServerRepository(
            store = JsonStore(File(file.absolutePath + ".mcp"), json),
            client = mcpClient,
            json = json,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        registry = ToolRegistry(
            webSearchTool = WebSearchTool(ollama, settings),
            webFetchTool = WebFetchTool(ollama),
            gitHubTools = GitHubToolSet(
                listRepos = ListReposTool(gitHub),
                searchCode = SearchCodeTool(gitHub),
                readFile = ReadFileTool(gitHub),
                listDirectory = ListDirectoryTool(gitHub),
                listIssues = ListIssuesTool(gitHub),
                getIssue = GetIssueTool(gitHub),
                listPulls = ListPullRequestsTool(gitHub),
                getPull = GetPullRequestTool(gitHub),
                createIssue = CreateIssueTool(gitHub),
                comment = CommentTool(gitHub),
                reviewPull = ReviewPullRequestTool(gitHub),
                writeFile = WriteFileTool(gitHub),
            ),
            memoryTools = MemoryToolSet(
                RememberTool(memories),
                RecallTool(memories),
                ForgetTool(memories),
            ),
            notificationTool = SendNotificationTool(SilentNotifier()),
            // Stand-ins: what is under test here is the gate rather than what is behind it. Two
            // entries, because the two lists answer to two different switches and both halves of
            // each gate have to be exercised.
            deviceTools = DeviceToolSet(
                shell = listOf(StubTool("run_command")),
                apps = listOf(StubTool("open_app")),
            ),
            // Stand-ins again: one read and one write, so both halves of the Spotify gate are
            // exercised without an account behind them.
            spotifyTools = SpotifyToolSet(
                all = listOf(StubTool("spotify_search"), StubTool("spotify_edit", writes = true)),
            ),
            settingsTool = DescribeSettingsTool(settings),
            // No server is attached, so this contributes nothing to the offered definitions.
            mcpTools = McpToolSet(repository = mcpServerRepository, client = mcpClient),
            settingsRepository = settings,
            gitHubCredentials = gitHubCredentials,
            spotifyCredentials = SpotifyCredentials(),
        )
    }

    @After
    fun tearDown() {
        file.delete()
        File(file.absolutePath + ".memories").delete()
        File(file.absolutePath + ".mcp").delete()
    }

    // ---- The external-tools switch --------------------------------------------------------

    @Test
    fun `web tools are offered and runnable with the switch on`() = runBlocking {
        assertTrue(offeredNames(externalTools = true).contains("web_search"))
        assertNotNull(registry.find("web_search", externalTools = true))
    }

    @Test
    fun `web tools are neither offered nor runnable with the switch off`() = runBlocking {
        assertFalse(offeredNames(externalTools = false).contains("web_search"))
        assertNull(
            "a model naming web_search with external tools off must be told it is unknown",
            registry.find("web_search", externalTools = false),
        )
    }

    // ---- Memory and notifications sit outside that switch ---------------------------------

    @Test
    fun `memory tools are offered even with external tools off`() = runBlocking {
        settings.setMemoryEnabled(true)

        assertTrue(offeredNames(externalTools = false).contains("remember"))
        assertNotNull(registry.find("remember", externalTools = false))
    }

    @Test
    fun `memory tools disappear entirely when memory is switched off`() = runBlocking {
        settings.setMemoryEnabled(false)

        assertFalse(offeredNames(externalTools = true).contains("remember"))
        assertNull(registry.find("remember", externalTools = true))
    }

    @Test
    fun `the notification tool follows its own setting`() = runBlocking {
        settings.setNotificationToolEnabled(false)

        assertFalse(offeredNames(externalTools = true).contains("send_notification"))
        assertNull(registry.find("send_notification", externalTools = true))
    }

    // ---- The device tools sit outside that switch too ---------------------------------------

    /**
     * The same argument memory makes: the switch governs what leaves the machine, and the clock
     * does not. A model that cannot ask what today's date is answers from the year it was trained
     * in.
     */
    @Test
    fun `shell tools are offered even with external tools off`() = runBlocking {
        assertTrue(offeredNames(externalTools = false).contains("run_command"))
        assertNotNull(registry.find("run_command", externalTools = false))
    }

    @Test
    fun `shell tools disappear entirely when the setting is off`() = runBlocking {
        settings.setShellToolsEnabled(false)

        assertFalse(offeredNames(externalTools = true).contains("run_command"))
        assertNull(registry.find("run_command", externalTools = true))
    }

    /** The one local tool that acts rather than answers, so it starts off. */
    @Test
    fun `app tools are absent by default and appear only when switched on`() = runBlocking {
        assertFalse(offeredNames(externalTools = true).contains("open_app"))
        assertNull(registry.find("open_app", externalTools = true))

        settings.setAppControlEnabled(true)

        assertTrue(offeredNames(externalTools = false).contains("open_app"))
        assertNotNull(registry.find("open_app", externalTools = false))
    }

    // ---- The GitHub gates ------------------------------------------------------------------

    @Test
    fun `github tools are absent without a token`() = runBlocking {
        configureGitHub(token = null, toolsEnabled = true, writesAllowed = true)

        assertTrue(offeredNames(externalTools = true).none { it.startsWith("github_") })
        assertNull(registry.find("github_list_repos", externalTools = true))
    }

    /**
     * The case the offered/runnable split exists for.
     *
     * A token that is still stored while the feature is switched off is an ordinary state — you add
     * a token, use it, then turn GitHub off. Nothing is offered, correctly. But the model has seen
     * these names in earlier turns of the same thread, and naming one must not reach GitHub with
     * the user's personal access token attached.
     */
    @Test
    fun `a github tool cannot run while the feature is switched off`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = false, writesAllowed = false)

        assertTrue(
            "nothing should be offered while GitHub is off",
            offeredNames(externalTools = true).none { it.startsWith("github_") },
        )
        assertNull(
            "a GitHub tool that was never offered must not resolve",
            registry.find("github_list_repos", externalTools = true),
        )
    }

    @Test
    fun `write tools are withheld unless writes are allowed`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = true, writesAllowed = false)

        val offered = offeredNames(externalTools = true)
        assertTrue("reads should still be offered", offered.contains("github_list_repos"))
        assertFalse("writes must not be offered", offered.contains("github_create_issue"))
        assertNull(
            "a write tool that was never offered must not resolve",
            registry.find("github_create_issue", externalTools = true),
        )
    }

    @Test
    fun `write tools appear once writes are allowed`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = true, writesAllowed = true)

        assertTrue(offeredNames(externalTools = true).contains("github_create_issue"))
        assertNotNull(registry.find("github_create_issue", externalTools = true))
    }

    @Test
    fun `github tools never run when external tools are off, token or not`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = true, writesAllowed = true)

        assertNull(registry.find("github_list_repos", externalTools = false))
        assertNull(registry.find("github_create_issue", externalTools = false))
    }

    // ---- Names ------------------------------------------------------------------------------

    @Test
    fun `an unknown name resolves to nothing rather than throwing`() = runBlocking {
        assertNull(registry.find("definitely_not_a_tool", externalTools = true))
    }

    /**
     * The invariant that ties the two halves together, over whatever the registry happens to offer.
     *
     * Anything the model is shown must be resolvable, or the turn ends in "Unknown tool" for a tool
     * we ourselves advertised a moment earlier.
     */
    @Test
    fun `every offered definition carries a name the registry can resolve`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = true, writesAllowed = true)
        settings.setMemoryEnabled(true)

        val offered = offeredNames(externalTools = true)
        assertTrue("expected a non-trivial set of tools", offered.size > 5)
        offered.forEach { name ->
            assertNotNull(
                "$name was offered to the model but cannot be resolved",
                registry.find(name, externalTools = true),
            )
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun offeredNames(externalTools: Boolean): List<String> =
        registry.definitions(externalTools = externalTools).map { definition ->
            definition.jsonObject.getValue("function").jsonObject
                .getValue("name").jsonPrimitive.content
        }

    /**
     * Puts the two GitHub gates into a known state.
     *
     * The token goes through the repository rather than being planted on the credential holder:
     * the desktop build has no Keystore between the two, so `setGitHubToken` really does store it
     * and really does mirror it, which is the path the app takes.
     */
    private suspend fun configureGitHub(
        token: String?,
        toolsEnabled: Boolean,
        writesAllowed: Boolean,
    ) {
        settings.setGitHubToken(token.orEmpty())
        settings.setGitHubToolsEnabled(toolsEnabled)
        settings.setWriteToolsAllowed(writesAllowed)
    }
}

/** A name and a schema, which is all the registry's gates ever look at. */
private class StubTool(
    override val name: String,
    override val writes: Boolean = false,
) : CirrusTool {
    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", "stub")
        }
    }

    override suspend fun execute(arguments: JsonObject): String = "{}"
}

/** There is no tray in a JVM test, and nothing here asserts on one. */
private class SilentNotifier : Notifier {

    override val isAvailable: Boolean = true
    override fun notify(
        title: String,
        body: String,
        channel: Notifier.Channel,
        conversationId: String?,
    ): Boolean = true
}
