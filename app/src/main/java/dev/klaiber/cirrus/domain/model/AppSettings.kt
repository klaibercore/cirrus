package dev.klaiber.cirrus.domain.model

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * Everything configurable from the settings screen.
 *
 * [defaultParams] seeds each new conversation; conversations then own their own copy so that
 * changing defaults later never rewrites the settings of an existing thread.
 */
data class AppSettings(
    val baseUrl: String = "https://ollama.com",
    val hasApiKey: Boolean = false,
    val defaultModel: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Surfaces the raw request/response inspector and per-message stats. */
    val developerMode: Boolean = false,
    val defaultParams: GenerationParams = GenerationParams.Default,
    val toolsEnabledByDefault: Boolean = false,
    val webSearchMaxResults: Int = 5,
    /** Guards against a model looping on tool calls forever. */
    val maxToolIterations: Int = 6,
    val showStats: Boolean = true,
    val renderMarkdown: Boolean = true,
    /** Ask the model for a short title after the first exchange. */
    val autoTitleConversations: Boolean = true,
    /**
     * How many prior messages to replay as context. Zero means send the whole thread and let
     * the server-side context window do the truncating.
     */
    val contextMessageLimit: Int = 0,
    val sendOnEnter: Boolean = false,
    /** Shows the microphone in the composer. */
    /** Offers the GitHub tools to the model. Requires a token to have any effect. */
    val gitHubToolsEnabled: Boolean = false,
    val hasGitHubToken: Boolean = false,
    /**
     * Lets every tool that changes something outside Cirrus actually run: opening a GitHub issue,
     * committing a file, editing a Spotify playlist, or an MCP tool that has not declared itself
     * read-only.
     *
     * Default off, and one switch rather than one per integration. Reading is recoverable and
     * writing is not, whoever is being written to — and a per-integration switch meant the third
     * integration shipped without one, which is exactly what had happened to MCP.
     *
     * Migrated from the old `github_writes` key, so anyone who had allowed GitHub writes keeps
     * them without being asked again.
     */
    val writeToolsAllowed: Boolean = false,
    val voiceInputEnabled: Boolean = true,
    /**
     * Prefer Android's offline recogniser, so dictated audio never leaves the device. Falls back
     * to the network recogniser when the platform has no on-device model for the locale.
     */
    val preferOnDeviceRecognition: Boolean = true,
    /** Shows the read-aloud control on finished answers. */
    val readAloudEnabled: Boolean = true,
    /** Whether that control speaks a summary of the answer or the whole of it. */
    val readAloudStyle: ReadAloudStyle = ReadAloudStyle.SUMMARY,
    val speechEngine: SpeechEngine = SpeechEngine.DEVICE,
    val hasElevenLabsKey: Boolean = false,
    /** Blank until a voice is picked, at which point the client falls back to a sensible default. */
    val elevenLabsVoiceId: String = "",
    val elevenLabsVoiceName: String = "",
    val elevenLabsModelId: String = ElevenLabsModel.Default.id,
    /**
     * Offers the shell and the everyday-work tools: run_command, the clock, the calendar and the
     * device summary.
     *
     * On by default, and not behind the conversation's tools switch, for the same reason memory is
     * not: none of it leaves the phone, none of it costs a round trip, and a model that cannot find
     * out what today's date is answers scheduling questions from the year it was trained in. The
     * shell itself is safe to leave on because [dev.klaiber.cirrus.domain.tools.shell.CommandPolicy]
     * decides what may run before anything does, and the working directory is a scratch folder in
     * Cirrus's own cache.
     */
    val shellToolsEnabled: Boolean = true,
    /**
     * Lets the model list, open and offer to install apps.
     *
     * Off by default. Everything else in the local set answers a question; this one acts — it puts
     * another app in front of whatever the user was reading, and points them at a store page. It
     * still cannot install anything: Android's own installer asks, every time.
     */
    val appControlEnabled: Boolean = false,
    /**
     * Offers `get_location`.
     *
     * Off by default and separate from everything else, because where somebody is is the most
     * personal thing this app can read, and it is the one capability whose usefulness ("what is
     * the weather here?") is easy to mistake for a reason to leave it on permanently.
     */
    val locationEnabled: Boolean = false,
    /**
     * A mirror of Android's own permission, kept so the settings catalogue can tell "switched off"
     * apart from "switched on but the permission was refused" — two states that send the user to
     * completely different screens. Written whenever it is observed; the tool re-checks for real
     * at the moment of the call, and that check is the authority.
     */
    val hasLocationPermission: Boolean = false,
    /** Offers the Spotify tools. Needs a client ID and a signed-in account to do anything. */
    val spotifyEnabled: Boolean = false,
    /** The user's own Spotify application, from developer.spotify.com. No secret: this is PKCE. */
    val spotifyClientId: String = "",
    val hasSpotifyAccount: Boolean = false,
    /** Shown in settings so it is obvious which account is connected. */
    val spotifyAccountName: String = "",
    /**
     * Whether the connected account is Premium.
     *
     * Recorded because the Web API refuses playback control on free accounts with a 403 that says
     * nothing useful, and the honest answer to that is to fall back to the on-device media keys
     * rather than to report a failure.
     */
    val spotifyPremium: Boolean = false,
    /** Offers the remember/recall/forget tools, and sends pinned memories with every turn. */
    val memoryEnabled: Boolean = true,
    /**
     * Offers the installed skills: their roster in the system message, and use_skill to open one.
     *
     * On by default, and that costs nothing until a skill is installed — the roster is omitted when
     * there is nothing in it. Like memory, it is not behind the conversation's tools switch: a
     * skill is a document already on the phone, and loading one reaches no further than the disk.
     */
    val skillsEnabled: Boolean = true,
    /** Lets a model put something on the notification shade. */
    val notificationToolEnabled: Boolean = true,
    /** Runs the nightly pass that merges duplicate memories and retires stale ones. */
    val memoryConsolidationEnabled: Boolean = true,
    /** Local hour at which that pass runs. Late enough to be asleep, early enough to be charged. */
    val memoryConsolidationHour: Int = 3,
    val lastConsolidationAt: Long = 0L,
    /**
     * Whether the first-run wizard has been through.
     *
     * Read defensively rather than as a plain flag: anyone who already had a key or a model when
     * this shipped has plainly finished setting up, and showing them a welcome screen on the next
     * launch would be the worst possible reward for having been an early user.
     */
    val onboardingCompleted: Boolean = false,
    /** Suggested openers on an empty chat. Off for people who know what they want to type. */
    val showStarterPrompts: Boolean = true,
)
