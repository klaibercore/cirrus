package dev.klaiber.cirrus.domain.tools.spotify

import dev.klaiber.cirrus.data.remote.spotify.SpotifyClient
import dev.klaiber.cirrus.data.remote.spotify.SpotifyException
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyArtist
import dev.klaiber.cirrus.data.remote.spotify.dto.SpotifyTrack
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.spotify.SpotifySession
import dev.klaiber.cirrus.domain.tools.CirrusTool
import dev.klaiber.cirrus.domain.tools.github.enumParam
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.int
import dev.klaiber.cirrus.domain.tools.github.intParam
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.tools.github.stringParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify, as five tools rather than fifteen.
 *
 * The Web API has an endpoint per verb — play, pause, next, previous, volume, queue, shuffle,
 * transfer — and wrapping each one in its own tool would put eight nearly identical schemas in
 * front of the model on every single turn. They collapse into one `spotify_playback` with an
 * action, which costs one enum and saves most of a page of context. The same argument folds
 * playlists, saved tracks and top artists into one `spotify_library`.
 *
 * The read/write line falls where [CirrusTool.writes] defines it. Creating a playlist is a write:
 * there is no call that uncreates it. Pausing is not: the next call unpauses. That distinction is
 * why playback control is usable with the write switch off, which matters, because "pause the
 * music" is the single most likely thing anyone will ask this app to do.
 */
abstract class SpotifyTool(
    protected val session: SpotifySession,
    protected val client: SpotifyClient,
) : CirrusTool {

    final override suspend fun execute(arguments: JsonObject): String = try {
        session.withToken { run(arguments) }
    } catch (auth: SpotifyException.Auth) {
        errorJson("${auth.message} Ask the user to reconnect at Settings → Music → Spotify.")
    } catch (forbidden: SpotifyException.Forbidden) {
        // The single most common failure, and the one where a bare error is least useful: the
        // account is not Premium, and there is a way through that does not involve one.
        errorJson(
            "${forbidden.message} Playback control is the only part of Spotify that needs " +
                "Premium: search, the library and what-is-playing all work on a free account. " +
                "Say that rather than reporting a failure — and offer to open the track in the " +
                "Spotify app instead, which needs no subscription.",
        )
    } catch (device: SpotifyException.NoActiveDevice) {
        errorJson(
            "${device.message} Call spotify_library with kind \"devices\" to see what is " +
                "available, then use spotify_playback with a device_id. If nothing is listed, " +
                "Spotify is not open anywhere — say so rather than retrying.",
        )
    } catch (limited: SpotifyException.RateLimited) {
        errorJson(limited.message ?: "Spotify is rate-limiting.")
    } catch (spotify: SpotifyException) {
        errorJson(spotify.message ?: "The Spotify request failed.")
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        errorJson(error.message ?: "Could not reach Spotify.")
    }

    protected abstract suspend fun run(arguments: JsonObject): String
}

