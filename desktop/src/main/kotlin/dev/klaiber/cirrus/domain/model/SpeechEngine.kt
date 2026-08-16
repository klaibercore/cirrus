package dev.klaiber.cirrus.domain.model

/** Who does the talking when an answer is read aloud. Kept for shape compatibility. */
enum class SpeechEngine(val label: String, val description: String) {
    DEVICE(
        label = "On device",
        description = "The platform's own voice. Free, offline, and always available.",
    ),
    ELEVENLABS(
        label = "ElevenLabs",
        description = "Far more natural, but it needs an API key and spends characters.",
    ),
}

/**
 * The ElevenLabs models worth offering.
 */
enum class ElevenLabsModel(val id: String, val label: String, val description: String) {
    FLASH(
        id = "eleven_flash_v2_5",
        label = "Flash v2.5",
        description = "Lowest latency. Best for reading a long answer back.",
    ),
    MULTILINGUAL(
        id = "eleven_multilingual_v2",
        label = "Multilingual v2",
        description = "The stable, high-quality workhorse. Slower to start.",
    ),
    EXPRESSIVE(
        id = "eleven_v3",
        label = "v3",
        description = "The most expressive delivery, and the slowest.",
    );

    companion object {
        val Default = FLASH

        fun fromId(id: String): ElevenLabsModel = entries.firstOrNull { it.id == id } ?: Default
    }
}
