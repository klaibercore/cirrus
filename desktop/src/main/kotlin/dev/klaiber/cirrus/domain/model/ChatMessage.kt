package dev.klaiber.cirrus.domain.model

import kotlinx.serialization.Serializable

enum class Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): Role =
            entries.firstOrNull { it.wire == value.lowercase() } ?: ASSISTANT
    }
}

/** A tool invocation requested by the model, plus its result once we have executed it. */
@Serializable
data class ToolInvocation(
    val id: String,
    val name: String,
    /** Raw JSON object of arguments, kept verbatim so the inspector can show exactly what ran. */
    val argumentsJson: String,
    val resultJson: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long? = null,
) {
    val isComplete: Boolean get() = resultJson != null || errorMessage != null
}

/** Per-response timing counters reported by Ollama in the terminal stream chunk. */
@Serializable
data class GenerationStats(
    val totalDurationNs: Long? = null,
    val loadDurationNs: Long? = null,
    val promptEvalCount: Int? = null,
    val promptEvalDurationNs: Long? = null,
    val evalCount: Int? = null,
    val evalDurationNs: Long? = null,
    val doneReason: String? = null,
    /** Measured client-side: time from request start to the first content or thinking token. */
    val timeToFirstTokenMs: Long? = null,
) {
    /** Output tokens per second, derived from the model's own eval timings. */
    val tokensPerSecond: Double?
        get() {
            val count = evalCount ?: return null
            val durationNs = evalDurationNs ?: return null
            if (durationNs <= 0L) return null
            return count.toDouble() / (durationNs.toDouble() / 1_000_000_000.0)
        }

    val totalTokens: Int?
        get() = when {
            promptEvalCount == null && evalCount == null -> null
            else -> (promptEvalCount ?: 0) + (evalCount ?: 0)
        }
}

@Serializable
data class Attachment(
    val id: String,
    val messageId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Path to the copy this app owns inside its private storage. */
    val localPath: String,
    val kind: Kind,
    /** For [Kind.DOCUMENT], the extracted text that was actually injected into the prompt. */
    val extractedText: String? = null,
) {
    enum class Kind { IMAGE, DOCUMENT }
}

@Serializable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    /** Reasoning trace from `message.thinking`, shown in a collapsible section. */
    val thinking: String? = null,
    val createdAt: Long,
    val sequence: Int,
    val model: String? = null,
    val stats: GenerationStats? = null,
    val toolInvocations: List<ToolInvocation> = emptyList(),
    /** Set when generation failed; the message is kept so the user can retry from it. */
    val errorMessage: String? = null,
    val isStreaming: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    /** Raw request/response JSON captured when developer mode is on. */
    val rawRequestJson: String? = null,
)
