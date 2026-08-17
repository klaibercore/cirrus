package dev.klaiber.cirrus.data.remote.spotify

import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyCreatedPlaylist
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyDevices
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyPage
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyPlaybackState
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyPlaylist
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyPlaylistItem
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyProfile
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifySavedTrack
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifySearchResponse
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Failures worth telling apart, because each one has a different thing to say to the user. */
sealed class SpotifyException(message: String) : IOException(message) {

    /** Sign-in itself failed. Usually a revoked refresh token, which needs a new sign-in. */
    class Auth(detail: String) : SpotifyException(detail)

    /** The access token was rejected. Recoverable by refreshing, once. */
    class Unauthorized : SpotifyException("Spotify rejected the access token.")

    /**
     * Almost always "this is not a Premium account". Spotify says only "Player command failed:
     * Premium required", so the message is carried through rather than guessed at.
     */
    class Forbidden(detail: String) : SpotifyException(detail)

    /** Playback was asked for with nothing to play it on. The fix is a device, not a retry. */
    class NoActiveDevice :
        SpotifyException("No Spotify device is active — open Spotify on a device first.")

    class RateLimited(val retryAfterSeconds: Int) :
        SpotifyException("Spotify is rate-limiting; retry in ${retryAfterSeconds}s.")

    class Remote(val code: Int, detail: String) : SpotifyException("Spotify error $code: $detail")
}

/**
 * The Spotify Web API, as a transport and nothing more.
 *
 * Like [dev.klaiber.cirrus.data.remote.github.GitHubClient], this has its own OkHttp instance: the
 * Ollama client attaches the Ollama API key to every request it makes, and that key has no business
 * reaching a music service.
 *
 * It deliberately does *not* know how to refresh a token. A 401 comes back as
 * [SpotifyException.Unauthorized] and `SpotifySession` decides what to do about it — which keeps
 * the persistence of new tokens out of a class whose only job is HTTP, and keeps the refresh from
 * happening three times in parallel when three tools fire at once.
 */