/** Finding something to play, or to talk about. */
@Singleton
class SpotifySearchTool @Inject constructor(
    session: SpotifySession,
    client: SpotifyClient,
) : SpotifyTool(session, client) {

    override val name: String = "spotify_search"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Search Spotify's catalogue for tracks, albums, artists or playlists. The " +
            "results carry a `uri` for each item, which is what spotify_playback and " +
            "spotify_playlist_edit take — always search first rather than guessing a URI, because " +
            "a guessed one either fails or plays the wrong thing.",
        required = listOf("query"),
    ) {
        stringParam("query", "What to look for. Spotify's own field syntax works: artist:, album:, year:.")
        enumParam(
            "type",
            "What kind of thing to find. Defaults to tracks.",
            listOf("track", "album", "artist", "playlist"),
        )
        intParam("limit", "How many results, 1-20. Defaults to 5.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val query = arguments.string("query") ?: return errorJson("missing required argument: query")
        val type = arguments.string("type")?.lowercase()?.takeIf { it in TYPES } ?: "track"
        val limit = (arguments.int("limit") ?: 5).coerceIn(1, 20)

        val results = client.search(query, listOf(type), limit)
        return buildJsonObject {
            put("query", query)
            put("type", type)
            put(
                "results",
                when (type) {
                    "track" -> JsonArray(results.tracks?.items.orEmpty().map { it.toJson() })
                    "artist" -> JsonArray(
                        results.artists?.items.orEmpty().map { artist ->
                            buildJsonObject {
                                put("name", artist.name)
                                put("uri", artist.uri)
                                if (artist.genres.isNotEmpty()) {
                                    put("genres", artist.genres.take(3).joinToString(", "))
                                }
                            }
                        },
                    )

                    "album" -> JsonArray(
                        results.albums?.items.orEmpty().map { album ->
                            buildJsonObject {
                                put("name", album.name)
                                put("uri", album.uri)
                                put("artist", album.artists.joinToString(", ") { it.name })
                                album.releaseDate?.let { put("released", it) }
                                put("tracks", album.totalTracks)
                            }
                        },
                    )

                    else -> JsonArray(
                        results.playlists?.items.orEmpty().filterNotNull().map { playlist ->
                            buildJsonObject {
                                put("name", playlist.name)
                                put("uri", playlist.uri)
                                put("owner", playlist.owner?.displayName ?: "")
                                put("tracks", playlist.tracks?.total ?: 0)
                            }
                        },
                    )
                },
            )
        }.toString()
    }

    private companion object {
        val TYPES = setOf("track", "album", "artist", "playlist")
    }
}

/** What is playing, where, and how far through. */
@Singleton
class SpotifyNowPlayingTool @Inject constructor(
    session: SpotifySession,
    client: SpotifyClient,
) : SpotifyTool(session, client) {

    override val name: String = "spotify_now_playing"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "What is playing on the user's Spotify right now, on which device, and how " +
            "far through it is. Answers \"what is this song?\" and is worth checking before " +
            "changing playback, so you can say what you are interrupting.",
    ) {}

    override suspend fun run(arguments: JsonObject): String {
        val state = client.playbackState()
            ?: return buildJsonObject {
                put("playing", false)
                put("note", "Nothing is playing, and no Spotify device is active.")
            }.toString()

        return buildJsonObject {
            put("playing", state.isPlaying)
            state.item?.let { put("track", it.toJson()) }
            state.device?.let { device ->
                putJsonObject("device") {
                    put("name", device.name)
                    put("type", device.type)
                    device.volumePercent?.let { put("volume", it) }
                }
            }
            state.progressMs?.let { progress ->
                put("progress", formatDuration(progress))
                state.item?.durationMs?.takeIf { it > 0 }?.let { total ->
                    put("through_percent", (progress * 100 / total).toInt())
                }
            }
            state.shuffleState?.let { put("shuffle", it) }
        }.toString()
    }
}

