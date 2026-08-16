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
    /** Offers the GitHub tools to the model. Requires a token to have any effect. */
    val gitHubToolsEnabled: Boolean = false,
    val hasGitHubToken: Boolean = false,
    /**
     * Lets every tool that changes something outside Cirrus actually run: opening a GitHub issue,
     * committing a file, or an MCP tool that has not declared itself read-only.
     *
     * Default off, and one switch rather than one per integration. Reading is recoverable and
     * writing is not, whoever is being written to.
     */
    val writeToolsAllowed: Boolean = false,
    /**
     * Offers the shell and the everyday-work tools: run_command, the clock, the calendar and the
     * device summary.
     *
     * On by default, and not behind the conversation's tools switch, for the same reason memory is
     * not: none of it leaves the machine, none of it costs a round trip, and a model that cannot
     * find out what today's date is answers scheduling questions from the year it was trained in.
     * The shell itself is safe to leave on because CommandPolicy decides what may run before
     * anything does, and the working directory is a scratch folder in Cirrus's own data
     * directory.
     */
    val shellToolsEnabled: Boolean = true,
    /**
     * Lets the model list and open applications on this computer.
     *
     * Off by default. Everything else in the local set answers a question; this one acts — it
     * puts another application in front of whatever the user was reading.
     */
    val appControlEnabled: Boolean = false,
    /** Offers the Spotify tools. Not wired on desktop yet. */
    val spotifyEnabled: Boolean = false,
    val spotifyClientId: String = "",
    val hasSpotifyAccount: Boolean = false,
    val spotifyAccountName: String = "",
    val spotifyPremium: Boolean = false,
    /** Offers the remember/recall/forget tools, and sends pinned memories with every turn. */
    val memoryEnabled: Boolean = true,
    /** Lets a model put something on the desktop notification tray. */
    val notificationToolEnabled: Boolean = true,
    /** Runs the nightly pass that merges duplicate memories and retires stale ones. */
    val memoryConsolidationEnabled: Boolean = true,
    /** Local hour at which that pass runs. Late enough to be asleep, early enough to be charged. */
    val memoryConsolidationHour: Int = 3,
    val lastConsolidationAt: Long = 0L,
    /**
     * Whether the first-run wizard has been through.
     */
    val onboardingCompleted: Boolean = false,
    /** Suggested openers on an empty chat. Off for people who know what they want to type. */
    val showStarterPrompts: Boolean = true,
)
