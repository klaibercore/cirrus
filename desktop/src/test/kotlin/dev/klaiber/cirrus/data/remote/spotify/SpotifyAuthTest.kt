package dev.klaiber.cirrus.data.remote.spotify

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * The half of OAuth that is worth testing without a server: the URL that goes out.
 *
 * PKCE is one equation — the challenge must be the base64url-unpadded SHA-256 of the verifier — and
 * every way of getting it slightly wrong produces the same symptom: Spotify accepts the redirect and
 * then rejects the exchange with `invalid_grant`, an error that says nothing about which of the two
 * halves was malformed. Standard base64 rather than base64url, or padding left on, are the two
 * classic ways to spend an afternoon.
 */
class SpotifyAuthTest {

    private lateinit var credentials: SpotifyCredentials
    private lateinit var auth: SpotifyAuth

    @Before
    fun setUp() {
        credentials = SpotifyCredentials()
        credentials.update(
            clientId = "test-client-id",
            accessToken = null,
            refreshToken = null,
            expiresAt = 0L,
            writesAllowed = false,
        )
        auth = SpotifyAuth(OkHttpClient(), credentials, Json { ignoreUnknownKeys = true })
    }

    @Test
    fun `the challenge is the base64url sha256 of the verifier, unpadded`() {
        val verifier = auth.newVerifier()
        val url = auth.authorizeUrl(verifier, state = "state123").toHttpUrl()

        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(expected, url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
    }

    @Test
    fun `the challenge is url-safe and carries no padding`() {
        val url = auth.authorizeUrl(auth.newVerifier(), state = "s").toHttpUrl()
        val challenge = url.queryParameter("code_challenge")
        assertNotNull(challenge)
        checkNotNull(challenge)

        assertFalse("+ and / would be mangled in a URL", challenge.any { it == '+' || it == '/' })
        assertFalse("padding is not allowed by RFC 7636", challenge.contains("="))
    }

    @Test
    fun `verifiers are long enough and never repeat`() {
        val verifiers = List(50) { auth.newVerifier() }

        // RFC 7636 puts the floor at 43 characters; anything shorter is guessable.
        assertTrue(verifiers.all { it.length in 43..128 })
        assertEquals("a repeated verifier is a broken random source", 50, verifiers.toSet().size)
    }

    @Test
    fun `the authorize url asks for a code, and for exactly the declared scopes`() {
        val url = auth.authorizeUrl("verifier", state = "state123").toHttpUrl()

        assertEquals("accounts.spotify.com", url.host)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("test-client-id", url.queryParameter("client_id"))
        assertEquals("state123", url.queryParameter("state"))
        assertEquals(SpotifyCredentials.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals(
            SpotifyCredentials.SCOPES.joinToString(" "),
            url.queryParameter("scope"),
        )
    }

    /**
     * Scope creep is a security regression that looks like a feature. Every entry here is something
     * the consent screen tells the user Cirrus wants, so the list is asserted rather than trusted.
     */
    @Test
    fun `no scope is requested that no tool uses`() {
        assertEquals(
            listOf(
                "user-read-playback-state",
                "user-modify-playback-state",
                "user-read-currently-playing",
                "playlist-read-private",
                "playlist-modify-private",
                "playlist-modify-public",
                "user-library-read",
                "user-top-read",
                "user-read-private",
            ),
            SpotifyCredentials.SCOPES,
        )
        assertFalse(
            "Cirrus never reads the user's email",
            SpotifyCredentials.SCOPES.any { it.contains("email") },
        )
    }

    // ---- The expiry logic, which decides when a refresh happens ----------------------------

    @Test
    fun `a token with no expiry counts as needing a refresh`() {
        assertTrue(SpotifyCredentials().needsRefresh)
    }

    @Test
    fun `a token about to expire is refreshed before it is used`() {
        credentials.update(
            clientId = "id",
            accessToken = "token",
            refreshToken = "refresh",
            // Comfortably in the future by a naive check, and inside the skew that exists because
            // a token with seconds left expires mid-flight and fails at random.
            expiresAt = System.currentTimeMillis() + 5_000,
            writesAllowed = false,
        )
        assertTrue(credentials.needsRefresh)
    }

    @Test
    fun `a fresh token is left alone`() {
        credentials.update(
            clientId = "id",
            accessToken = "token",
            refreshToken = "refresh",
            expiresAt = System.currentTimeMillis() + 30 * 60_000,
            writesAllowed = false,
        )
        assertFalse(credentials.needsRefresh)
        assertTrue(credentials.isConnected)
    }
}
