package dev.klaiber.cirrus.domain.settings

import dev.klaiber.cirrus.domain.model.AppSettings

/**
 * Every switch that can stop a tool from running, named the way the user would find it.
 *
 * This exists because of a specific failure. A model asks for a tool that is switched off, is told
 * "unknown tool", and — having no way to tell "this app cannot do that" from "this app can do that
 * but not right now" — tells the user Cirrus does not support it. The user then believes a false
 * thing about their own app, and there is nothing in the conversation to correct it.
 *
 * One list fixes both halves. `ToolRegistry` uses it to turn a refusal into a sentence naming the
 * exact toggle, and `describe_settings` hands the whole thing over when the model wants to check
 * before promising something. The [path] strings are the load-bearing part: "it is disabled" is
 * not actionable, and "Settings → Tools → Apps and media" is.
 *
 * Which means these strings have to match the interface. A path that has drifted is worse than no
 * path, because it sends someone looking for a row that is not there — `SettingsCatalogTest`
 * asserts that every entry names a section that exists.
 */
enum class SettingSwitch(
    val id: String,
    val title: String,
    /** Where to tap, in full, written as the screens read. */
    val path: String,
    /** What turning it on makes possible, in one line. */
    val summary: String,
    private val reader: (AppSettings) -> Boolean,
    /**
     * A switch can be on and still not work, because the credential behind it is missing. Null
     * when there is nothing to configure.
     */
    private val credential: Credential? = null,
) {
    MEMORY(
        id = "memory",
        title = "Memory",
        path = "Settings → Tools → Memory",
        summary = "Remembering things about the user between conversations, and recalling them.",
        reader = AppSettings::memoryEnabled,
    ),
    NOTIFICATIONS(
        id = "notifications",
        title = "Notifications",
        path = "Settings → Tools → Notifications",
        summary = "Putting something on the phone's notification shade.",
        reader = AppSettings::notificationToolEnabled,
    ),
    SHELL(
        id = "shell",
        title = "Shell and everyday tools",
        path = "Settings → Tools → Shell and everyday tools",
        summary = "The exact date and time, a calendar month, this phone's details, and safe " +
            "shell commands in a private scratch folder.",
        reader = AppSettings::shellToolsEnabled,
    ),
    APPS(
        id = "apps",
        title = "Apps and media",
        path = "Settings → Tools → Apps and media",
        summary = "Listing installed apps, opening one, offering a store page, and the play/pause " +
            "and volume controls for whatever is playing on the phone.",
        reader = AppSettings::appControlEnabled,
    ),
    LOCATION(
        id = "location",
        title = "Location",
        path = "Settings → Tools → Location",
        summary = "Where the phone is now — for weather, travel time, or anything local.",
        reader = AppSettings::locationEnabled,
        credential = Credential(
            hint = "Android also has to have granted the location permission. The switch asks " +
                "for it when it is turned on; if it was refused, it has to be granted from the " +
                "phone's own Settings → Apps → Cirrus → Permissions.",
            present = AppSettings::hasLocationPermission,
        ),
    ),
    WRITES(
        id = "writes",
        title = "Allow write actions",
        path = "Settings → Tools → Allow write actions",
        summary = "Tools that change something outside Cirrus and cannot be undone from inside " +
            "it — opening a GitHub issue, committing a file, editing a Spotify playlist, or any " +
            "MCP tool that has not declared itself read-only.",
        reader = AppSettings::writeToolsAllowed,
    ),
    GITHUB(
        id = "github",
        title = "GitHub tools",
        path = "Settings → GitHub and MCP → GitHub tools",
        summary = "Reading repositories, code, issues and pull requests.",
        reader = AppSettings::gitHubToolsEnabled,
        credential = Credential(
            hint = "Needs a personal access token, saved on the same screen.",
            present = AppSettings::hasGitHubToken,
        ),
    ),
    SPOTIFY(
        id = "spotify",
        title = "Spotify",
        path = "Settings → Music → Spotify",
        summary = "Searching Spotify, reading the user's playlists and saved music, seeing what " +
            "is playing, and controlling playback.",
        reader = AppSettings::spotifyEnabled,
        credential = Credential(
            hint = "Needs a client ID from developer.spotify.com and a one-time sign-in, both " +
                "on the same screen. Playback control also needs Spotify Premium; without it, " +
                "media_control still works if Apps and media is on.",
            present = AppSettings::hasSpotifyAccount,
        ),
    ),
    ;

    fun isOn(settings: AppSettings): Boolean = reader(settings)

    /** True when the switch is on *and* whatever it needs behind it is present. */
    fun isUsable(settings: AppSettings): Boolean =
        isOn(settings) && (credential?.present?.invoke(settings) ?: true)

    /**
     * The state, in a phrase the model can put in a sentence.
     *
     * The credential case is the one worth separating: "switched off" and "switched on but not
     * signed in" send the user to the same screen for completely different reasons, and telling
     * someone to enable a switch that is already enabled is how a model loses their confidence.
     */
    fun status(settings: AppSettings): String = when {
        !isOn(settings) -> "off"
        credential != null && !credential.present(settings) -> "on, but not set up yet"
        else -> "on"
    }

    /** What to tell the user to do about it, or null when nothing needs doing. */
    fun remedy(settings: AppSettings): String? = when {
        !isOn(settings) -> "Ask the user to turn on \"$title\" at $path."
        credential != null && !credential.present(settings) ->
            "\"$title\" is on but not finished at $path. ${credential.hint}"

        else -> null
    }

    val credentialHint: String? get() = credential?.hint

    private class Credential(val hint: String, val present: (AppSettings) -> Boolean)

    companion object {
        fun byId(id: String): SettingSwitch? = entries.firstOrNull { it.id.equals(id, true) }
    }
}
