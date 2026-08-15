package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.settings.SettingSwitch
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

    /**
     * True when the effect outlives the turn, happens outside Cirrus, and cannot be reversed by
     * calling the same tool again.
     *
     * That definition is narrower than "changes something", and each clause is doing work. Pausing
     * music changes something and is not a write: the next call unpauses it. Remembering a fact
     * changes something and is not a write: it is Cirrus's own store, and the memory screen
     * restores anything. Opening a GitHub issue is a write, because there is no call that unopens
     * it and other people can already see it.
     *
     * Getting this wrong in the cautious direction is not free either — gating memory behind the
     * write switch would mean cross-session memory silently not working, which is indistinguishable
     * from it being broken. The test is the reversibility, not the severity.
     */
    val writes: Boolean get() = false

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
    private val deviceTools: DeviceToolSet,
    private val spotifyTools: SpotifyToolSet,
    private val settingsTool: DescribeSettingsTool,
    private val mcpTools: McpToolSet,
    private val settingsRepository: SettingsRepository,
    private val gitHubCredentials: GitHubCredentials,
    private val spotifyCredentials: SpotifyCredentials,
) {
    private val webTools: List<CirrusTool> = listOf(webSearchTool, webFetchTool)

    /**
     * Every tool, with what stands between it and running.
     *
     * Rebuilt per call because most of it is dynamic — MCP tools come and go with their servers —
     * and because there is exactly one thing worse than recomputing a list: two lists. This used to
     * be a chain of `if`s in [definitions] and a second, subtly different chain in [find], which is
     * how a GitHub write ended up offered in one and refused in the other. "Was it offered?" and
     * "may it run?" are now the same question asked of the same table.
     */
    private data class Group(
        val tools: List<CirrusTool>,
        /** The settings switch that governs it, or null when nothing does. */
        val gate: SettingSwitch?,
        /** Whether the conversation's own tools switch has to be on as well. */
        val external: Boolean,
        /**
         * Whether the credential behind the switch is actually present.
         *
         * Read from the credential holder rather than from [AppSettings], and the difference is not
         * academic: the holder is what the HTTP layer will use, while the settings field is a
         * mirror of it kept up to date by a collector on another scope. Gating on the mirror means
         * that for the moment after a token is saved — which is exactly when someone tries the
         * feature — the gate and the client disagree about whether there is a token.
         */
        val ready: Boolean = true,
    )

    private fun groups(): List<Group> = listOf(
        // Offered as a set: recall with nothing to find is a wasted round trip, and remember with
        // no way to correct it is worse than not remembering at all.
        Group(memoryTools.all, SettingSwitch.MEMORY, external = false),
        Group(listOf(notificationTool), SettingSwitch.NOTIFICATIONS, external = false),
        // The shell, the clock and the calendar are on the same footing as memory: local, instant,
        // and nothing leaves the phone. A model that cannot ask what today's date is answers every
        // scheduling question from the year it was trained in, which is wrong in the way that looks
        // most convincing.
        Group(deviceTools.shell, SettingSwitch.SHELL, external = false),
        Group(deviceTools.apps, SettingSwitch.APPS, external = false),
        Group(deviceTools.location, SettingSwitch.LOCATION, external = false),
        // Ungated on purpose, and the only tool that is. It is how a model finds out that the
        // reason it cannot do something is a switch rather than a missing feature, so putting it
        // behind a switch would be a joke at the user's expense.
        Group(listOf(settingsTool), gate = null, external = false),
        Group(webTools, gate = null, external = true),
        Group(
            tools = gitHubTools.all,
            gate = SettingSwitch.GITHUB,
            external = true,
            ready = gitHubCredentials.isConfigured,
        ),
        Group(
            tools = spotifyTools.all,
            gate = SettingSwitch.SPOTIFY,
            external = true,
            ready = spotifyCredentials.isConnected,
        ),
        // Only servers whose tools have actually been listed contribute here, so one that is
        // switched off or unreachable is silently absent rather than offered and broken.
        Group(mcpTools.all, gate = null, external = true),
    )

    /** What to offer the model this turn. */
    fun definitions(externalTools: Boolean): List<JsonElement> {
        val settings = settingsRepository.current.value
        return groups().flatMap { group ->
            group.tools
                .filter { access(it, group, settings, externalTools) is Access.Allowed }
                .map { it.definition }
        }
    }

    /**
     * Resolves a tool the model asked for, or null if it may not run.
     *
     * Built-ins win ties. [McpTool.qualifiedName] namespaces every MCP tool, so a collision means a
     * server picked a name that looks namespaced, and resolving to the built-in is the safe way to
     * break it: a remote server must not take over `web_search` by naming a tool after it. The
     * group order above is that precedence.
     */
    fun find(name: String, externalTools: Boolean = true): CirrusTool? =
        (resolve(name, externalTools) as? Access.Allowed)?.tool

    /**
     * Why a call did not run, addressed to the model.
     *
     * The reason this is not just "Unknown tool" is that a model cannot otherwise tell "this app
     * cannot do that" from "this app can do that, but not until somebody flips a switch". It
     * guesses, and it guesses the first one — so the user is told their app lacks a feature it
     * shipped with, and nothing in the conversation will ever correct that.
     */
    fun explainRefusal(name: String, externalTools: Boolean = true): String =
        when (val access = resolve(name, externalTools)) {
            is Access.Allowed -> "Unknown tool: $name"
            is Access.Blocked -> access.explanation
            Access.Unknown -> "Unknown tool: $name. Call describe_settings to see what is " +
                "available and what is switched off."
        }

    private fun resolve(name: String, externalTools: Boolean): Access {
        val settings = settingsRepository.current.value
        groups().forEach { group ->
            val tool = group.tools.firstOrNull { it.name == name } ?: return@forEach
            return access(tool, group, settings, externalTools)
        }
        return Access.Unknown
    }

    private sealed interface Access {
        data class Allowed(val tool: CirrusTool) : Access
        data class Blocked(val explanation: String) : Access
        data object Unknown : Access
    }

    /**
     * The one place a tool is allowed or refused.
     *
     * Order matters only for which explanation is given, and it goes from the switch nearest the
     * user outwards: the toggle in the message box they can see, then the setting, then the
     * credential behind it, then the write gate.
     */
    private fun access(
        tool: CirrusTool,
        group: Group,
        settings: AppSettings,
        externalTools: Boolean,
    ): Access = when {
        group.external && !externalTools -> Access.Blocked(
            "${tool.name} needs the tools switch for this conversation, which is off. It is the " +
                "toggle in the message box; ask the user to turn it on. It governs everything " +
                "that leaves the phone — web search, GitHub, Spotify and MCP servers.",
        )

        group.gate != null && !group.gate.isOn(settings) -> Access.Blocked(
            "${tool.name} is switched off. ${group.gate.remedy(settings)}",
        )

        !group.ready -> Access.Blocked(
            "${tool.name} is switched on but not signed in or configured yet. " +
                (group.gate?.remedy(settings) ?: group.gate?.credentialHint.orEmpty()),
        )

        tool.writes && !settings.writeToolsAllowed -> Access.Blocked(
            "${tool.name} changes something outside Cirrus, and write actions are off. " +
                "${SettingSwitch.WRITES.remedy(settings)} Everything read-only still works, so " +
                "say what you would have done and let the user decide.",
        )

        else -> Access.Allowed(tool)
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
        if (!settings.shellToolsEnabled || deviceTools.shell.isEmpty()) return null
        return "You can run shell commands on this phone with run_command. It works inside a " +
            "private scratch workspace and can reach nothing outside it. Pass text to work on in " +
            "its \"input\" argument rather than quoting it into the command, and name a \"topic\" " +
            "per job so its files stay together. Two rules hold for the whole conversation: be " +
            "non-destructive — read before you write, and never remove a file you did not create " +
            "— and clean up after yourself by calling clean_workspace before you finish, whenever " +
            "you have written anything. Use get_datetime rather than assuming today's date."
    }

}

