package dev.klaiber.cirrus.di

import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.ModelCapabilityDetector
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.JsonStore
import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.ChatEngine
import dev.klaiber.cirrus.domain.ConversationTitler
import dev.klaiber.cirrus.domain.TurnController
import dev.klaiber.cirrus.domain.notify.DesktopNotifier
import dev.klaiber.cirrus.domain.tools.CirrusTool
import dev.klaiber.cirrus.domain.tools.DescribeSettingsTool
import dev.klaiber.cirrus.domain.tools.DeviceToolSet
import dev.klaiber.cirrus.domain.tools.ForgetTool
import dev.klaiber.cirrus.domain.tools.GitHubToolSet
import dev.klaiber.cirrus.domain.tools.MemoryToolSet
import dev.klaiber.cirrus.domain.tools.RecallTool
import dev.klaiber.cirrus.domain.tools.RememberTool
import dev.klaiber.cirrus.domain.tools.SendNotificationTool
import dev.klaiber.cirrus.domain.tools.ToolRegistry
import dev.klaiber.cirrus.domain.tools.WebFetchTool
import dev.klaiber.cirrus.domain.tools.WebSearchTool
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
import dev.klaiber.cirrus.domain.tools.shell.CalendarTool
import dev.klaiber.cirrus.domain.tools.shell.CleanWorkspaceTool
import dev.klaiber.cirrus.domain.tools.shell.DateTimeTool
import dev.klaiber.cirrus.domain.tools.shell.ListAppsTool
import dev.klaiber.cirrus.domain.tools.shell.OpenAppTool
import dev.klaiber.cirrus.domain.tools.shell.RunCommandTool
import dev.klaiber.cirrus.domain.tools.shell.ShellRunner
import dev.klaiber.cirrus.domain.tools.shell.ShellWorkspace
import dev.klaiber.cirrus.domain.tools.shell.SystemInfoTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The desktop build's dependency graph, wired by hand.
 *
 * There is no Hilt on the JVM, and there does not need to be: the graph is small and fixed, and
 * an explicit list is easier to audit than an injected set when the question is "which of these
 * can do something?". Everything is a `val` so the wiring order is the only thing that matters.
 */
class AppContainer(
    private val dataDir: File,
    private val scope: CoroutineScope,
) {

    // ---- JSON --------------------------------------------------------------------------------

    /** The wire protocol: unknown keys ignored, defaults omitted, nulls dropped. */
    val wireJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** Persistence: defaults written so a fresh file is complete, and pretty-printed to read. */
    private val persistenceJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ---- Credentials -------------------------------------------------------------------------

    val apiCredentials = ApiCredentials()
    val gitHubCredentials = GitHubCredentials()

    // ---- HTTP --------------------------------------------------------------------------------

    private val ollamaHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("User-Agent", "Cirrus/1.0.0 (Desktop)")
            apiCredentials.apiKey?.let { key -> builder.header("Authorization", "Bearer $key") }
            chain.proceed(builder.build())
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // A long generation legitimately holds the socket open with sparse writes, so the read
        // and overall call timeouts are disabled and cancellation is driven by the UI.
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gitHubHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("User-Agent", "Cirrus/1.0.0 (Desktop)")
            gitHubCredentials.token?.let { token -> builder.header("Authorization", "Bearer $token") }
            chain.proceed(builder.build())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ---- Data layer --------------------------------------------------------------------------

    val ollamaClient = OllamaClient(ollamaHttp, wireJson, apiCredentials)
    val gitHubClient = GitHubClient(gitHubHttp, wireJson, gitHubCredentials)
    private val capabilityDetector = ModelCapabilityDetector(wireJson)

    // ---- Repositories ------------------------------------------------------------------------

    val settingsRepository = SettingsRepository(
        store = JsonStore(File(dataDir, "settings.json"), persistenceJson),
        credentials = apiCredentials,
        gitHubCredentials = gitHubCredentials,
    )

    val conversationRepository = ConversationRepository(
        store = JsonStore(File(dataDir, "conversations.json"), persistenceJson),
    )

    val memoryRepository = MemoryRepository(
        store = JsonStore(File(dataDir, "memories.json"), persistenceJson),
    )

    val modelRepository = ModelRepository(ollamaClient, capabilityDetector, scope)

    // ---- Tools -------------------------------------------------------------------------------

    val notifier = DesktopNotifier()

    val shellWorkspace = ShellWorkspace(File(dataDir, "workspace"))
    private val shellRunner = ShellRunner(shellWorkspace)

    private val webSearchTool = WebSearchTool(ollamaClient, settingsRepository)
    private val webFetchTool = WebFetchTool(ollamaClient)

    private val gitHubToolSet = GitHubToolSet(
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
    )

    private val memoryToolSet = MemoryToolSet(
        remember = RememberTool(memoryRepository),
        recall = RecallTool(memoryRepository),
        forget = ForgetTool(memoryRepository),
    )

    private val notificationTool = SendNotificationTool(notifier)

    private val deviceToolSet = DeviceToolSet(
        shell = listOf(
            RunCommandTool(shellRunner, shellWorkspace),
            CleanWorkspaceTool(shellWorkspace),
            DateTimeTool(),
            CalendarTool(),
            SystemInfoTool(shellWorkspace),
        ),
        apps = listOf(
            ListAppsTool(),
            OpenAppTool(),
        ),
    )

    private val settingsTool = DescribeSettingsTool(settingsRepository)

    val toolRegistry = ToolRegistry(
        webSearchTool = webSearchTool,
        webFetchTool = webFetchTool,
        gitHubTools = gitHubToolSet,
        memoryTools = memoryToolSet,
        notificationTool = notificationTool,
        deviceTools = deviceToolSet,
        settingsTool = settingsTool,
        settingsRepository = settingsRepository,
        gitHubCredentials = gitHubCredentials,
    )

    // ---- Domain ------------------------------------------------------------------------------

    val chatEngine = ChatEngine(ollamaClient, toolRegistry, wireJson)

    val conversationTitler = ConversationTitler(
        conversations = conversationRepository,
        settings = settingsRepository,
        models = modelRepository,
        engine = chatEngine,
        scope = scope,
    )

    val turnController = TurnController(
        conversations = conversationRepository,
        settings = settingsRepository,
        engine = chatEngine,
        titler = conversationTitler,
        memories = memoryRepository,
        scope = scope,
    )

    /** Loads the persisted stores and clears the scratch workspace from the last session. */
    suspend fun start() {
        settingsRepository.load()
        conversationRepository.load()
        memoryRepository.load()
        shellWorkspace.clear()
    }
}