/** The user's own music: playlists, saved tracks, top artists, and where it can play. */
@Singleton
class SpotifyLibraryTool @Inject constructor(
    session: SpotifySession,
    client: SpotifyClient,
) : SpotifyTool(session, client) {

    override val name: String = "spotify_library"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Read the user's own Spotify: their playlists, their saved tracks, the " +
            "artists and tracks they actually listen to, or the devices they can play on. Use " +
            "\"top_artists\" when you need to know their taste — it is a far better answer to " +
            "\"recommend me something\" than guessing from one song they mentioned.",
        required = listOf("kind"),
    ) {
        enumParam(
            "kind",
            "What to read.",
            listOf("playlists", "saved_tracks", "top_artists", "top_tracks", "devices", "playlist_tracks"),
        )
        stringParam(
            "playlist_id",
            "Required for \"playlist_tracks\". The id (not the URI) from a playlists result.",
        )
        intParam("limit", "How many items, 1-50. Defaults to 20.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val kind = arguments.string("kind")?.lowercase()
            ?: return errorJson("missing required argument: kind")
        val limit = (arguments.int("limit") ?: 20).coerceIn(1, 50)

        return when (kind) {
            "playlists" -> {
                val page = client.playlists(limit)
                listJson("playlists", page.total) {
                    page.items.map { playlist ->
                        buildJsonObject {
                            put("id", playlist.id)
                            put("name", playlist.name)
                            put("uri", playlist.uri)
                            put("tracks", playlist.tracks?.total ?: 0)
                            put("owner", playlist.owner?.displayName ?: "")
                        }
                    }
                }
            }

            "playlist_tracks" -> {
                val id = arguments.string("playlist_id")
                    ?: return errorJson("playlist_tracks needs a playlist_id")
                val page = client.playlistTracks(id, limit)
                listJson("tracks", page.total) {
                    page.items.mapNotNull { it.track?.toJson() }
                }
            }

            "saved_tracks" -> {
                val page = client.savedTracks(limit)
                listJson("tracks", page.total) { page.items.mapNotNull { it.track?.toJson() } }
            }

            "top_artists" -> {
                val page = client.topItems("artists", limit, SpotifyArtist.serializer())
                listJson("artists", page.total) {
                    page.items.map { artist ->
                        buildJsonObject {
                            put("name", artist.name)
                            put("uri", artist.uri)
                            if (artist.genres.isNotEmpty()) {
                                put("genres", artist.genres.take(3).joinToString(", "))
                            }
                        }
                    }
                }
            }

            "top_tracks" -> {
                val page = client.topItems("tracks", limit, SpotifyTrack.serializer())
                listJson("tracks", page.total) { page.items.map { it.toJson() } }
            }

            "devices" -> {
                val devices = client.devices().devices
                listJson("devices", devices.size) {
                    devices.map { device ->
                        buildJsonObject {
                            device.id?.let { put("device_id", it) }
                            put("name", device.name)
                            put("type", device.type)
                            put("active", device.isActive)
                            device.volumePercent?.let { put("volume", it) }
                        }
                    }
                }
            }

            else -> errorJson("unknown kind: $kind")
        }
    }

    private inline fun listJson(
        key: String,
        total: Int,
        items: () -> List<JsonElement>,
    ): String = buildJsonObject {
        put("total", total)
        put(key, JsonArray(items()))
    }.toString()
}

/**
 * Playback control, which is reversible and therefore not a write.
 *
 * Every action here is undone by another action here — pause by play, next by previous, a volume
 * change by another volume change. That is the whole test [CirrusTool.writes] applies, and it is
 * what keeps "pause the music" working for somebody who has, quite reasonably, not granted this app
 * permission to change things permanently.
 */
@Singleton
class SpotifyPlaybackTool @Inject constructor(
    session: SpotifySession,
    client: SpotifyClient,
    private val settings: SettingsRepository,
) : SpotifyTool(session, client) {

    override val name: String = "spotify_playback"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Control what Spotify is playing: start something, pause, skip, queue a " +
            "track, set the volume, shuffle, or move playback to another device. Needs Spotify " +
            "Premium — the Web API refuses playback control on free accounts, and there is no " +
            "media-key fallback on this build. Get a `uri` from spotify_search first; \"play\" " +
            "with no uri resumes whatever was paused.",
        required = listOf("action"),
    ) {
        enumParam(
            "action",
            "What to do.",
            listOf("play", "pause", "next", "previous", "queue", "volume", "shuffle", "transfer"),
        )
        stringParam(
            "uri",
            "A Spotify URI from a search. An album, artist or playlist URI plays the whole thing; " +
                "a track URI plays just that track. Required for \"queue\".",
        )
        intParam("volume_percent", "0-100. Required for \"volume\".")
        stringParam("device_id", "From spotify_library kind \"devices\". Required for \"transfer\".")
        stringParam("shuffle", "\"on\" or \"off\", for the shuffle action.")
    }

    override suspend fun run(arguments: JsonObject): String {
        val action = arguments.string("action")?.lowercase()
            ?: return errorJson("missing required argument: action")
        val uri = arguments.string("uri")
        val deviceId = arguments.string("device_id")

        when (action) {
            "play" -> {
                // A track URI and a context URI are different fields, and sending a track as a
                // context makes Spotify answer 404 rather than play it.
                val isContext = uri != null && !uri.startsWith("spotify:track:")
                client.play(
                    contextUri = uri?.takeIf { isContext },
                    trackUris = listOfNotNull(uri?.takeUnless { isContext }),
                    deviceId = deviceId,
                )
            }

            "pause" -> client.pause(deviceId)
            "next" -> client.next(deviceId)
            "previous" -> client.previous(deviceId)
            "queue" -> {
                uri ?: return errorJson("queue needs a uri")
                client.enqueue(uri, deviceId)
            }

            "volume" -> {
                val percent = arguments.int("volume_percent")
                    ?: return errorJson("volume needs volume_percent, 0-100")
                client.setVolume(percent, deviceId)
            }

            "shuffle" -> client.setShuffle(
                on = arguments.string("shuffle")?.lowercase() != "off",
                deviceId = deviceId,
            )

            "transfer" -> {
                deviceId ?: return errorJson("transfer needs a device_id")
                client.transfer(deviceId, keepPlaying = true)
            }

            else -> return errorJson("unknown action: $action")
        }

        return buildJsonObject {
            put("done", action)
            uri?.let { put("uri", it) }
            if (!settings.current.value.spotifyPremium) {
                // The account was free at the last sign-in and the call went through anyway, which
                // means the tier was upgraded or misread. Worth noting rather than asserting.
                put("note", "This account was not recorded as Premium, but the command was accepted.")
            }
        }.toString()
    }
}

