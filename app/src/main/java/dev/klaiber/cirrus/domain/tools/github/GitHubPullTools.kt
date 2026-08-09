package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.dto.CreateReviewRequestDto
import dev.klaiber.cirrus.data.remote.github.dto.ReviewCommentDto
import dev.klaiber.cirrus.domain.tools.CirrusTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Pull requests: listing them, reading the diff, and posting a review. */

class ListPullRequestsTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_list_pull_requests"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List pull requests in a GitHub repository, most recently updated first.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        stringProperty("state", "One of `open`, `closed`, `all`. Defaults to `open`.")
        integerProperty("limit", "How many to return (1-100, default 30).")
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val state = arguments.stringOrNull("state")?.lowercase()?.takeIf { it in STATES } ?: "open"

        val pulls = client.listPulls(
            owner = target.owner,
            repo = target.repo,
            state = state,
            limit = arguments.intOrNull("limit") ?: DEFAULT_LIMIT,
        )

        buildJsonObject {
            put("repo", target.fullName)
            put("state", state)
            put("count", pulls.size)
            put(
                "pull_requests",
                JsonArray(
                    pulls.map { pull ->
                        buildJsonObject {
                            put("number", pull.number)
                            put("title", pull.title)
                            put("state", if (pull.draft) "draft" else pull.state)
                            pull.user?.login?.let { put("author", it) }
                            pull.head?.ref?.let { put("head", it) }
                            pull.base?.ref?.let { put("base", it) }
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

/**
 * Reads a pull request and its per-file patches.
 *
 * Per-file patches are requested rather than the raw unified diff: they arrive already split,
 * carry line counts, and can be truncated file by file instead of cut off mid-hunk.
 */
class GetPullRequestTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_get_pull_request"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Read a pull request: its description, and the diff of every changed file. " +
            "Use this before reviewing. Large diffs are truncated per file.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        integerProperty("number", "The pull request number.", required = true)
        booleanProperty("include_diff", "Include per-file patches. Defaults to true.")
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val number = arguments.intOrNull("number") ?: return@runTool missingArgument("number")
        val includeDiff = arguments.booleanOrNull("include_diff") ?: true

        val pull = client.getPull(target.owner, target.repo, number)
        val files = if (includeDiff) {
            client.listPullFiles(target.owner, target.repo, number, MAX_FILES)
        } else {
            emptyList()
        }

        buildJsonObject {
            put("repo", target.fullName)
            put("number", pull.number)
            put("title", pull.title)
            put("state", if (pull.draft) "draft" else pull.state)
            pull.user?.login?.let { put("author", it) }
            pull.head?.ref?.let { put("head", it) }
            pull.base?.ref?.let { put("base", it) }
            put("additions", pull.additions)
            put("deletions", pull.deletions)
            put("changed_files", pull.changedFiles)
            put("body", pull.body.orEmpty().take(MAX_BODY))
            put("url", pull.htmlUrl)
            if (includeDiff) {
                put(
                    "files",
                    JsonArray(
                        files.map { file ->
                            buildJsonObject {
                                put("filename", file.filename)
                                put("status", file.status)
                                put("additions", file.additions)
                                put("deletions", file.deletions)
                                // Absent for binaries and files GitHub considers too large.
                                file.patch?.let { put("patch", it.take(MAX_PATCH)) }
                            }
                        },
                    ),
                )
                if (pull.changedFiles > files.size) {
                    put("note", "${pull.changedFiles - files.size} more files not shown.")
                }
            }
        }.toString()
    }

    private companion object {
        const val MAX_FILES = 50
        const val MAX_BODY = 10_000
        const val MAX_PATCH = 12_000
    }
}

/**
 * Posts a review.
 *
 * `APPROVE` and `REQUEST_CHANGES` carry real weight in a repository's protection rules, so the
 * description tells the model to default to `COMMENT` unless the user asked for a verdict.
 */
class ReviewPullRequestTool @Inject constructor(
    private val client: GitHubClient,
) : CirrusTool {

    override val name: String = "github_review_pull_request"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Post a review on a pull request. This WRITES and is publicly visible. " +
            "Prefer event=COMMENT; only use APPROVE or REQUEST_CHANGES when the user explicitly " +
            "asked for that verdict, because they can gate a merge. Inline comments must point " +
            "at lines that appear in the pull request's diff.",
    ) {
        stringProperty("repo", "Repository as `owner/name`.", required = true)
        integerProperty("number", "The pull request number.", required = true)
        stringProperty("event", "One of `COMMENT`, `APPROVE`, `REQUEST_CHANGES`.", required = true)
        stringProperty("body", "The overall review comment. Markdown is supported.")
        arrayProperty(
            "comments",
            "Optional inline comments, each an object with `path`, `line` and `body`.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = runTool {
        val target = arguments.repoOrNull() ?: return@runTool missingArgument("repo")
        val number = arguments.intOrNull("number") ?: return@runTool missingArgument("number")
        val event = arguments.stringOrNull("event")?.uppercase()
            ?: return@runTool missingArgument("event")
        if (event !in EVENTS) {
            return@runTool errorJson("event must be one of ${EVENTS.joinToString(", ")}.")
        }

        val inline = arguments["comments"]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { entry ->
                    val comment = entry.jsonObject
                    val path = comment.stringOrNull("path") ?: return@mapNotNull null
                    val line = comment.intOrNull("line") ?: return@mapNotNull null
                    val body = comment.stringOrNull("body") ?: return@mapNotNull null
                    ReviewCommentDto(path = path, line = line, body = body)
                }
            }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

        client.createReview(
            owner = target.owner,
            repo = target.repo,
            number = number,
            body = CreateReviewRequestDto(
                body = arguments.stringOrNull("body"),
                event = event,
                comments = inline,
            ),
        )

        buildJsonObject {
            put("posted", true)
            put("event", event)
            put("inline_comments", inline?.size ?: 0)
        }.toString()
    }

    private companion object {
        val EVENTS = setOf("COMMENT", "APPROVE", "REQUEST_CHANGES")
    }
}
