package dev.klaiber.cirrus.domain.model

import kotlinx.serialization.Serializable

/**
 * One thing worth remembering between conversations.
 *
 * Deliberately a sentence rather than a transcript. A memory earns its place by being short enough
 * to sit in a system prompt without crowding out the conversation, and by still being true next
 * month — "prefers Kotlin over Java" is a memory, "asked about coroutines on Tuesday" is not.
 */
@Serializable
data class Memory(
    val id: String,
    val content: String,
    val kind: MemoryKind,
    /** The thread this came from, so the viewer can show where a claim originated. */
    val sourceConversationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRecalledAt: Long?,
    val recallCount: Int,
    /** Pinned memories are sent every turn; everything else has to be recalled. */
    val pinned: Boolean,
    /**
     * Retired rather than deleted. Consolidation supersedes memories all the time, and a wrong
     * merge is only recoverable if the original is still there to restore.
     */
    val archived: Boolean,
    /** Lowered when consolidation finds a memory contradicted, raised when it is confirmed. */
    val confidence: Float,
) {
    companion object {
        const val DEFAULT_CONFIDENCE = 0.7f

        /** Long enough for a real fact, short enough that ten of them are still cheap to send. */
        const val MAX_CONTENT_CHARS = 400
    }
}

enum class MemoryKind(val label: String, val hint: String) {
    FACT("Fact", "Something true about the world or the work"),
    PREFERENCE("Preference", "How you like things done"),
    PROJECT("Project", "Ongoing work, goals and constraints"),
    PERSON("Person", "Someone who comes up, and what matters about them"),
    ROUTINE("Routine", "Something that happens on a schedule");

    companion object {
        fun fromName(name: String?): MemoryKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FACT
    }
}
