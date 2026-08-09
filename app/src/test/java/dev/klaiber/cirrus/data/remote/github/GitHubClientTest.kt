package dev.klaiber.cirrus.data.remote.github

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubClientTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: GitHubCredentials
    private lateinit var client: GitHubClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = GitHubCredentials().apply {
            apiBaseUrl = server.url("/").toString().trimEnd('/')
            update(token = "test-token", writesAllowed = false)
        }
        client = GitHubClient(OkHttpClient(), json, credentials)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `listRepos asks for everything the token can see`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""[{"full_name":"me/app","private":true,"default_branch":"main"}]""")
                .build()
        )

        val repos = client.listRepos(30)
        assertEquals(1, repos.size)
        assertEquals("me/app", repos[0].fullName)
        assertTrue(repos[0].private)

        val request = server.takeRequest()
        assertEquals("/user/repos", request.url.encodedPath)
        // Private repositories only appear when affiliation is widened past what you own.
        assertEquals(
            "owner,collaborator,organization_member",
            request.url.queryParameter("affiliation"),
        )
    }

    @Test
    fun `requests carry the token and the api version`() = runTest {
        server.enqueue(MockResponse.Builder().body("[]").build())
        client.listRepos(5)

        val request = server.takeRequest()
        assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
        assertEquals("application/vnd.github+json", request.headers["Accept"])
    }

    @Test
    fun `readFile builds a nested content path`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"path":"app/src/Main.kt","content":"aGk=","encoding":"base64"}""")
                .build()
        )

        client.readFile("me", "app", "app/src/Main.kt", ref = "develop")

        val request = server.takeRequest()
        assertEquals("/repos/me/app/contents/app/src/Main.kt", request.url.encodedPath)
        assertEquals("develop", request.url.queryParameter("ref"))
    }

    @Test
    fun `a missing token fails before any request is made`() = runTest {
        credentials.update(token = null, writesAllowed = false)
        val error = runCatching { client.listRepos(5) }.exceptionOrNull()

        assertTrue(error is GitHubException.MissingToken)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `writes are refused while the write gate is closed`() = runTest {
        val error = runCatching {
            client.comment("me", "app", 1, "hello")
        }.exceptionOrNull()

        assertTrue(error is GitHubException.WritesDisabled)
        // The gate must close before the network, not after.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `writes go through once allowed`() = runTest {
        credentials.update(token = "test-token", writesAllowed = true)
        server.enqueue(
            MockResponse.Builder()
                .body("""{"body":"hello","html_url":"https://github.com/me/app/issues/1"}""")
                .build()
        )

        val comment = client.comment("me", "app", 1, "hello")
        assertEquals("https://github.com/me/app/issues/1", comment.htmlUrl)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/me/app/issues/1/comments", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("\"body\":\"hello\""))
    }

    @Test
    fun `401 maps to Unauthorized`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(401).body("""{"message":"Bad credentials"}""").build()
        )
        val error = runCatching { client.listRepos(5) }.exceptionOrNull()
        assertTrue(error is GitHubException.Unauthorized)
        assertTrue(error!!.message!!.contains("Bad credentials"))
    }

    @Test
    fun `404 names what was not found`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
        val error = runCatching { client.getRepo("me", "ghost") }.exceptionOrNull()
        assertTrue(error is GitHubException.NotFound)
        assertTrue(error!!.message!!.contains("me/ghost"))
    }

    @Test
    fun `403 with no remaining quota is a rate limit, not a scope problem`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader("X-RateLimit-Reset", "1700000000")
                .body("""{"message":"API rate limit exceeded"}""")
                .build()
        )
        val error = runCatching { client.listRepos(5) }.exceptionOrNull()
        assertTrue(error is GitHubException.RateLimited)
        assertEquals(1700000000L, (error as GitHubException.RateLimited).resetAtEpochSeconds)
    }

    @Test
    fun `403 with quota remaining is a scope problem`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("X-RateLimit-Remaining", "4999")
                .body("""{"message":"Resource not accessible by personal access token"}""")
                .build()
        )
        val error = runCatching { client.listRepos(5) }.exceptionOrNull()
        assertTrue(error is GitHubException.Forbidden)
    }

    @Test
    fun `per-page is clamped to the api maximum`() = runTest {
        server.enqueue(MockResponse.Builder().body("[]").build())
        client.listRepos(5_000)
        assertEquals("100", server.takeRequest().url.queryParameter("per_page"))
    }
}
