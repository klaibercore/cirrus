package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one tool that changes a repository, driven end to end against a mock GitHub.
 *
 * Assertions are on the request GitHub would actually receive — method, path and body — because
 * the failure that matters here is committing the wrong bytes to the wrong place.
 */
class WriteFileToolTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: GitHubCredentials
    private lateinit var tool: WriteFileTool
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = GitHubCredentials().apply {
            apiBaseUrl = server.url("/").toString().trimEnd('/')
            update(token = "test-token", writesAllowed = true)
        }
        tool = WriteFileTool(GitHubClient(OkHttpClient(), json, credentials))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    private fun result(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun enqueueNotFound() {
        server.enqueue(
            MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build(),
        )
    }

    private fun enqueueCommit() {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """{"content":{"path":"docs/NOTES.md","sha":"newblob",
                       |"html_url":"https://github.com/me/app/blob/main/docs/NOTES.md"},
                       |"commit":{"sha":"c0ffee","html_url":"https://github.com/me/app/commit/c0ffee"}}"""
                        .trimMargin().replace("\n", ""),
                )
                .build(),
        )
    }

    @Test
    fun `creates a new file when none exists`() = runTest {
        enqueueNotFound()
        enqueueCommit()

        val output = result(
            tool.execute(
                args(
                    "repo" to "me/app",
                    "path" to "docs/NOTES.md",
                    "content" to "hello",
                    "message" to "docs: add notes",
                ),
            ),
        )

        assertTrue(output["committed"]!!.jsonPrimitive.boolean)
        assertEquals(false, output["updated"]!!.jsonPrimitive.boolean)
        assertEquals("c0ffee", output.str("commit"))

        server.takeRequest() // the existence check
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/repos/me/app/contents/docs/NOTES.md", put.url.encodedPath)

        val body = result(put.body!!.utf8())
        // "hello" base64-encoded. A stubbed encoder would leave this empty.
        assertEquals("aGVsbG8=", body.str("content"))
        assertEquals("docs: add notes", body.str("message"))
        // No sha at all, which is how GitHub is told the file must not already exist.
        assertNull(body["sha"])
    }

    @Test
    fun `updates an existing file by looking up its blob sha`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"path":"docs/NOTES.md","sha":"oldblob","content":"aGk=","encoding":"base64"}""")
                .build(),
        )
        enqueueCommit()

        val output = result(
            tool.execute(
                args(
                    "repo" to "me/app",
                    "path" to "docs/NOTES.md",
                    "content" to "hello",
                    "message" to "docs: rewrite notes",
                ),
            ),
        )

        assertEquals(true, output["updated"]!!.jsonPrimitive.boolean)

        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)

        val body = result(server.takeRequest().body!!.utf8())
        assertEquals("oldblob", body.str("sha"))
    }

    @Test
    fun `an explicit sha skips the lookup`() = runTest {
        enqueueCommit()

        tool.execute(
            args(
                "repo" to "me/app",
                "path" to "docs/NOTES.md",
                "content" to "hello",
                "message" to "docs: rewrite",
                "sha" to "known",
            ),
        )

        // One request only: the caller already knew the sha, so no round trip is spent on it.
        assertEquals(1, server.requestCount)
        val put = server.takeRequest()
        assertEquals("known", result(put.body!!.utf8()).str("sha"))
    }

    @Test
    fun `the branch is forwarded to both the lookup and the commit`() = runTest {
        enqueueNotFound()
        enqueueCommit()

        tool.execute(
            args(
                "repo" to "me/app",
                "path" to "NOTES.md",
                "content" to "x",
                "message" to "chore: note",
                "branch" to "develop",
            ),
        )

        assertEquals("develop", server.takeRequest().url.queryParameter("ref"))
        assertEquals("develop", result(server.takeRequest().body!!.utf8()).str("branch"))
    }

    @Test
    fun `writes disabled is reported without committing anything`() = runTest {
        credentials.update(token = "test-token", writesAllowed = false)
        enqueueNotFound()

        val output = result(
            tool.execute(
                args(
                    "repo" to "me/app",
                    "path" to "NOTES.md",
                    "content" to "x",
                    "message" to "chore: note",
                ),
            ),
        )

        assertTrue(output.str("error")!!.contains("Write actions are turned off"))
        // The existence check is a read and goes out; the commit itself never does.
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `a rate limit is surfaced as a rate limit`() = runTest {
        enqueueNotFound()
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader("X-RateLimit-Reset", "1700000000")
                .body("""{"message":"API rate limit exceeded"}""")
                .build(),
        )

        val output = result(
            tool.execute(
                args(
                    "repo" to "me/app",
                    "path" to "NOTES.md",
                    "content" to "x",
                    "message" to "chore: note",
                ),
            ),
        )

        assertTrue(output.str("error")!!.contains("rate limit"))
    }

    @Test
    fun `a missing scope is surfaced as a permissions problem`() = runTest {
        enqueueNotFound()
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body("""{"message":"Resource not accessible by personal access token"}""")
                .build(),
        )

        val output = result(
            tool.execute(
                args(
                    "repo" to "me/app",
                    "path" to "NOTES.md",
                    "content" to "x",
                    "message" to "chore: note",
                ),
            ),
        )

        assertTrue(output.str("error")!!.contains("not accessible by personal access token"))
    }

    @Test
    fun `a 422 tells the model to re-read rather than repeating unprocessable entity`() = runTest {
        enqueueNotFound()
        server.enqueue(
            MockResponse.Builder()
                .code(422)
                .body("""{"message":"sha wasn't supplied"}""")
                .build(),
        )

        val output = result(
            tool.execute(
                args(
                    "repo" to "me/app",
                    "path" to "NOTES.md",
                    "content" to "x",
                    "message" to "chore: note",
                ),
            ),
        )

        val error = output.str("error")!!
        assertTrue(error.contains("Read it again"))
        assertTrue(error.contains("me/app/NOTES.md"))
    }

    @Test
    fun `missing arguments are reported without any request`() = runTest {
        val output = result(tool.execute(args("repo" to "me/app", "path" to "NOTES.md")))

        assertTrue(output.str("error")!!.contains("content"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a malformed repo argument is rejected`() = runTest {
        val output = result(
            tool.execute(
                args(
                    "repo" to "not-a-repo",
                    "path" to "NOTES.md",
                    "content" to "x",
                    "message" to "chore: note",
                ),
            ),
        )

        assertTrue(output.str("error")!!.contains("owner/name"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the tool declares itself as writing`() {
        assertTrue(tool.writes)
        // The model reads this when deciding whether to call it, so the warning has to be in it.
        assertTrue(tool.definition.toString().contains("WRITES"))
    }
}
