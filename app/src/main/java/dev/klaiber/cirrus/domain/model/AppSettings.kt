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
    val useDynamicColor: Boolean = true,
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
     * Lets the model open issues, comment and post reviews. Default off: reading is recoverable,
     * writing is not, and a tool call is decided by a model rather than by the user.
     */
    val gitHubWritesAllowed: Boolean = false,
    val voiceInputEnabled: Boolean = true,
    /**
     * Prefer Android's offline recogniser, so dictated audio never leaves the device. Falls back
     * to the network recogniser when the platform has no on-device model for the locale.
     */
    val preferOnDeviceRecognition: Boolean = true,
    /** Shows the read-aloud control on finished answers. */
    val readAloudEnabled: Boolean = true,
    val speechEngine: SpeechEngine = SpeechEngine.DEVICE,
    val hasElevenLabsKey: Boolean = false,
    /** Blank until a voice is picked, at which point the client falls back to a sensible default. */
    val elevenLabsVoiceId: String = "",
    val elevenLabsVoiceName: String = "",
    val elevenLabsModelId: String = ElevenLabsModel.Default.id,
)
