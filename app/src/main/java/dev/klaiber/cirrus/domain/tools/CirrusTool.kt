package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.tools.github.CommentTool
import dev.klaiber.cirrus.domain.tools.github.CreateIssueTool
import dev.klaiber.cirrus.domain.tools.github.GitHubTool
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A function the model may call during a turn.
 *
 * Implementations return a JSON string that is fed back as a `role: "tool"` message, so the
 * shape should stay compact — every byte competes with the conversation for context.
 */
interface CirrusTool {
    val name: String

    /** OpenAI-style function schema, sent in `ChatRequest.tools`. */
    val definition: JsonElement

    suspend fun execute(arguments: JsonObject): String
}

/** Grounds answers in current sources via Ollama's hosted search index. */
class WebSearchTool @Inject constructor(
    private val client: OllamaClient,
    private val settingsRepository: SettingsRepository,
) : CirrusTool {

    override val name: String = "web_search"

    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put(
                "description",
                "Search the web for current information. Use this for anything time-sensitive, " +
                    "for facts you are unsure about, or when the user asks about recent events.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "The search query.")
                    }
                    putJsonObject("max_results") {
                        put("type", "integer")
                        put("description", "How many results to return (1-10).")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): String {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return """{"error":"missing required argument: query"}"""
        val requested = arguments["max_results"]?.let {
            runCatching { it.jsonPrimitive.int }.getOrNull()
        }
        val max = (requested ?: settingsRepository.current.value.webSearchMaxResults).coerceIn(1, 10)

        val response = client.webSearch(query, max)
        return buildJsonObject {
            put("query", query)
            put(
                "results",
                JsonArray(
                    response.results.map { result ->
                        buildJsonObject {
                            put("title", result.title)
                            put("url", result.url)
                            put("content", result.content.take(MAX_SNIPPET_CHARS))
                        }
                    },
                ),
            )
        }.toString()
    }

    private companion object {
        // Long pages can blow the context window; the model can always fetch the full page.
        const val MAX_SNIPPET_CHARS = 4_000
    }
}

/** Retrieves and flattens a specific page, for following up on a search result. */
class WebFetchTool @Inject constructor(
    private val client: OllamaClient,
) : CirrusTool {

    override val name: String = "web_fetch"

    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put(
                "description",
                "Fetch the full text of a specific web page by URL. Use after web_search when a " +
                    "result looks relevant and you need more than the snippet.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Absolute URL of the page to fetch.")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("url")) })
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): String {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return """{"error":"missing required argument: url"}"""

        val response = client.webFetch(url)
        return buildJsonObject {
            put("url", url)
            put("title", response.title ?: "")
            put("content", (response.content ?: "").take(MAX_PAGE_CHARS))
        }.toString()
    }

    private companion object {
        const val MAX_PAGE_CHARS = 20_000
    }
}

/**
 * Maps tool names to implementations, and decides which are offered to the model.
 *
 * [definitions] is computed per turn rather than fixed at construction: which tools exist
 * depends on what the user has switched on, whether a GitHub token is present, and which MCP
 * servers are currently attached and reachable. Sending the schema for a tool that cannot run
 * wastes context and invites the model to call it and fail.
 */
