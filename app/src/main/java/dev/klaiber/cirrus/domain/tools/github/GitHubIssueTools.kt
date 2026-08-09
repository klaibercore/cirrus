package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.dto.CreateIssueRequestDto
import dev.klaiber.cirrus.domain.tools.CirrusTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Issue triage: reading a backlog, and — behind the write gate — adding to it. */

class ListIssuesTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_list_issues"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List issues in a GitHub repository. Returns titles and numbers only; " +
            "use github_get_issue for the body and discussion of a specific one.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        stringProperty("state", "One of `open`, `closed`, `all`. Defaults to `open`.")
        stringProperty("labels", "Comma-separated label filter, e.g. `bug,help wanted`.")
        integerProperty("limit", "How many to return (1-100, default 30).")
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val state = arguments.stringOrNull("state")?.lowercase()?.takeIf { it in STATES } ?: "open"
        val limit = arguments.intOrNull("limit") ?: DEFAULT_LIMIT

        val issues = client.listIssues(
            owner = target.owner,
            repo = target.repo,
            state = state,
            labels = arguments.stringOrNull("labels"),
            limit = limit,
        )
        // GitHub's issues endpoint returns pull requests too; the tools keep them separate.
        val realIssues = issues.filter { it.pullRequest == null }

        buildJsonObject {
            put("repo", target.fullName)
            put("state", state)
            put("count", realIssues.size)
            put(
                "issues",
                JsonArray(
                    realIssues.map { issue ->
                        buildJsonObject {
                            put("number", issue.number)
                            put("title", issue.title)
                            put("state", issue.state)
                            issue.user?.login?.let { put("author", it) }
                            if (issue.labels.isNotEmpty()) {
                                put("labels", issue.labels.joinToString(",") { it.name })
                            }
                            put("comments", issue.comments)
                            issue.updatedAt?.let { put("updated_at", it) }
                        }
                    },
                ),
            )
        }.toString()
    }

    private companion object {
        val STATES = setOf("open", "closed", "all")
        const val DEFAULT_LIMIT = 30
    }
}

class GetIssueTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_get_issue"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Read one issue in full, including its body and its comment thread.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        integerProperty("number", "The issue number.", required = true)
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val number = arguments.intOrNull("number") ?: return@runTool missingArgument("number")

        val issue = client.getIssue(target.owner, target.repo, number)
        val comments = if (issue.comments > 0) {
            client.listIssueComments(target.owner, target.repo, number, MAX_COMMENTS)
        } else {
            emptyList()
        }

        buildJsonObject {
            put("repo", target.fullName)
            put("number", issue.number)
            put("title", issue.title)
            put("state", issue.state)
            issue.user?.login?.let { put("author", it) }
            if (issue.labels.isNotEmpty()) {
                put("labels", issue.labels.joinToString(",") { it.name })
            }
            put("body", issue.body.orEmpty().take(MAX_BODY))
            put("url", issue.htmlUrl)
            put(
                "comments",
                JsonArray(
                    comments.map { comment ->
                        buildJsonObject {
                            put("author", comment.user?.login ?: "")
                            put("body", comment.body.take(MAX_COMMENT))
                        }
                    },
                ),
            )
        }.toString()
    }

    private companion object {
        const val MAX_COMMENTS = 30
        const val MAX_BODY = 20_000
        const val MAX_COMMENT = 4_000
    }
}

/**
 * Opens an issue.
 *
 * Writes are gated in [GitHubClient] rather than here, so a new mutating tool cannot forget the
 * check. The description says plainly that it writes, because the model decides when to call it.
 */
class CreateIssueTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_create_issue"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Open a new issue on GitHub. This WRITES to the repository and is visible " +
            "to everyone with access. Only call it when the user has clearly asked for an issue " +
            "to be created, and summarise what you are about to file first.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        stringProperty("title", "A short, specific title.", required = true)
        stringProperty("body", "The issue body. Markdown is supported.")
        stringProperty("labels", "Comma-separated labels to apply.")
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val title = arguments.stringOrNull("title") ?: return@runTool missingArgument("title")

        val issue = client.createIssue(
            owner = target.owner,
            repo = target.repo,
            body = CreateIssueRequestDto(
                title = title,
                body = arguments.stringOrNull("body"),
                labels = arguments.stringOrNull("labels")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.takeIf { it.isNotEmpty() },
            ),
        )

        buildJsonObject {
            put("created", true)
            put("number", issue.number)
            put("url", issue.htmlUrl)
        }.toString()
    }
}

/** Adds a comment to an issue or a pull request; GitHub treats both the same way. */
class CommentTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_comment"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Post a comment on a GitHub issue or pull request. This WRITES and is " +
            "publicly visible. Only call it when the user has explicitly asked you to comment.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        integerProperty("number", "The issue or pull request number.", required = true)
        stringProperty("body", "The comment text. Markdown is supported.", required = true)
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val number = arguments.intOrNull("number") ?: return@runTool missingArgument("number")
        val body = arguments.stringOrNull("body") ?: return@runTool missingArgument("body")

        val comment = client.comment(target.owner, target.repo, number, body)
        buildJsonObject {
            put("posted", true)
            put("url", comment.htmlUrl)
        }.toString()
    }
}
