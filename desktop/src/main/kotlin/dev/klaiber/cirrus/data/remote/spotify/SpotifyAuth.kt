package dev.klaiber.cirrus.data.remote.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** What Spotify hands back from the token endpoint. */
@Serializable
data class SpotifyTokens(
    @SerialName("access_token") val accessToken: String,
    /** Absent on a refresh: the existing one stays valid and must not be overwritten with null. */
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val scope: String = "",
) {
    fun expiresAt(now: Long = System.currentTimeMillis()): Long = now + expiresIn * 1000
}

/**
 * The authorization-code flow with PKCE, which is the only correct one for an app like this.
 *
 * The implicit grant would be simpler and is what most phone apps reached for until it was
 * deprecated; it hands the access token to the browser's redirect, where it lands in logs and
 * history, and it issues no refresh token, so the user re-authorises every hour. PKCE instead sends
 * a hash of a random secret up front and the secret itself at exchange time, which means an
 * intercepted redirect is worth nothing to whoever intercepted it.
 *
 * The [verifier] is generated per attempt and must survive the trip out to the browser and back.
 * It is deliberately *not* stored with the tokens: it is worthless after the exchange and
 * dangerous before it.
 */
@Singleton
class SpotifyAuth @Inject constructor(
    // Its own client: the Ollama one attaches the Ollama key to every request, and that
    // key must never reach a third party. `AppContainer` is where that is guaranteed.
    private val client: OkHttpClient,
    private val credentials: SpotifyCredentials,
    private val json: Json,
) {

    /** A fresh code verifier: 64 URL-safe characters from a cryptographic source. */
    fun newVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return bytes.base64Url()
    }

    /**
     * Where to send the browser.
     *
     * `show_dialog` is left off, so somebody who has already authorised Cirrus and is simply
     * reconnecting is bounced straight back rather than made to press Agree again.
     */
    fun authorizeUrl(verifier: String, state: String): String =
        "${credentials.accountsBaseUrl}/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", credentials.clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", SpotifyCredentials.REDIRECT_URI)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", verifier.sha256().base64Url())
            .addQueryParameter("state", state)
            .addQueryParameter("scope", SpotifyCredentials.SCOPES.joinToString(" "))
            .build()
            .toString()

    /** Trades the code from the redirect for a token pair. */
    suspend fun exchange(code: String, verifier: String): SpotifyTokens = post(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", SpotifyCredentials.REDIRECT_URI)
            .add("client_id", credentials.clientId)
            .add("code_verifier", verifier)
            .build(),
    )

    /** Mints a new access token from the stored refresh token. */
    suspend fun refresh(refreshToken: String): SpotifyTokens = post(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", credentials.clientId)
            .build(),
    )

    private suspend fun post(body: FormBody): SpotifyTokens = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${credentials.accountsBaseUrl}/api/token")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Spotify's errors here are genuinely informative — "invalid_grant" means the
                // refresh token was revoked, which needs a new sign-in rather than a retry — so the
                // description is carried through rather than flattened into a status code.
                throw SpotifyException.Auth(readAuthError(text, response.code))
            }
            runCatching { json.decodeFromString(SpotifyTokens.serializer(), text) }
                .getOrElse { throw SpotifyException.Auth("Spotify returned a token response that could not be read.") }
        }
    }

    private fun readAuthError(body: String, code: Int): String {
        val described = runCatching {
            json.parseToJsonElement(body)
        }.getOrNull()?.let { element ->
            val obj = element as? kotlinx.serialization.json.JsonObject
            val description = obj?.get("error_description")?.let { primitive ->
                (primitive as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            val error = obj?.get("error")?.let { primitive ->
                (primitive as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            description ?: error
        }
        return described ?: "Spotify refused the sign-in (HTTP $code)."
    }

    private fun String.sha256(): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.US_ASCII))

    /**
     * Base64url without padding, as RFC 7636 requires.
     *
     * `android.util.Base64` is avoided so this class stays testable on the JVM; the flags it would
     * need (`NO_WRAP or NO_PADDING or URL_SAFE`) are exactly the three substitutions below.
     */
    private fun ByteArray.base64Url(): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}