@Singleton
class ToolRegistry @Inject constructor(
    webSearchTool: WebSearchTool,
    webFetchTool: WebFetchTool,
    private val gitHubTools: GitHubToolSet,
    private val memoryTools: MemoryToolSet,
    private val notificationTool: SendNotificationTool,
    private val shellTools: ShellToolSet,
    private val mcpTools: McpToolSet,
    private val settingsRepository: SettingsRepository,
    private val gitHubCredentials: GitHubCredentials,
) {
    private val webTools: List<CirrusTool> = listOf(webSearchTool, webFetchTool)

    /** Tools that exist for the life of the process; MCP tools come and go, so are resolved live. */
    private val staticTools: Map<String, CirrusTool> =
        (webTools + gitHubTools.all + memoryTools.all + shellTools.all + notificationTool)
            .associateBy { it.name }

    /**
     * What to offer the model this turn.
     *
     * [externalTools] is the conversation's own tools switch, and it only governs the tools that
     * reach outside the phone: search, GitHub, MCP. Memory and notifications are not on that
     * switch. They are local, free and instant, and the switch exists to control latency, cost and
     * what leaves the device — none of which they spend. Gating memory behind it would mean
     * cross-session memory silently not working in most conversations, which is indistinguishable
     * from it being broken.
     */
    fun definitions(externalTools: Boolean): List<JsonElement> = buildList {
        val settings = settingsRepository.current.value

        // Offered as a set: recall with nothing to find is a wasted round trip, and remember with
        // no way to correct it is worse than not remembering at all.
        if (settings.memoryEnabled) {
            addAll(memoryTools.all.map { it.definition })
        }
        if (settings.notificationToolEnabled) {
            add(notificationTool.definition)
        }
        // The shell, the clock and the calendar are on the same footing as memory: local, instant,
        // and nothing leaves the phone. A model that cannot ask what today's date is answers every
        // scheduling question from the year it was trained in, which is wrong in the way that looks
        // most convincing.
        if (settings.shellToolsEnabled) {
            addAll(shellTools.device.map { it.definition })
        }
        if (settings.appControlEnabled) {
            addAll(shellTools.apps.map { it.definition })
        }

        if (!externalTools) return@buildList

        addAll(webTools.map { it.definition })
        if (gitHubEnabled) {
            val writesAllowed = gitHubCredentials.writesAllowed
            gitHubTools.all
                .filter { writesAllowed || it !in gitHubTools.writeTools }
                .forEach { add(it.definition) }
        }
        // Only servers whose tools have actually been listed contribute here, so one that is
        // switched off or unreachable is silently absent rather than offered and broken.
        addAll(mcpTools.all.map { it.definition })
    }

    /**
     * Resolves a tool the model asked for, or null if it may not run.
     *
     * [externalTools] is checked here and not only when the schemas are built, because "was it
     * offered?" and "may it run?" have to be the same question. A model that has seen `web_search`
     * in an earlier turn — or simply guesses the name — must not be able to reach the network
     * after the user switched external tools off. Being told the tool is unknown is the right
     * answer: it is unknown, this turn.
     *
     * Built-ins win ties. [McpTool.qualifiedName] namespaces every MCP tool, so a collision means a
     * server picked a name that looks namespaced, and resolving to the built-in is the safe way to
     * break it: a remote server must not take over `web_search` by naming a tool after it.
     */
    fun find(name: String, externalTools: Boolean = true): CirrusTool? {
        val settings = settingsRepository.current.value

        memoryTools.all.firstOrNull { it.name == name }
            ?.let { return it.takeIf { settings.memoryEnabled } }
        if (name == notificationTool.name) {
            return notificationTool.takeIf { settings.notificationToolEnabled }
        }
        shellTools.device.firstOrNull { it.name == name }
            ?.let { return it.takeIf { settings.shellToolsEnabled } }
        shellTools.apps.firstOrNull { it.name == name }
            ?.let { return it.takeIf { settings.appControlEnabled } }

        if (!externalTools) return null

        // GitHub carries two gates of its own beyond the external switch, and both have to be asked
        // here as well as when the schemas are built — `staticTools` holds every GitHub tool
        // unconditionally, so falling through to it would resolve one that was never offered: with
        // the feature switched off, or with no token at all, or a write while writes are refused.
        // The client refuses a write of its own accord, but a *read* would have gone out with the
        // user's token attached against a setting that says not to.
        gitHubTools.all.firstOrNull { it.name == name }?.let { tool ->
            if (!gitHubEnabled) return null
            if (tool in gitHubTools.writeTools && !gitHubCredentials.writesAllowed) return null
            return tool
        }

        return staticTools[name] ?: mcpTools.find(name)
    }

    /**
     * The standing rules that belong in the system prompt rather than in a tool description.
     *
     * A tool's description is read when the model is deciding whether to call *that tool*. Two of
     * the shell's rules have to survive longer than one decision — stay non-destructive, and clean
     * up before you finish — because the second one is about the end of a session, which is exactly
     * the moment nobody is looking at a tool description. Both are one sentence each: this is paid
     * for on every turn, and a paragraph of etiquette would cost more context than the tool saves.
     *
     * Null when nothing needs saying, so an install with the shell switched off sends nothing.
     */
    fun standingBrief(): String? {
        val settings = settingsRepository.current.value
        // The emptiness check is not belt-and-braces: it is what stops the brief describing a shell
        // to a build that has none.
        if (!settings.shellToolsEnabled || shellTools.device.isEmpty()) return null
        return "You can run shell commands on this phone with run_command. It works inside a " +
            "private scratch workspace and can reach nothing outside it. Two rules hold for the " +
            "whole conversation: be non-destructive — read before you write, and never remove a " +
            "file you did not create — and clean up after yourself by calling clean_workspace " +
            "before you finish, whenever you have written anything. Use get_datetime rather than " +
            "assuming today's date."
    }

    private val gitHubEnabled: Boolean
        get() = settingsRepository.current.value.gitHubToolsEnabled &&
            gitHubCredentials.isConfigured
}

/**
 * The local tool set, split by what it is allowed to do.
 *
 * Two lists rather than one, because they answer to two different switches and the difference is
 * real: [device] answers questions and touches nothing outside a scratch directory, while [apps]
 * acts on the phone — it puts another app in front of whatever the user was reading. Assembled in
 * `AppModule` rather than injected as a set, in the same spirit as [GitHubToolSet]: an explicit
 * list is far easier to audit when the question is "which of these can do something?"
 */
class ShellToolSet(
    val device: List<CirrusTool>,
    val apps: List<CirrusTool>,
) {
    val all: List<CirrusTool> = device + apps
}

/**
 * The GitHub tools, grouped so the registry can tell reads from writes.
 *
 * Hilt has no multibinding set up in this project, and an explicit list is easier to audit than
 * an injected set when the question "which of these can change something?" has to have an
 * obviously correct answer.
 */
@Singleton
class GitHubToolSet @Inject constructor(
    listRepos: ListReposTool,
    searchCode: SearchCodeTool,
    readFile: ReadFileTool,
    listDirectory: ListDirectoryTool,
    listIssues: ListIssuesTool,
    getIssue: GetIssueTool,
    listPulls: ListPullRequestsTool,
    getPull: GetPullRequestTool,
    createIssue: CreateIssueTool,
    comment: CommentTool,
    reviewPull: ReviewPullRequestTool,
    writeFile: WriteFileTool,
) {
    val all: List<CirrusTool> = listOf(
        listRepos,
        searchCode,
        readFile,
        listDirectory,
        listIssues,
        getIssue,
        listPulls,
        getPull,
        createIssue,
        comment,
        reviewPull,
        writeFile,
    )

    /** Tools that change state on GitHub, derived from each tool's [GitHubTool.writes]. */
    val writeTools: Set<CirrusTool> = all.filter { it is GitHubTool && it.writes }.toSet()
}

/** `jsonPrimitive.content` throws on JSON null; this returns null instead. */
private fun JsonPrimitive.contentOrNullSafe(): String? =
    content.takeIf { it.isNotBlank() && this !is kotlinx.serialization.json.JsonNull }
