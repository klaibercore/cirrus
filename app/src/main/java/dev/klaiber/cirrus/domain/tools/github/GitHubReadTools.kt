package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.domain.tools.CirrusTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Tools that only read from GitHub.
 *
 * Results are trimmed hard before they reach the model: a repository listing or a diff can run
 * to hundreds of kilobytes, and every byte of a tool result competes with the conversation for
 * context. Each tool returns the smallest useful shape and tells the model how to ask for more.
 */

/** Lists what the token can see, so the model can name repositories without guessing. */
class ListReposTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_list_repos"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List the GitHub repositories the user's token can access, most recently " +
            "pushed first. Includes private repositories. Use this first when the user refers " +
            "to a repository by a short or ambiguous name.",
    ) {
        integerProperty("limit", "How many to return (1-100, default 30).")
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val limit = arguments.intOrNull("limit") ?: DEFAULT_LIMIT
        val repos = client.listRepos(limit)
        buildJsonObject {
            put("count", repos.size)
            put(
                "repositories",
                JsonArray(
                    repos.map { repo ->
                        buildJsonObject {
                            put("full_name", repo.fullName)
                            put("private", repo.private)
                            put("default_branch", repo.defaultBranch)
                            repo.language?.let { put("language", it) }
                            repo.description?.take(SHORT_TEXT)?.let { put("description", it) }
                            put("open_issues", repo.openIssues)
                            repo.pushedAt?.let { put("pushed_at", it) }
                        }
                    },
                ),
            )
        }.toString()
    }

    private companion object {
        const val DEFAULT_LIMIT = 30
    }
}

/** Full-text code search, the fastest way into an unfamiliar repository. */
class SearchCodeTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_search_code"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Search code across the user's GitHub repositories, including private " +
            "ones. Supports GitHub's search qualifiers, so scope the query wherever possible: " +
            "`repo:owner/name`, `path:src/`, `language:kotlin`, `filename:build.gradle.kts`. " +
            "Returns file paths only - follow up with github_read_file to see the code.",
    ) {
        stringProperty(
            "query",
            "The search query, e.g. `ChatEngine repo:klaibercore/cirrus language:kotlin`.",
            required = true,
        )
        integerProperty("limit", "How many results to return (1-100, default 20).")
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val query = arguments.stringOrNull("query")
            ?: return@runTool missingArgument("query")
        val limit = arguments.intOrNull("limit") ?: DEFAULT_LIMIT

        val response = client.searchCode(query, limit)
        buildJsonObject {
            put("query", query)
            put("total_count", response.totalCount)
            put(
                "matches",
                JsonArray(
                    response.items.map { item ->
                        buildJsonObject {
                            put("repository", item.repository?.fullName ?: "")
                            put("path", item.path)
                        }
                    },
                ),
            )
            if (response.totalCount > response.items.size) {
                put(
                    "note",
                    "Showing ${response.items.size} of ${response.totalCount}. " +
                        "Narrow the query for more relevant hits.",
                )
            }
        }.toString()
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
    }
}

/** Reads one file. The workhorse: everything else exists to find the path to pass here. */
class ReadFileTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_read_file"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Read a single file from a GitHub repository, including private ones. " +
            "Long files are truncated; pass start_line to page through the rest.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        stringProperty(
            "path",
            "Path within the repository, e.g. `app/src/main/Foo.kt`.",
            required = true,
        )
        stringProperty("ref", "Branch, tag or commit SHA. Defaults to the default branch.")
        integerProperty("start_line", "1-based line to start from, for paging through a long file.")
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val path = arguments.stringOrNull("path") ?: return@runTool missingArgument("path")
        val ref = arguments.stringOrNull("ref")
        val startLine = (arguments.intOrNull("start_line") ?: 1).coerceAtLeast(1)

        val content = client.readFile(target.owner, target.repo, path, ref)
        val encoded = content.content
            ?: return@runTool errorJson("$path is a directory. Use github_list_directory instead.")
        if (content.encoding != null && content.encoding != "base64") {
            return@runTool errorJson("Unsupported encoding: ${content.encoding}.")
        }

        val text = GitHubClient.decodeContent(encoded)
        // A NUL byte in decoded output is the classic binary-file signal.
        if (text.any { it.code == 0 }) {
            return@runTool errorJson("$path looks like a binary file.")
        }

        val lines = text.lines()
        val window = lines.drop(startLine - 1).take(MAX_LINES)
        val lastLine = startLine - 1 + window.size

        buildJsonObject {
            put("repo", target.fullName)
            put("path", content.path)
            put("total_lines", lines.size)
            put("lines_shown", "$startLine-$lastLine")
            put("content", window.joinToString("\n").take(MAX_CHARS))
            if (lastLine < lines.size) {
                put("note", "Truncated. Call again with start_line=${lastLine + 1} for more.")
            }
        }.toString()
    }

    private companion object {
        const val MAX_LINES = 600
        const val MAX_CHARS = 40_000
    }
}

/** Directory listing, for orienting in a repository whose layout the model does not know. */
class ListDirectoryTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_list_directory"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List the files and folders at a path in a GitHub repository. Use this to " +
            "explore a repository's layout before reading files. Pass an empty path for the root.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        stringProperty("path", "Directory path. Omit or pass an empty string for the root.")
        stringProperty("ref", "Branch, tag or commit SHA. Defaults to the default branch.")
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val path = arguments.stringOrNull("path").orEmpty()
        val ref = arguments.stringOrNull("ref")

        val entries = client.listDirectory(target.owner, target.repo, path, ref)
        buildJsonObject {
            put("repo", target.fullName)
            put("path", path.ifBlank { "/" })
            put(
                "entries",
                JsonArray(
                    entries.take(MAX_ENTRIES).map { entry ->
                        buildJsonObject {
                            put("name", entry.name)
                            put("type", entry.type)
                            if (entry.type == "file") put("size", entry.size)
                        }
                    },
                ),
            )
            if (entries.size > MAX_ENTRIES) {
                put("note", "${entries.size - MAX_ENTRIES} more entries not shown.")
            }
        }.toString()
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
