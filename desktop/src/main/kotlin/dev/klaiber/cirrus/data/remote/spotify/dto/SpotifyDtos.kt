package dev.klaiber.cirrus.data.remote.spotify.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the Spotify Web API, trimmed hard.
 *
 * A track object comes back with about forty fields, most of them URLs, external ids and market
 * availability lists. Every one that survives here is one a model would actually put in a sentence.
 * That is not tidiness: these objects are serialised straight back into the conversation as tool
 * results, so an untrimmed page of search results would cost more context than the answer it is
 * supposed to support.
 */
@Serializable
data class SpotifyPage<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class SpotifyArtistRef(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
)

@Serializable
data class SpotifyAlbumRef(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
)

@Serializable
data class SpotifyTrack(
    val id: String? = null,
    val name: String = "",
    val uri: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    val artists: List<SpotifyArtistRef> = emptyList(),
    val album: SpotifyAlbumRef? = null,
    val explicit: Boolean = false,
)

@Serializable
data class SpotifyArtist(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val genres: List<String> = emptyList(),
    val popularity: Int = 0,
)

@Serializable
data class SpotifyAlbum(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int = 0,
    val artists: List<SpotifyArtistRef> = emptyList(),
)

@Serializable
data class SpotifyOwner(
    @SerialName("display_name") val displayName: String? = null,
    val id: String = "",
)

@Serializable
data class SpotifyPlaylist(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val description: String? = null,
    val owner: SpotifyOwner? = null,
    val public: Boolean? = null,
    val tracks: SpotifyTrackCount? = null,
)

@Serializable
data class SpotifyTrackCount(val total: Int = 0)

@Serializable
data class SpotifySearchResponse(
    val tracks: SpotifyPage<SpotifyTrack>? = null,
    val artists: SpotifyPage<SpotifyArtist>? = null,
    val albums: SpotifyPage<SpotifyAlbum>? = null,
    val playlists: SpotifyPage<SpotifyPlaylist?>? = null,
)

@Serializable
data class SpotifyDevice(
    val id: String? = null,
    val name: String = "",
    val type: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("volume_percent") val volumePercent: Int? = null,
)

@Serializable
data class SpotifyDevices(val devices: List<SpotifyDevice> = emptyList())

@Serializable
data class SpotifyPlaybackState(
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("progress_ms") val progressMs: Long? = null,
    @SerialName("shuffle_state") val shuffleState: Boolean? = null,
    @SerialName("repeat_state") val repeatState: String? = null,
    val device: SpotifyDevice? = null,
    val item: SpotifyTrack? = null,
)

/** A playlist's tracks arrive wrapped, and the wrapper can hold a null for a removed track. */
@Serializable
data class SpotifyPlaylistItem(val track: SpotifyTrack? = null)

@Serializable
data class SpotifySavedTrack(val track: SpotifyTrack? = null)

@Serializable
data class SpotifyProfile(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
    /** "premium" or "free". The one field that decides whether playback control can work at all. */
    val product: String? = null,
)

/** What a create-playlist call gives back. */
@Serializable
data class SpotifyCreatedPlaylist(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    @SerialName("external_urls") val externalUrls: Map<String, String> = emptyMap(),
)