/** Making and editing playlists, which cannot be undone by calling this again. */
@Singleton
class SpotifyPlaylistEditTool @Inject constructor(
    session: SpotifySession,
    client: SpotifyClient,
) : SpotifyTool(session, client) {

    override val name: String = "spotify_playlist_edit"

    override val writes: Boolean = true

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Create a playlist, or add and remove tracks in one. THIS CHANGES THE " +
            "USER'S ACCOUNT and other people may be able to see the result, so only do it when " +
            "they have actually asked for it — say what you are about to add and to which " +
            "playlist first. Track URIs come from spotify_search; playlist ids from " +
            "spotify_library. Creating a playlist makes it private unless asked otherwise.",
        required = listOf("action"),
    ) {
        enumParam("action", "What to do.", listOf("create", "add", "remove"))
        stringParam("name", "The new playlist's name. Required for \"create\".")
        stringParam("description", "One line describing the playlist, for \"create\".")
        stringParam("public", "\"true\" to make a created playlist public. Defaults to private.")
        stringParam("playlist_id", "Which playlist to change. Required for \"add\" and \"remove\".")
        stringParam("uris", "Comma-separated Spotify track URIs, for \"add\" and \"remove\".")
    }

    override suspend fun run(arguments: JsonObject): String {
        val action = arguments.string("action")?.lowercase()
            ?: return errorJson("missing required argument: action")

        return when (action) {
            "create" -> {
                val name = arguments.string("name") ?: return errorJson("create needs a name")
                val profile = client.profile()
                val created = client.createPlaylist(
                    userId = profile.id,
                    name = name,
                    description = arguments.string("description"),
                    public = arguments.string("public")?.lowercase() == "true",
                )
                buildJsonObject {
                    put("created", created.name)
                    put("playlist_id", created.id)
                    put("uri", created.uri)
                    created.externalUrls["spotify"]?.let { put("url", it) }
                }.toString()
            }

            "add", "remove" -> {
                val playlistId = arguments.string("playlist_id")
                    ?: return errorJson("$action needs a playlist_id")
                val uris = arguments.string("uris")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.startsWith("spotify:") }
                    .orEmpty()
                if (uris.isEmpty()) {
                    return errorJson("$action needs at least one Spotify track URI in uris")
                }

                if (action == "add") {
                    client.addToPlaylist(playlistId, uris)
                } else {
                    client.removeFromPlaylist(playlistId, uris)
                }
                buildJsonObject {
                    put(if (action == "add") "added" else "removed", uris.size)
                    put("playlist_id", playlistId)
                }.toString()
            }

            else -> errorJson("unknown action: $action")
        }
    }
}

// ---- Shared shaping --------------------------------------------------------------------------

/** One track, in the handful of fields anyone would say out loud. */
internal fun SpotifyTrack.toJson(): JsonElement = buildJsonObject {
    put("name", name)
    put("artist", artists.joinToString(", ") { it.name })
    album?.let { put("album", it.name) }
    put("uri", uri)
    if (durationMs > 0) put("duration", formatDuration(durationMs))
}

internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
