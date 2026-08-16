package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Read-only GitHub tools.
 *
 * These are what make the app useful against a codebase: list what exists, search it, and read
 * specific files. They work against private repositories exactly as they do public ones — the
 * token's scope decides, not the tool.
 */

class ListReposTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_list_repos"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List the GitHub repositories the user can access, most recently pushed " +
            "first. Includes private repositories. Use this when you need to find the right " +
            "repository before reading or searching it.",
    ) {
        intParam("limit", "How many to return (1-100, default 30).")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repos = client.listRepos(arguments.int("limit") ?: DEFAULT_LIMIT)
        return buildJsonObject {
            put("count", repos.size)
            put(
                "repositories",
                JsonArray(
                    repos.map { repo ->
                        buildJsonObject {
                            put("repo", repo.fullName)
                            put("private", repo.private)
                            put("default_branch", repo.defaultBranch)
                            repo.language?.let { put("language", it) }
                            repo.description?.let { put("description", it.clip(200)) }
                            if (repo.archived) put("archived", true)
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

class SearchCodeTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_search_code"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Search code on GitHub and get back matching file paths. Supports GitHub's " +
            "search qualifiers, so scope the query with repo:owner/name, path:, language: or " +
            "filename: to get useful results. Returns locations, not file contents — follow up " +
            "with github_read_file.",
        required = listOf("query"),
    ) {
        stringParam(
            "query",
            "Search query, e.g. \"ChatEngine repo:klaibercore/cirrus\" or " +
                "\"fun respond language:kotlin repo:owner/name\".",
        )
        intParam("limit", "How many results to return (1-100, default 20).")
    }

    override suspend fun run(arguments: JsonObject): String {
        val query = arguments.string("query")
            ?: return errorJson("missing required argument: query")

        val response = client.searchCode(query, arguments.int("limit") ?: DEFAULT_LIMIT)
        return buildJsonObject {
            put("query", query)
            put("total_count", response.totalCount)
            put(
                "matches",
                JsonArray(
                    response.items.map { item ->
                        buildJsonObject {
                            put("repo", item.repository?.fullName ?: "")
                            put("path", item.path)
                        }
                    },
                ),
            )
        }.toString()
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
    }
}

class ReadFileTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_read_file"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Read a single file from a GitHub repository. Works on private " +
            "repositories the token can see. Long files are truncated, so prefer reading a " +
            "specific file over browsing.",
        required = listOf("repo", "path"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        stringParam("path", "Path within the repository, e.g. \"src/main/kotlin/App.kt\".")
        stringParam("ref", "Branch, tag or commit SHA. Defaults to the default branch.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }
        val path = arguments.string("path")
            ?: return errorJson("missing required argument: path")

        val content = client.readFile(repo.owner, repo.name, path, arguments.string("ref"))
        if (content.type != "file") {
            return errorJson("$path is a ${content.type}, not a file. Use github_list_directory.")
        }
        val encoded = content.content
            ?: return errorJson("$path returned no content; it may be too large to read via the API.")

        val text = runCatching { GitHubClient.decodeContent(encoded) }.getOrNull()
            ?: return errorJson("$path does not appear to be a text file.")

        return buildJsonObject {
            put("repo", repo.toString())
            put("path", content.path)
            put("size_bytes", content.size)
            put("content", text.clip(MAX_FILE_CHARS))
        }.toString()
    }

    private companion object {
        const val MAX_FILE_CHARS = 40_000
    }
}

class ListDirectoryTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_list_directory"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List the files and folders at a path in a GitHub repository. Use it to " +
            "orient yourself in an unfamiliar repository before reading files.",
        required = listOf("repo"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        stringParam("path", "Directory path. Omit or use \"\" for the repository root.")
        stringParam("ref", "Branch, tag or commit SHA. Defaults to the default branch.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }
        val path = arguments.string("path").orEmpty()

        val entries = client.listDirectory(repo.owner, repo.name, path, arguments.string("ref"))
        return buildJsonObject {
            put("repo", repo.toString())
            put("path", path.ifEmpty { "/" })
            put(
                "entries",
                JsonArray(
                    entries.map { entry ->
                        buildJsonObject {
                            put("name", entry.name)
                            put("type", entry.type)
                            if (entry.type == "file") put("size_bytes", entry.size)
                        }
                    },
                ),
            )
        }.toString()
    }
}

class ListIssuesTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_list_issues"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List issues in a repository. Note that GitHub treats pull requests as " +
            "issues; entries marked is_pull_request are pull requests.",
        required = listOf("repo"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        enumParam("state", "Which issues to list. Defaults to open.", listOf("open", "closed", "all"))
        stringParam("labels", "Comma-separated label names to filter by.")
        intParam("limit", "How many to return (1-100, default 30).")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }

        val issues = client.listIssues(
            owner = repo.owner,
            repo = repo.name,
            state = arguments.string("state") ?: "open",
            labels = arguments.string("labels"),
            limit = arguments.int("limit") ?: DEFAULT_LIMIT,
        )
        return buildJsonObject {
            put("repo", repo.toString())
            put(
                "issues",
                JsonArray(
                    issues.map { issue ->
                        buildJsonObject {
                            put("number", issue.number)
                            put("title", issue.title)
                            put("state", issue.state)
                            put("comments", issue.comments)
                            issue.user?.let { put("author", it.login) }
                            if (issue.labels.isNotEmpty()) {
                                put("labels", issue.labels.joinToString(",") { it.name })
                            }
                            if (issue.pullRequest != null) put("is_pull_request", true)
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

class GetIssueTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_get_issue"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Read one issue in full, including its body and discussion.",
        required = listOf("repo", "number"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        intParam("number", "Issue number.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }
        val number = arguments.int("number")
            ?: return errorJson("missing required argument: number")

        val issue = client.getIssue(repo.owner, repo.name, number)
        val comments = if (issue.comments > 0) {
            client.listIssueComments(repo.owner, repo.name, number, MAX_COMMENTS)
        } else {
            emptyList()
        }

        return buildJsonObject {
            put("repo", repo.toString())
            put("number", issue.number)
            put("title", issue.title)
            put("state", issue.state)
            issue.user?.let { put("author", it.login) }
            put("body", (issue.body ?: "").clip(MAX_BODY_CHARS))
            put("url", issue.htmlUrl)
            put(
                "comments",
                JsonArray(
                    comments.map { comment ->
                        buildJsonObject {
                            put("author", comment.user?.login ?: "")
                            put("body", comment.body.clip(MAX_COMMENT_CHARS))
                        }
                    },
                ),
            )
        }.toString()
    }

    private companion object {
        const val MAX_COMMENTS = 30
        const val MAX_BODY_CHARS = 8_000
        const val MAX_COMMENT_CHARS = 2_000
    }
}

class ListPullRequestsTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_list_pull_requests"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List pull requests in a repository, most recently updated first.",
        required = listOf("repo"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        enumParam("state", "Which to list. Defaults to open.", listOf("open", "closed", "all"))
        intParam("limit", "How many to return (1-100, default 30).")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }

        val pulls = client.listPulls(
            owner = repo.owner,
            repo = repo.name,
            state = arguments.string("state") ?: "open",
            limit = arguments.int("limit") ?: DEFAULT_LIMIT,
        )
        return buildJsonObject {
            put("repo", repo.toString())
            put(
                "pull_requests",
                JsonArray(
                    pulls.map { pull ->
                        buildJsonObject {
                            put("number", pull.number)
                            put("title", pull.title)
                            put("state", pull.state)
                            if (pull.draft) put("draft", true)
                            pull.user?.let { put("author", it.login) }
                            pull.head?.let { put("head", it.ref) }
                            pull.base?.let { put("base", it.ref) }
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

class GetPullRequestTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_get_pull_request"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Read a pull request: its description, and the diff of every changed file. " +
            "This is the tool to use before reviewing. Large diffs are truncated per file, so " +
            "read specific files with github_read_file if you need full context.",
        required = listOf("repo", "number"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        intParam("number", "Pull request number.")
        stringParam("include_diff", "\"false\" to skip the per-file patches. Defaults to true.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }
        val number = arguments.int("number")
            ?: return errorJson("missing required argument: number")
        val includeDiff = arguments.string("include_diff")?.lowercase() != "false"

        val pull = client.getPull(repo.owner, repo.name, number)
        val files = client.listPullFiles(repo.owner, repo.name, number, MAX_FILES)

        return buildJsonObject {
            put("repo", repo.toString())
            put("number", pull.number)
            put("title", pull.title)
            put("state", pull.state)
            if (pull.draft) put("draft", true)
            pull.user?.let { put("author", it.login) }
            pull.head?.let { put("head", it.ref) }
            pull.base?.let { put("base", it.ref) }
            put("additions", pull.additions)
            put("deletions", pull.deletions)
            put("changed_files", pull.changedFiles)
            put("body", (pull.body ?: "").clip(MAX_BODY_CHARS))
            put("url", pull.htmlUrl)
            put(
                "files",
                JsonArray(
                    files.map { file ->
                        buildJsonObject {
                            put("path", file.filename)
                            put("status", file.status)
                            put("additions", file.additions)
                            put("deletions", file.deletions)
                            if (includeDiff) {
                                // Binaries and very large files come back with no patch at all.
                                put("patch", (file.patch ?: "[no diff available]").clip(MAX_PATCH_CHARS))
                            }
                        }
                    },
                ),
            )
        }.toString()
    }

    private companion object {
        const val MAX_FILES = 50
        const val MAX_BODY_CHARS = 8_000
        const val MAX_PATCH_CHARS = 12_000
    }
}
