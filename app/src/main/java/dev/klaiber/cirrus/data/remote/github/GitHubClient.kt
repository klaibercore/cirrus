package dev.klaiber.cirrus.data.remote.github

import android.util.Base64
import dev.klaiber.cirrus.data.remote.github.dto.CommentDto
import dev.klaiber.cirrus.data.remote.github.dto.CommentRequestDto
import dev.klaiber.cirrus.data.remote.github.dto.ContentDto
import dev.klaiber.cirrus.data.remote.github.dto.CreateIssueRequestDto
import dev.klaiber.cirrus.data.remote.github.dto.CreateReviewRequestDto
import dev.klaiber.cirrus.data.remote.github.dto.ErrorResponseDto
import dev.klaiber.cirrus.data.remote.github.dto.IssueDto
import dev.klaiber.cirrus.data.remote.github.dto.PullDto
import dev.klaiber.cirrus.data.remote.github.dto.PullFileDto
import dev.klaiber.cirrus.data.remote.github.dto.RepoDto
import dev.klaiber.cirrus.data.remote.github.dto.SearchCodeResponseDto
import dev.klaiber.cirrus.di.GitHubHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** GitHub failures the tool layer can turn into something a model can act on. */
sealed class GitHubException(message: String) : IOException(message) {
    class MissingToken : GitHubException(
        "No GitHub token configured. Add a personal access token in Settings › GitHub.",
    )

    class Unauthorized(detail: String?) : GitHubException(
        detail ?: "GitHub rejected the token. Check it has not expired or been revoked.",
    )

    class Forbidden(detail: String?) : GitHubException(
        detail ?: "The token lacks the scope for this. Reading private repositories needs `repo`.",
    )

    class NotFound(what: String) : GitHubException(
        "Not found: $what. It may be private, renamed, or the token may not have access to it.",
    )

    class RateLimited(val resetAtEpochSeconds: Long?) : GitHubException(
        "GitHub rate limit reached." + (resetAtEpochSeconds?.let { " Resets at epoch $it." } ?: ""),
    )

    class WritesDisabled : GitHubException(
        "Write actions are turned off. Enable them in Settings › GitHub to let a model open " +
            "issues, comment or post reviews.",
    )

    class Failed(val code: Int, detail: String?) :
        GitHubException("GitHub returned $code${detail?.let { ": $it" } ?: "."}")
}

/**
 * Thin transport over GitHub's REST API.
 *
 * Like [dev.klaiber.cirrus.data.remote.OllamaClient], this layer speaks only HTTP and JSON. It
 * knows nothing about tool schemas or how results are summarised for a model, so it can be
 * tested against a mock server without any of that machinery.
 */
