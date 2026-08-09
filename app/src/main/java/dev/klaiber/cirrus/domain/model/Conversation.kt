package dev.klaiber.cirrus.domain.model

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
) {
    val isFork: Boolean get() = forkedFromConversationId != null
}

/** A reusable system prompt + parameter bundle, equivalent to a lightweight "project". */
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
