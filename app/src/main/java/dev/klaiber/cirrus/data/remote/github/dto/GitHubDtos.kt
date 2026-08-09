package dev.klaiber.cirrus.data.remote.github.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the GitHub REST API, trimmed to the fields the tools actually pass to a model.
 *
 * GitHub's responses are enormous — a single repository object carries around a hundred fields,
 * most of them URLs. Everything omitted here is context the model would have to read past.
 */

@Serializable
data class RepoDto(
    @SerialName("full_name") val fullName: String,
    val name: String = "",
    val description: String? = null,
    val private: Boolean = false,
    val fork: Boolean = false,
    val archived: Boolean = false,
    val language: String? = null,
    @SerialName("default_branch") val defaultBranch: String = "main",
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("open_issues_count") val openIssues: Int = 0,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
)

/** `/repos/{owner}/{repo}/contents/{path}` returns this for a file, or a list of them for a dir. */
@Serializable
data class ContentDto(
    val name: String = "",
    val path: String = "",
    /** "file", "dir", "symlink" or "submodule". */
    val type: String = "",
    val size: Long = 0,
    /** Base64 with embedded newlines, and absent for directory entries. */
    val content: String? = null,
    val encoding: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class SearchCodeResponseDto(
    @SerialName("total_count") val totalCount: Int = 0,
    val incomplete_results: Boolean = false,
    val items: List<SearchCodeItemDto> = emptyList(),
)

@Serializable
data class SearchCodeItemDto(
    val name: String = "",
    val path: String = "",
    val repository: RepoDto? = null,
    @SerialName("html_url") val htmlUrl: String = "",
)

@Serializable
data class UserRefDto(
    val login: String = "",
)

@Serializable
data class LabelDto(
    val name: String = "",
)

@Serializable
data class IssueDto(
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val state: String = "",
    val user: UserRefDto? = null,
    val labels: List<LabelDto> = emptyList(),
    val comments: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    /** Present only when the row is really a pull request; the issues endpoint returns both. */
    @SerialName("pull_request") val pullRequest: PullRefDto? = null,
)

@Serializable
data class PullRefDto(
    val url: String = "",
)

@Serializable
data class CommentDto(
    val body: String = "",
    val user: UserRefDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
)

@Serializable
data class PullDto(
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val state: String = "",
    val draft: Boolean = false,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    val user: UserRefDto? = null,
    val head: GitRefDto? = null,
    val base: GitRefDto? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerialName("changed_files") val changedFiles: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
)

@Serializable
data class GitRefDto(
    val ref: String = "",
    val sha: String = "",
)

@Serializable
data class PullFileDto(
    val filename: String = "",
    val status: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    /** The unified diff for this file. Absent for binaries and very large files. */
    val patch: String? = null,
)

// ---- Request bodies -------------------------------------------------------------------------

@Serializable
data class CreateIssueRequestDto(
    val title: String,
    val body: String? = null,
    val labels: List<String>? = null,
)

@Serializable
data class CommentRequestDto(
    val body: String,
)

@Serializable
data class CreateReviewRequestDto(
    val body: String? = null,
    /** "COMMENT", "APPROVE" or "REQUEST_CHANGES". */
    val event: String,
    val comments: List<ReviewCommentDto>? = null,
)

@Serializable
data class ReviewCommentDto(
    val path: String,
    /** Line in the file's diff, as GitHub numbers it in the head commit. */
    val line: Int,
    val body: String,
)

@Serializable
data class ErrorResponseDto(
    val message: String? = null,
    @SerialName("documentation_url") val documentationUrl: String? = null,
)