@Singleton
class SpotifyClient @Inject constructor(
    // Its own client: the Ollama one attaches the Ollama key to every request, and that
    // key must never reach a third party. `AppContainer` is where that is guaranteed.
    private val http: OkHttpClient,
    private val json: Json,
    private val credentials: SpotifyCredentials,
) {

    // ---- Reading ------------------------------------------------------------------------------

    suspend fun profile(): SpotifyProfile = get("me", SpotifyProfile.serializer())

    suspend fun search(query: String, types: List<String>, limit: Int): SpotifySearchResponse =
        get(
            path = "search",
            serializer = SpotifySearchResponse.serializer(),
            query = mapOf(
                "q" to query,
                "type" to types.joinToString(","),
                "limit" to limit.toString(),
            ),
        )

    /**
     * The full player state, or null when nothing is playing.
     *
     * Spotify answers 204 with an empty body for "nothing is playing", which is a perfectly
     * sensible thing for it to do and a completely unparseable thing to hand a JSON decoder.
     */
    suspend fun playbackState(): SpotifyPlaybackState? =
        getOrNull("me/player", SpotifyPlaybackState.serializer())

    suspend fun devices(): SpotifyDevices = get("me/player/devices", SpotifyDevices.serializer())

    suspend fun playlists(limit: Int): SpotifyPage<SpotifyPlaylist> = get(
        path = "me/playlists",
        serializer = SpotifyPage.serializer(SpotifyPlaylist.serializer()),
        query = mapOf("limit" to limit.toString()),
    )

    suspend fun playlistTracks(playlistId: String, limit: Int): SpotifyPage<SpotifyPlaylistItem> =
        get(
            path = "playlists/$playlistId/tracks",
            serializer = SpotifyPage.serializer(SpotifyPlaylistItem.serializer()),
            query = mapOf("limit" to limit.toString()),
        )

    suspend fun savedTracks(limit: Int): SpotifyPage<SpotifySavedTrack> = get(
        path = "me/tracks",
        serializer = SpotifyPage.serializer(SpotifySavedTrack.serializer()),
        query = mapOf("limit" to limit.toString()),
    )

    /** [type] is "artists" or "tracks"; the shapes differ, so callers pick the serializer. */
    suspend fun <T> topItems(type: String, limit: Int, serializer: KSerializer<T>): SpotifyPage<T> =
        get(
            path = "me/top/$type",
            serializer = SpotifyPage.serializer(serializer),
            query = mapOf("limit" to limit.toString(), "time_range" to "medium_term"),
        )

    // ---- Playback ------------------------------------------------------------------------------

    /**
     * Starts or resumes playback.
     *
     * A body with neither field is not the same as no body at all: an empty object asks Spotify to
     * start something of its own choosing, whereas no body resumes what was paused. Resuming is
     * what "play" means when nothing was named, so the body is dropped in that case.
     */
    suspend fun play(contextUri: String?, trackUris: List<String>, deviceId: String?) {
        val body = when {
            contextUri != null -> buildJsonObject { put("context_uri", contextUri) }.toString()
            trackUris.isNotEmpty() -> buildJsonObject {
                putJsonArray("uris") { trackUris.forEach { add(JsonPrimitive(it)) } }
            }.toString()

            else -> null
        }
        send("PUT", "me/player/play", deviceQuery(deviceId), body)
    }

    suspend fun pause(deviceId: String?) = send("PUT", "me/player/pause", deviceQuery(deviceId))

    suspend fun next(deviceId: String?) = send("POST", "me/player/next", deviceQuery(deviceId))

    suspend fun previous(deviceId: String?) =
        send("POST", "me/player/previous", deviceQuery(deviceId))

    suspend fun setVolume(percent: Int, deviceId: String?) = send(
        "PUT",
        "me/player/volume",
        deviceQuery(deviceId) + ("volume_percent" to percent.coerceIn(0, 100).toString()),
    )

    suspend fun enqueue(uri: String, deviceId: String?) =
        send("POST", "me/player/queue", deviceQuery(deviceId) + ("uri" to uri))

    suspend fun setShuffle(on: Boolean, deviceId: String?) =
        send("PUT", "me/player/shuffle", deviceQuery(deviceId) + ("state" to on.toString()))

    /** Moves playback to another device. `play: true` keeps it playing across the move. */
    suspend fun transfer(deviceId: String, keepPlaying: Boolean) = send(
        method = "PUT",
        path = "me/player",
        body = """{"device_ids":["$deviceId"],"play":$keepPlaying}""",
    )

    // ---- Playlists -----------------------------------------------------------------------------

    suspend fun createPlaylist(
        userId: String,
        name: String,
        description: String?,
        public: Boolean,
    ): SpotifyCreatedPlaylist {
        val body = buildJsonObject {
            put("name", name)
            if (description != null) put("description", description)
            put("public", public)
        }
        return post("users/$userId/playlists", body.toString(), SpotifyCreatedPlaylist.serializer())
    }

    suspend fun addToPlaylist(playlistId: String, uris: List<String>) {
        val body = buildJsonObject {
            putJsonArray("uris") { uris.forEach { add(JsonPrimitive(it)) } }
        }
        send("POST", "playlists/$playlistId/tracks", body = body.toString())
    }

    suspend fun removeFromPlaylist(playlistId: String, uris: List<String>) {
        val body = buildJsonObject {
            putJsonArray("tracks") {
                uris.forEach { uri -> add(buildJsonObject { put("uri", uri) }) }
            }
        }
        send("DELETE", "playlists/$playlistId/tracks", body = body.toString())
    }

    // ---- Plumbing ------------------------------------------------------------------------------

    private fun deviceQuery(deviceId: String?): Map<String, String> =
        deviceId?.let { mapOf("device_id" to it) } ?: emptyMap()

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap(),
    ): T = getOrNull(path, serializer, query)
        ?: throw SpotifyException.Remote(204, "Spotify returned nothing for $path")

    private suspend fun <T> getOrNull(
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap(),
    ): T? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path, query)).get().build()
        http.newCall(request).execute().use { response ->
            val text = body(response, path)
            if (text.isNullOrBlank()) return@use null
            runCatching { json.decodeFromString(serializer, text) }.getOrElse {
                throw SpotifyException.Remote(response.code, "unreadable response from $path")
            }
        }
    }

    private suspend fun <T> post(path: String, body: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url(path, emptyMap()))
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(request).execute().use { response ->
                val text = body(response, path).orEmpty()
                runCatching { json.decodeFromString(serializer, text) }.getOrElse {
                    throw SpotifyException.Remote(response.code, "unreadable response from $path")
                }
            }
        }

    /** For the calls whose whole answer is the status code, which is most of the player API. */
    private suspend fun send(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url(path, query))
                .method(method, body?.toRequestBody(JSON_MEDIA) ?: EMPTY_BODY.takeIf { method != "GET" })
                .build()
            http.newCall(request).execute().use { response -> body(response, path) }
        }
    }

    private fun url(path: String, query: Map<String, String>) =
        "${credentials.apiBaseUrl}/$path".toHttpUrl().newBuilder()
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

    /**
     * Maps the response onto the exception the caller can act on, or returns its body.
     *
     * The three that get their own type are the three with a different remedy: 401 means refresh
     * the token, 403 on the player means the account is not Premium and the on-device controls are
     * the way through, and 404 on the player means there is nothing to play on.
     */
    private fun body(response: Response, path: String): String? {
        val text = response.body?.string()
        if (response.isSuccessful) return text
        throw when (response.code) {
            401 -> SpotifyException.Unauthorized()
            403 -> SpotifyException.Forbidden(
                describe(text) ?: "Spotify refused that. Playback control needs Spotify Premium.",
            )

            404 -> if (path.startsWith("me/player")) {
                SpotifyException.NoActiveDevice()
            } else {
                SpotifyException.Remote(404, describe(text) ?: "not found")
            }

            429 -> SpotifyException.RateLimited(
                response.header("Retry-After")?.toIntOrNull() ?: 5,
            )

            else -> SpotifyException.Remote(response.code, describe(text) ?: "no detail given")
        }
    }

    /** Spotify's errors nest under `error.message`; anything else is passed through as-is. */
    private fun describe(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            val error = root?.get("error")
            when (error) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.content
                is JsonPrimitive -> error.content
                else -> null
            }
        }.getOrNull() ?: body.take(200)
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = "".toRequestBody(null)
    }
}
