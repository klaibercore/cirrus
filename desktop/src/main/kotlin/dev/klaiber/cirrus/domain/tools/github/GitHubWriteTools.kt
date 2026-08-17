package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.GitHubException
import dev.klaiber.cirrus.data.remote.github.dto.PutFileRequestDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Commits a single file.
 *
 * The read tools let a model navigate a repository; this is the one that changes it. Writes are
 * gated in [GitHubClient] rather than here, so the check cannot be forgotten, and the tool is not
 * offered to the model at all unless the user has turned writes on.
 *
 * GitHub's contents endpoint distinguishes create from update by whether `sha` is present, and
 * answers 422 when the caller guesses wrong. Rather than make the model track blob SHAs across a
 * conversation, this resolves the SHA itself when one was not supplied: a file that already
 * exists is updated, one that does not is created. That costs a GET on the way in and removes
 * the failure mode entirely.
 */
class WriteFileTool(
    private val client: GitHubClient,
) : GitHubTool() {

    override val name = "github_create_or_update_file"

    override val writes = true

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Create a new file or replace an existing one, as a single commit. This " +
            "WRITES to the repository and the commit is visible to everyone with access. Only " +
            "call it when the user has clearly asked for a file to be written, and show them " +
            "the content you intend to commit first. Replacing a file overwrites it in full, so " +
            "read it first and send back the complete new content, not just the changed part.",
        required = listOf("repo", "path", "content", "message"),
    ) {
        stringParam("repo", "Repository as \"owner/name\".")
        stringParam("path", "Path within the repository, e.g. \"docs/README.md\".")
        stringParam("content", "The complete new file content, as plain text.")
        stringParam("message", "Commit message. One line, imperative mood.")
        stringParam("branch", "Branch to commit to. Defaults to the repository's default branch.")
        stringParam(
            "sha",
            "Blob SHA of the file being replaced. Optional — it is looked up automatically when " +
                "omitted, so only send it if you already know it.",
        )
    }

    override suspend fun run(arguments: JsonObject): String {
        val repo = arguments.repoRef().getOrElse { return errorJson(it.message.orEmpty()) }
        val path = arguments.string("path")
            ?: return errorJson("missing required argument: path")
        val content = arguments.string("content")
            ?: return errorJson("missing required argument: content")
        val message = arguments.string("message")
            ?: return errorJson("missing required argument: message")
        val branch = arguments.string("branch")

        val sha = arguments.string("sha") ?: existingSha(repo, path, branch)

        val response = try {
            client.putFile(
                owner = repo.owner,
                repo = repo.name,
                path = path,
                body = PutFileRequestDto(
                    message = message,
                    content = GitHubClient.encodeContent(content),
                    branch = branch,
                    sha = sha,
                ),
            )
        } catch (failed: GitHubException.Failed) {
            // 422 here almost always means the SHA raced: someone else committed between the
            // lookup and the write. Say so, because "unprocessable entity" is not actionable.
            if (failed.code == HTTP_UNPROCESSABLE) {
                return errorJson(
                    "GitHub rejected the commit for ${repo}/$path: ${failed.message}. The file " +
                        "changed since it was read. Read it again and retry with the new content.",
                )
            }
            throw failed
        }

        return buildJsonObject {
            put("committed", true)
            put("path", path)
            put("updated", sha != null)
            put("branch", branch ?: "")
            put("commit", response.commit?.sha ?: "")
            put("url", response.content?.htmlUrl ?: response.commit?.htmlUrl ?: "")
        }.toString()
    }

    /** Null when the file is new, which is exactly what the create case wants to send. */
    private suspend fun existingSha(repo: RepoRef, path: String, branch: String?): String? = try {
        client.readFile(repo.owner, repo.name, path, ref = branch).sha
    } catch (notFound: GitHubException.NotFound) {
        null
    }

    private companion object {
        const val HTTP_UNPROCESSABLE = 422
    }
}
