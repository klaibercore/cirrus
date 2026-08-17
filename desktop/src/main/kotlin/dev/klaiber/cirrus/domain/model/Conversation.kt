package dev.klaiber.cirrus.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val model: String,
    val systemPrompt: String? = null,
    val params: GenerationParams = GenerationParams.Default,
    val toolsEnabled: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Set when this conversation was branched off another one at a specific message. */
    val forkedFromConversationId: String? = null,
    val forkedFromMessageId: String? = null,
    /**
     * When the model last wrote [title]. Null means the title belongs to the user — hand-typed
     * or still the placeholder — and auto-titling must not touch it.
     */
    val autoTitledAt: Long? = null,
    /**
     * The agent that wrote this thread, if it was written by one. Desktop has no agents yet;
     * kept for shape compatibility with the Android model.
     */
    val agentId: String? = null,
) {
    val isFork: Boolean get() = forkedFromConversationId != null

    /** True while this thread is still an agent's output rather than a conversation. */
    val isAgentRun: Boolean get() = agentId != null

    /** True while the thread still carries the placeholder name rather than one of its own. */
    val isUntitled: Boolean get() = title == DEFAULT_TITLE

    companion object {
        /**
         * The name a thread carries until it is titled.
         */
        const val DEFAULT_TITLE = "New chat"

        /**
         * Stamped on a locally derived title (see `ConversationTitler`) so it reads as "titled,
         * but long ago": the thread stops being a placeholder, yet the next turn is free to
         * replace it with something the model wrote.
         */
        const val FALLBACK_TITLED_AT = 0L
    }
}

/** A reusable system prompt + parameter bundle, equivalent to a lightweight "project". */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val description: String? = null,
    val systemPrompt: String,
    val model: String? = null,
    val params: GenerationParams = GenerationParams.Default,
    val toolsEnabled: Boolean = false,
    val createdAt: Long,
)

/** A conversation row decorated with the counts the drawer needs, without loading messages. */
data class ConversationSummary(
    val conversation: Conversation,
    val messageCount: Int,
    val lastMessagePreview: String?,
)
