package dev.klaiber.cirrus.domain.model

/**
 * A capability as reported by `/api/show`.
 *
 * [wire] mirrors Ollama's own `types/model.Capability` constants. Values this app does not know
 * about are dropped rather than shown raw, so a newer server simply degrades to "not displayed".
 */
enum class ModelCapability(
    val wire: String,
    val label: String,
    /** One-line explanation, surfaced as the chip's tooltip in the model picker. */
    val help: String,
) {
    COMPLETION(
        wire = "completion",
        label = "Chat",
        help = "Generates text replies. Every conversational model reports this.",
    ),
    THINKING(
        wire = "thinking",
        label = "Reasoning",
        help = "Can reason before answering. Cirrus streams that reasoning into a collapsible " +
            "section and lets you set the effort level in Parameters.",
    ),
    VISION(
        wire = "vision",
        label = "Vision",
        help = "Reads images. Attach a photo or screenshot and the model sees it directly " +
            "instead of only its file name.",
    ),
    TOOLS(
        wire = "tools",
        label = "Tools",
        help = "Can call tools. With web tools enabled in the composer, the model can run web " +
            "searches and fetch pages mid-answer.",
    ),
    AUDIO(
        wire = "audio",
        label = "Audio",
        help = "Understands speech. Ollama's chat API carries no audio field yet, so Cirrus " +
            "transcribes your voice on-device and sends the text.",
    ),
    IMAGE(
        wire = "image",
        label = "Image output",
        help = "Can return generated images as well as text.",
    ),
    EMBEDDING(
        wire = "embedding",
        label = "Embeddings",
        help = "Produces vectors for search and similarity, not chat replies. Not usable here.",
    ),
    INSERT(
        wire = "insert",
        label = "Infill",
        help = "Supports fill-in-the-middle completion between a prefix and a suffix.",
    ),
    ;

    companion object {
        fun fromWire(value: String): ModelCapability? =
            entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}
