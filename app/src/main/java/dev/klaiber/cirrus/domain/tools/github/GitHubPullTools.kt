package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.dto.CreateReviewRequestDto
import dev.klaiber.cirrus.data.remote.github.dto.ReviewCommentDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Pull requests: listing and reading them lives in GitHubReadTools; reviewing lives here. */

/**
 * Posts a review.
 *
 * `APPROVE` and `REQUEST_CHANGES` carry real weight in a repository's protection rules, so the
 * description tells the model to default to `COMMENT` unless the user asked for a verdict.
 */
class ReviewPullRequestTool @Inject constructor(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_review_pull_request"

    override val writes = true

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Post a review on a pull request. This WRITES and is publicly visible. " +
            "Prefer event=COMMENT; only use APPROVE or REQUEST_CHANGES when the user explicitly " +
            "asked for that verdict, because they can gate a merge. Inline comments must point " +
            "at lines that appear in the pull request's diff.",
        required = listOf("repo", "number", "event"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        intParam("number", "The pull request number.")
        enumParam("event", "The review verdict.", listOf("COMMENT", "APPROVE", "REQUEST_CHANGES"))
        stringParam("body", "The overall review comment. Markdown is supported.")
        arrayParam(
            "comments",
            "Optional inline comments, each an object with `path`, `line` and `body`.",
        )
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }
        val number = arguments.int("number")
            ?: return errorJson("missing required argument: number")
        val event = arguments.string("event")?.uppercase()
            ?: return errorJson("missing required argument: event")
        if (event !in EVENTS) {
            return errorJson("event must be one of ${EVENTS.joinToString(", ")}.")
        }

        val inline = arguments["comments"]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { entry ->
                    val comment = entry.jsonObject
                    val path = comment.string("path") ?: return@mapNotNull null
                    val line = comment.int("line") ?: return@mapNotNull null
                    val body = comment.string("body") ?: return@mapNotNull null
                    ReviewCommentDto(path = path, line = line, body = body)
                }
            }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

        client.createReview(
            owner = repo.owner,
            repo = repo.name,
            number = number,
            body = CreateReviewRequestDto(
                body = arguments.string("body"),
                event = event,
                comments = inline,
            ),
        )

        return buildJsonObject {
            put("posted", true)
            put("event", event)
            put("inline_comments", inline?.size ?: 0)
        }.toString()
    }

    private companion object {
        val EVENTS = setOf("COMMENT", "APPROVE", "REQUEST_CHANGES")
    }
}
