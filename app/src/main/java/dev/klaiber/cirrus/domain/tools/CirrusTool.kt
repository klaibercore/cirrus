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
 * depends on what the user has switched on and whether a GitHub token is present. Sending the
 * schema for a tool that cannot run wastes context and invites the model to call it and fail.
 */
@Singleton
class ToolRegistry @Inject constructor(
    webSearchTool: WebSearchTool,
    webFetchTool: WebFetchTool,
    private val gitHubTools: GitHubToolSet,
    private val settingsRepository: SettingsRepository,
    private val gitHubCredentials: GitHubCredentials,
) {
    private val webTools: List<CirrusTool> = listOf(webSearchTool, webFetchTool)

    private val tools: Map<String, CirrusTool> =
        (webTools + gitHubTools.all).associateBy { it.name }

    val definitions: List<JsonElement>
        get() = buildList {
            addAll(webTools.map { it.definition })
            if (gitHubEnabled) {
                val writesAllowed = gitHubCredentials.writesAllowed
                gitHubTools.all
                    .filter { writesAllowed || it !in gitHubTools.writeTools }
                    .forEach { add(it.definition) }
            }
        }

    fun find(name: String): CirrusTool? = tools[name]

    private val gitHubEnabled: Boolean
        get() = settingsRepository.current.value.gitHubToolsEnabled &&
            gitHubCredentials.isConfigured
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
