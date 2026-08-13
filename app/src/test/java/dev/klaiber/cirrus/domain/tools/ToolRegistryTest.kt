package dev.klaiber.cirrus.domain.tools

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.klaiber.cirrus.data.mcp.McpClient
import dev.klaiber.cirrus.data.mcp.SseMcpTransport
import dev.klaiber.cirrus.data.mcp.StreamableHttpMcpTransport
import dev.klaiber.cirrus.data.prefs.SecretCipher
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsCredentials
import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
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
import dev.klaiber.cirrus.testing.InMemoryMemoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * `runBlocking` rather than `runTest`: the repository under test writes through a real DataStore on
 * its own dispatcher, and the barriers below wait on wall-clock time. `runTest`'s virtual clock does
 * not advance while another thread is doing the work we are waiting for.
 */
class ToolRegistryTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var settings: SettingsRepository
    private lateinit var gitHubCredentials: GitHubCredentials
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(
                System.getProperty("java.io.tmpdir"),
                "cirrus-registry-${System.nanoTime()}.preferences_pb",
            )
        }
        gitHubCredentials = GitHubCredentials()
        settings = SettingsRepository(
            dataStore = dataStore,
            secretCipher = SecretCipher(),
            credentials = ApiCredentials(),
            gitHubCredentials = gitHubCredentials,
            elevenLabsCredentials = ElevenLabsCredentials(),
            json = json,
            scope = scope,
        )

        val http = OkHttpClient()
        val ollama = OllamaClient(http, json, ApiCredentials())
        val gitHub = GitHubClient(http, json, gitHubCredentials)
        val mcp = McpClient(StreamableHttpMcpTransport(http), SseMcpTransport(http, json), json)
        val memories = MemoryRepository(InMemoryMemoryDao())

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
            mcpTools = McpToolSet(
                repository = McpServerRepository(
                    dataStore = dataStore,
                    secretCipher = SecretCipher(),
                    client = mcp,
                    json = json,
                    scope = scope,
                ),
                client = mcp,
            ),
            memoryTools = MemoryToolSet(
                RememberTool(memories),
                RecallTool(memories),
                ForgetTool(memories),
            ),
            notificationTool = SendNotificationTool(SilentNotifier()),
            settingsRepository = settings,
            gitHubCredentials = gitHubCredentials,
        )
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
        setMemoryEnabled(true)

        assertTrue(offeredNames(externalTools = false).contains("remember"))
        assertNotNull(registry.find("remember", externalTools = false))
    }

    @Test
    fun `memory tools disappear entirely when memory is switched off`() = runBlocking {
        setMemoryEnabled(false)

        assertFalse(offeredNames(externalTools = true).contains("remember"))
        assertNull(registry.find("remember", externalTools = true))
    }

    @Test
    fun `the notification tool follows its own setting`() = runBlocking {
        settings.setNotificationToolEnabled(false)
        await("notifications off") { !settings.current.value.notificationToolEnabled }

        assertFalse(offeredNames(externalTools = true).contains("send_notification"))
        assertNull(registry.find("send_notification", externalTools = true))
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
        setMemoryEnabled(true)

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

    private suspend fun setMemoryEnabled(enabled: Boolean) {
        settings.setMemoryEnabled(enabled)
        await("memory enabled=$enabled") { settings.current.value.memoryEnabled == enabled }
    }

    /**
     * Puts the two GitHub gates into a known state.
     *
     * The token is planted straight onto the credential snapshot rather than written through
     * settings, because `SecretCipher` needs the Android Keystore: in a JVM test `encrypt` returns
     * null and `setGitHubToken` therefore stores nothing at all. Planting it is exactly what the
     * collector in `SettingsRepository` does with a decrypted value, and the token is the only part
     * of that snapshot the registry reads.
     *
     * The flags go through the real DataStore first, and both barriers below have to pass before
     * the token is planted — otherwise a late emission of that same collector would overwrite it.
     */
    private suspend fun configureGitHub(
        token: String?,
        toolsEnabled: Boolean,
        writesAllowed: Boolean,
    ) {
        settings.setGitHubToolsEnabled(toolsEnabled)
        settings.setGitHubWritesAllowed(writesAllowed)
        val derivedWrites = toolsEnabled && writesAllowed
        await("gitHubToolsEnabled=$toolsEnabled") {
            settings.current.value.gitHubToolsEnabled == toolsEnabled
        }
        await("credential mirror caught up") {
            gitHubCredentials.writesAllowed == derivedWrites
        }
        gitHubCredentials.update(token = token, writesAllowed = derivedWrites)
    }

    /** Waits on wall-clock time for a value that another dispatcher is responsible for. */
    private fun await(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(5)
        }
        fail("timed out waiting for: $what")
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}

/** There is no notification manager in a JVM test, and nothing here asserts on one. */
private class SilentNotifier : Notifier {
    override fun notify(
        title: String,
        body: String,
        channel: Notifier.Channel,
        conversationId: String?,
    ): Boolean = true
}