/**
 * The tools that run on the phone itself, split by the switch each one answers to.
 *
 * Three lists rather than one, because the three ask the user for genuinely different things.
 * [shell] answers questions and touches nothing outside a scratch directory. [apps] acts — it puts
 * another app in front of whatever was being read, or starts music playing out loud. [location]
 * reads the most personal thing here, and is the only one that also needs Android's own permission.
 *
 * Assembled in `AppModule` rather than injected as a set, in the same spirit as [GitHubToolSet]: an
 * explicit list is far easier to audit when the question is "which of these can do something?"
 */
class DeviceToolSet(
    val shell: List<CirrusTool>,
    val apps: List<CirrusTool>,
    val location: List<CirrusTool>,
) {
    val all: List<CirrusTool> = shell + apps + location
}

/**
 * The Spotify tools. One list: [CirrusTool.writes] now carries the read/write split that used to
 * need a second one.
 */
class SpotifyToolSet(val all: List<CirrusTool>)

/**
 * The GitHub tools.
 *
 * Hilt has no multibinding set up in this project, and an explicit list is easier to audit than an
 * injected set. Which of them write is no longer this class's business — each tool declares it on
 * [CirrusTool.writes], and one gate in [ToolRegistry] reads that for every integration alike.
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

}

/** `jsonPrimitive.content` throws on JSON null; this returns null instead. */
private fun JsonPrimitive.contentOrNullSafe(): String? =
    content.takeIf { it.isNotBlank() && this !is kotlinx.serialization.json.JsonNull }
