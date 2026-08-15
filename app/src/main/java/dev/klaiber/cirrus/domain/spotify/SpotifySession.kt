package dev.klaiber.cirrus.domain.spotify

import dev.klaiber.cirrus.data.remote.spotify.SpotifyAuth
import dev.klaiber.cirrus.data.remote.spotify.SpotifyClient
import dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials
import dev.klaiber.cirrus.data.remote.spotify.SpotifyException
import dev.klaiber.cirrus.data.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the browser trip needs, handed to the screen that starts it. */
data class SpotifySignIn(val url: String, val verifier: String, val state: String)

/**
 * Owns "is there a usable Spotify token right now", and gets one when there is not.
 *
 * Separate from [SpotifyClient] because that class is a transport and refreshing is not transport:
 * it writes to settings, it has to happen exactly once when four tools discover an expired token at
 * the same moment, and it has to know the difference between a token that has aged out (refresh it)
 * and one that has been revoked (ask the user to sign in again). A [Mutex] rather than a flag,
 * because the four callers are on different coroutines and the loser of the race must wait for the
 * winner's token rather than start a second refresh with the same — now spent — refresh token.
 */
@Singleton
class SpotifySession @Inject constructor(
    private val auth: SpotifyAuth,
    private val client: SpotifyClient,
    private val credentials: SpotifyCredentials,
    private val settings: SettingsRepository,
) {

    private val refreshLock = Mutex()

    val isConnected: Boolean get() = credentials.isConnected

    val canSignIn: Boolean get() = credentials.canSignIn

    /**
     * Starts a sign-in, and remembers what the redirect will need.
     *
     * The verifier is persisted rather than held in memory because the browser is a different app:
     * Android is free to kill Cirrus while somebody is reading Spotify's consent screen, and a
     * verifier that died with the process turns the return trip into an error nobody can explain.
     */
    suspend fun beginSignIn(): SpotifySignIn {
        val verifier = auth.newVerifier()
        val state = randomState()
        settings.setSpotifyPendingAuth(verifier, state)
        return SpotifySignIn(auth.authorizeUrl(verifier, state), verifier, state)
    }

    /**
     * Finishes the trip. Returns the account's display name, or fails with something sayable.
     *
     * The state check is not ceremony: without it, anything able to fire an intent at Cirrus could
     * hand it an authorization code of somebody else's choosing, and Cirrus would dutifully
     * exchange it and connect the user's app to an account they have never seen.
     */
    suspend fun completeSignIn(code: String, state: String): Result<String> = runCatching {
        val pending = settings.consumeSpotifyPendingAuth()
            ?: throw SpotifyException.Auth("That sign-in did not start here. Try connecting again.")
        if (pending.state != state) {
            throw SpotifyException.Auth("That sign-in did not match the one Cirrus started.")
        }

        val tokens = auth.exchange(code, pending.verifier)
        settings.setSpotifyTokens(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAt(),
        )
        // Applied straight to the snapshot as well as through the store, for the same reason saving
        // the Ollama key does: the mirror runs on the application scope, and the profile call below
        // is the very next thing that happens.
        credentials.update(
            clientId = credentials.clientId,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: credentials.refreshToken,
            expiresAt = tokens.expiresAt(),
            writesAllowed = credentials.writesAllowed,
        )

        val profile = client.profile()
        val name = profile.displayName?.takeIf { it.isNotBlank() } ?: profile.id
        settings.setSpotifyTokens(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAt(),
            accountName = name,
            premium = profile.product.equals("premium", ignoreCase = true),
        )
        name
    }

    suspend fun signOut() {
        settings.clearSpotifyAccount()
        credentials.update(
            clientId = credentials.clientId,
            accessToken = null,
            refreshToken = null,
            expiresAt = 0L,
            writesAllowed = credentials.writesAllowed,
        )
    }

    /**
     * Runs [block] with a token that is fresh, and once more if Spotify disagrees.
     *
     * The retry exists for the case the expiry check cannot catch: a token revoked from Spotify's
     * own account page is not expired, it is simply no longer accepted, and the only way to find
     * out is to be told 401.
     */
    suspend fun <T> withToken(block: suspend () -> T): T {
        ensureFresh()
        return try {
            block()
        } catch (unauthorized: SpotifyException.Unauthorized) {
            ensureFresh(force = true)
            block()
        }
    }

    private suspend fun ensureFresh(force: Boolean = false) {
        if (!force && !credentials.needsRefresh) return
        refreshLock.withLock {
            // Re-checked inside the lock: whoever was ahead in the queue has already fixed it, and
            // refreshing again would spend a refresh token to replace a token that is a second old.
            if (!force && !credentials.needsRefresh) return
            val refreshToken = credentials.refreshToken
                ?: throw SpotifyException.Auth("Spotify is not connected. Connect it in Settings → Music.")

            val tokens = auth.refresh(refreshToken)
            settings.setSpotifyTokens(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAt = tokens.expiresAt(),
            )
            credentials.update(
                clientId = credentials.clientId,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: refreshToken,
                expiresAt = tokens.expiresAt(),
                writesAllowed = credentials.writesAllowed,
            )
        }
    }

    private fun randomState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
