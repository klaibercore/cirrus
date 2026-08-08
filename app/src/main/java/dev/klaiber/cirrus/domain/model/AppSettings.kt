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
)
