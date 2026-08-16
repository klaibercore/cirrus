package dev.klaiber.cirrus.domain.model

/** Who does the talking when an answer is read aloud. */
enum class SpeechEngine(val label: String, val description: String) {
    DEVICE(
        label = "On device",
        description = "Android's own voice. Free, offline, and always available.",
    ),
    ELEVENLABS(
        label = "ElevenLabs",
        description = "Far more natural, but it needs an API key and spends characters.",
    ),
}

/**
 * What gets said when an answer is read aloud.
 *
 * [SUMMARY] is the default because a written answer and a spoken one are different documents. See
 * [dev.klaiber.cirrus.domain.SpeechSummarizer] for the argument; the short version is that a
 * listener cannot skim, so the parts of an answer written to be skimmed — the restatement of the
 * question, the option list, the code — cost them minutes rather than seconds.
 */
enum class ReadAloudStyle(val label: String, val description: String) {
    SUMMARY(
        label = "Spoken summary",
        description = "A few paragraphs written for the ear: what it concluded and what to do.",
    ),
    VERBATIM(
        label = "The whole answer",
        description = "Every word on screen, in order. Code blocks are announced, not dictated.",
    ),
}

/**
 * The ElevenLabs models worth offering.
 *
 * Flash is the default because read-aloud is an interaction, not a render: the wait before audio
 * starts is what the feature is judged on. The other two are here for anyone who would rather wait
 * a moment for a better take.
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