@Singleton
class GitHubClient @Inject constructor(
    @GitHubHttp private val httpClient: OkHttpClient,
    private val json: Json,
    private val credentials: GitHubCredentials,
) {

    // ---- Repositories and code ----------------------------------------------------------------

    suspend fun listRepos(limit: Int): List<RepoDto> = get(
        path = listOf("user", "repos"),
        query = mapOf(
            "per_page" to limit.coerceIn(1, MAX_PAGE).toString(),
            "sort" to "pushed",
            // Everything the token can see, not just what the user owns.
            "affiliation" to "owner,collaborator,organization_member",
        ),
        deserializer = ListSerializer(RepoDto.serializer()),
        describe = "your repositories",
    )

    suspend fun getRepo(owner: String, repo: String): RepoDto = get(
        path = listOf("repos", owner, repo),
        deserializer = RepoDto.serializer(),
        describe = "$owner/$repo",
    )

    suspend fun searchCode(query: String, limit: Int): SearchCodeResponseDto = get(
        path = listOf("search", "code"),
        query = mapOf("q" to query, "per_page" to limit.coerceIn(1, MAX_PAGE).toString()),
        deserializer = SearchCodeResponseDto.serializer(),
        describe = "code matching \"$query\"",
    )

    /** A single file. Returns the decoded text; binaries are rejected by the caller. */
    suspend fun readFile(owner: String, repo: String, path: String, ref: String?): ContentDto = get(
        path = listOf("repos", owner, repo, "contents") + path.trim('/').split('/'),
        query = ref?.let { mapOf("ref" to it) }.orEmpty(),
        deserializer = ContentDto.serializer(),
        describe = "$owner/$repo/$path",
    )

    /** A directory listing. The same endpoint as [readFile], but the response is an array. */
    suspend fun listDirectory(
        owner: String,
        repo: String,
        path: String,
        ref: String?,
    ): List<ContentDto> = get(
        path = listOf("repos", owner, repo, "contents") +
            path.trim('/').split('/').filter { it.isNotEmpty() },
        query = ref?.let { mapOf("ref" to it) }.orEmpty(),
        deserializer = ListSerializer(ContentDto.serializer()),
        describe = "$owner/$repo/${path.ifBlank { "/" }}",
    )

    // ---- Issues -------------------------------------------------------------------------------

    suspend fun listIssues(
        owner: String,
        repo: String,
        state: String,
        labels: String?,
        limit: Int,
    ): List<IssueDto> = get(
        path = listOf("repos", owner, repo, "issues"),
        query = buildMap {
            put("state", state)
            put("per_page", limit.coerceIn(1, MAX_PAGE).toString())
            labels?.takeIf { it.isNotBlank() }?.let { put("labels", it) }
        },
        deserializer = ListSerializer(IssueDto.serializer()),
        describe = "issues in $owner/$repo",
    )

    suspend fun getIssue(owner: String, repo: String, number: Int): IssueDto = get(
        path = listOf("repos", owner, repo, "issues", number.toString()),
        deserializer = IssueDto.serializer(),
        describe = "$owner/$repo#$number",
    )

    suspend fun listIssueComments(
        owner: String,
        repo: String,
        number: Int,
        limit: Int,
    ): List<CommentDto> = get(
        path = listOf("repos", owner, repo, "issues", number.toString(), "comments"),
        query = mapOf("per_page" to limit.coerceIn(1, MAX_PAGE).toString()),
        deserializer = ListSerializer(CommentDto.serializer()),
        describe = "comments on $owner/$repo#$number",
    )

    suspend fun createIssue(
        owner: String,
        repo: String,
        body: CreateIssueRequestDto,
    ): IssueDto = post(
        path = listOf("repos", owner, repo, "issues"),
        payload = json.encodeToString(CreateIssueRequestDto.serializer(), body),
        deserializer = IssueDto.serializer(),
        describe = "$owner/$repo",
    )

    suspend fun comment(
        owner: String,
        repo: String,
        number: Int,
        body: String,
    ): CommentDto = post(
        path = listOf("repos", owner, repo, "issues", number.toString(), "comments"),
        payload = json.encodeToString(CommentRequestDto.serializer(), CommentRequestDto(body)),
        deserializer = CommentDto.serializer(),
        describe = "$owner/$repo#$number",
    )

    // ---- Pull requests ------------------------------------------------------------------------

    suspend fun listPulls(owner: String, repo: String, state: String, limit: Int): List<PullDto> =
        get(
            path = listOf("repos", owner, repo, "pulls"),
            query = mapOf(
                "state" to state,
                "per_page" to limit.coerceIn(1, MAX_PAGE).toString(),
                "sort" to "updated",
                "direction" to "desc",
            ),
            deserializer = ListSerializer(PullDto.serializer()),
            describe = "pull requests in $owner/$repo",
        )

    suspend fun getPull(owner: String, repo: String, number: Int): PullDto = get(
        path = listOf("repos", owner, repo, "pulls", number.toString()),
        deserializer = PullDto.serializer(),
        describe = "$owner/$repo#$number",
    )

    /** Per-file patches. Preferred over the raw diff: it is structured and already split up. */
    suspend fun listPullFiles(
        owner: String,
        repo: String,
        number: Int,
        limit: Int,
    ): List<PullFileDto> = get(
        path = listOf("repos", owner, repo, "pulls", number.toString(), "files"),
        query = mapOf("per_page" to limit.coerceIn(1, MAX_PAGE).toString()),
        deserializer = ListSerializer(PullFileDto.serializer()),
        describe = "files in $owner/$repo#$number",
    )

    suspend fun createReview(
        owner: String,
        repo: String,
        number: Int,
        body: CreateReviewRequestDto,
    ): String = withContext(Dispatchers.IO) {
        requireWrites()
        val request = buildRequest(
            url = url(listOf("repos", owner, repo, "pulls", number.toString(), "reviews")),
            payload = json.encodeToString(CreateReviewRequestDto.serializer(), body),
        )
        execute(request, "$owner/$repo#$number") { it }
    }

    // ---- Plumbing -----------------------------------------------------------------------------

    private suspend fun <T> get(
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        deserializer: DeserializationStrategy<T>,
        describe: String,
    ): T = withContext(Dispatchers.IO) {
        requireToken()
        val request = buildRequest(url(path, query), payload = null)
        execute(request, describe) { json.decodeFromString(deserializer, it) }
    }

    private suspend fun <T> post(
        path: List<String>,
        payload: String,
        deserializer: DeserializationStrategy<T>,
        describe: String,
    ): T = withContext(Dispatchers.IO) {
        requireWrites()
        val request = buildRequest(url(path), payload)
        execute(request, describe) { json.decodeFromString(deserializer, it) }
    }

    private fun requireToken() {
        if (credentials.token == null) throw GitHubException.MissingToken()
    }

    /** Every mutating call goes through here, so the gate cannot be forgotten on a new endpoint. */
    private fun requireWrites() {
        requireToken()
        if (!credentials.writesAllowed) throw GitHubException.WritesDisabled()
    }

    private fun url(path: List<String>, query: Map<String, String> = emptyMap()): HttpUrl =
        credentials.apiBaseUrl.toHttpUrl().newBuilder()
            .apply {
                path.filter { it.isNotEmpty() }.forEach(::addPathSegment)
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            .build()

    private fun buildRequest(url: HttpUrl, payload: String?): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .apply { payload?.let { post(it.toRequestBody(JSON_MEDIA_TYPE)) } }
            .build()

    private fun <T> execute(request: Request, describe: String, parse: (String) -> T): T {
        // A transport failure propagates as-is; the tool layer renders IOException sensibly.
        val response = httpClient.newCall(request).execute()
        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) throw errorFor(httpResponse, describe)
            val text = httpResponse.body.string()
            return try {
                parse(text)
            } catch (e: Exception) {
                throw GitHubException.Failed(httpResponse.code, "unexpected response shape")
                    .apply { initCause(e) }
            }
        }
    }

    private fun errorFor(response: Response, describe: String): GitHubException {
        val detail = runCatching {
            json.decodeFromString(ErrorResponseDto.serializer(), response.body.string()).message
        }.getOrNull()

        return when (response.code) {
            401 -> GitHubException.Unauthorized(detail)
            // GitHub returns 403 for both scope failures and rate limits; the header disambiguates.
            403, 429 -> if (response.header("X-RateLimit-Remaining") == "0") {
                GitHubException.RateLimited(response.header("X-RateLimit-Reset")?.toLongOrNull())
            } else {
                GitHubException.Forbidden(detail)
            }
            404 -> GitHubException.NotFound(describe)
            else -> GitHubException.Failed(response.code, detail)
        }
    }

    companion object {
        const val MAX_PAGE = 100
        private const val API_VERSION = "2022-11-28"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** GitHub returns file contents as base64 with hard-wrapped lines. */
        fun decodeContent(content: String): String =
            String(Base64.decode(content.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
    }
}
