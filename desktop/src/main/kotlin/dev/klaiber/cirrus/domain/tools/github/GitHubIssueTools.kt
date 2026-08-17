package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.dto.CreateIssueRequestDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Issue triage: reading a backlog lives in GitHubReadTools; adding to it lives here. */

/**
 * Opens an issue.
 *
 * Writes are gated in [GitHubClient] rather than here, so a new mutating tool cannot forget the
 * check. The description says plainly that it writes, because the model decides when to call it.
 */
class CreateIssueTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_create_issue"

    override val writes = true

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Open a new issue on GitHub. This WRITES to the repository and is visible " +
            "to everyone with access. Only call it when the user has clearly asked for an issue " +
            "to be created, and summarise what you are about to file first.",
        required = listOf("repo", "title"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        stringParam("title", "A short, specific title.")
        stringParam("body", "The issue body. Markdown is supported.")
        stringParam("labels", "Comma-separated labels to apply.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }
        val title = arguments.string("title")
            ?: return errorJson("missing required argument: title")

        val issue = client.createIssue(
            owner = repo.owner,
            repo = repo.name,
            body = CreateIssueRequestDto(
                title = title,
                body = arguments.string("body"),
                labels = arguments.string("labels")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.takeIf { it.isNotEmpty() },
            ),
        )

        return buildJsonObject {
            put("created", true)
            put("number", issue.number)
            put("url", issue.htmlUrl)
        }.toString()
    }
}

/** Adds a comment to an issue or a pull request; GitHub treats both the same way. */
class CommentTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_comment"

    override val writes = true

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Post a comment on a GitHub issue or pull request. This WRITES and is " +
            "publicly visible. Only call it when the user has explicitly asked you to comment.",
        required = listOf("repo", "number", "body"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        intParam("number", "The issue or pull request number.")
        stringParam("body", "The comment text. Markdown is supported.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }
        val number = arguments.int("number")
            ?: return errorJson("missing required argument: number")
        val body = arguments.string("body")
            ?: return errorJson("missing required argument: body")

        val comment = client.comment(repo.owner, repo.name, number, body)
        return buildJsonObject {
            put("posted", true)
            put("url", comment.htmlUrl)
        }.toString()
    }
}
