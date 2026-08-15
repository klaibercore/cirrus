package dev.klaiber.cirrus.data.remote.spotify

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the Spotify session that OkHttp needs synchronously.
 *
 * Mirrors [dev.klaiber.cirrus.data.remote.github.GitHubCredentials], with one thing that holder
 * does not have: an expiry. A Spotify access token lasts an hour, so "is there a token?" is not the
 * same question as "can it be used?", and a client that only asked the first one would spend the
 * fifty-ninth minute of every hour failing.
 *
 * There is no client *secret* anywhere in this file, and that is deliberate rather than an
 * omission. The flow is PKCE, which exists precisely because an app the user can unzip cannot keep
 * a secret — anything shipped in the APK is public, whatever the field is called.
 */
@Singleton
class SpotifyCredentials @Inject constructor() {

    /** The user's own registered application. Public by design under PKCE. */
    @Volatile
    var clientId: String = ""
        private set

    @Volatile
    var accessToken: String? = null
        private set

    /**
     * The long-lived half, and the one worth protecting: it mints access tokens until the user
     * revokes it. Stored encrypted, and never sent anywhere but accounts.spotify.com.
     */
    @Volatile
    var refreshToken: String? = null
        private set

    /** Epoch millis. Zero means "no token", which reads as expired without a special case. */
    @Volatile
    var expiresAt: Long = 0L
        private set

    @Volatile
    var writesAllowed: Boolean = false
        private set

    /** Constant in production; pointed at a mock server by tests, as GitHub's is. */
    @Volatile
    var apiBaseUrl: String = API_BASE_URL

    @Volatile
    var accountsBaseUrl: String = ACCOUNTS_BASE_URL

    fun update(
        clientId: String,
        accessToken: String?,
        refreshToken: String?,
        expiresAt: Long,
        writesAllowed: Boolean,
    ) {
        this.clientId = clientId.trim()
        this.accessToken = accessToken?.takeIf { it.isNotBlank() }
        this.refreshToken = refreshToken?.takeIf { it.isNotBlank() }
        this.expiresAt = expiresAt
        this.writesAllowed = writesAllowed
    }

    /** A client ID is configured, so a sign-in is at least possible. */
    val canSignIn: Boolean get() = clientId.isNotBlank()

    /** Somebody has signed in, whether or not the access token is currently fresh. */
    val isConnected: Boolean get() = refreshToken != null

    /**
     * True when the access token is missing or close enough to expiry not to be worth using.
     *
     * The skew matters: a token with four seconds left passes a naive check, and then expires
     * somewhere between the request leaving and the response arriving, which surfaces as a random
     * failure roughly once an hour.
     */
    val needsRefresh: Boolean
        get() = accessToken == null || System.currentTimeMillis() >= expiresAt - EXPIRY_SKEW_MS

    companion object {
        const val API_BASE_URL = "https://api.spotify.com/v1"
        const val ACCOUNTS_BASE_URL = "https://accounts.spotify.com"

        /**
         * What Cirrus asks for, and nothing beyond it.
         *
         * No `user-read-email`, no `user-follow-*`, no `streaming`: every extra scope is something
         * the consent screen tells the user Cirrus wants, and a list that asks for more than it
         * uses is how people learn not to read consent screens.
         */
        val SCOPES = listOf(
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "playlist-read-private",
            "playlist-modify-private",
            "playlist-modify-public",
            "user-library-read",
            "user-top-read",
            // The one non-obvious entry: it is how the product tier is read, which is how a 403 on
            // playback can be explained as "this account is not Premium" rather than reported as a
            // failure of the app.
            "user-read-private",
        )

        /** Where Spotify sends the browser back to. Must match the app registration exactly. */
        const val REDIRECT_URI = "cirrus://spotify/callback"

        private const val EXPIRY_SKEW_MS = 60_000L
    }
}
