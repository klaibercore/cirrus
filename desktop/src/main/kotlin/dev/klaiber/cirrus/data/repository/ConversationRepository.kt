package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.ConversationSummary
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.GenerationStats
import dev.klaiber.cirrus.domain.model.Preset
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.ToolInvocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Conversations and their messages, backed by a single JSON file.
 *
 * The whole store is held in memory and written out on every mutation. It is small — a few
 * hundred threads of a few hundred messages — so the cost of a full rewrite is negligible, and
 * keeping one in-memory copy means every read is a plain map lookup rather than a file read.
 */
class ConversationRepository(
    private val store: JsonStore,
) {

    private val _state = MutableStateFlow(ConversationStore())

    suspend fun load() {
        _state.value = store.read(ConversationStore.serializer()) { ConversationStore() }
    }

    fun observeSummaries(archived: Boolean = false): Flow<List<ConversationSummary>> =
        _state.map { state -> state.summaries(archived) }

    fun searchConversations(query: String): Flow<List<ConversationSummary>> =
        _state.map { state ->
            val needle = query.trim()
            if (needle.isEmpty()) emptyList()
            else state.summaries(archived = false).filter { summary ->
                summary.conversation.title.contains(needle, ignoreCase = true) ||
                    summary.lastMessagePreview?.contains(needle, ignoreCase = true) == true
            }
        }

    fun observeConversation(id: String): Flow<Conversation?> =
        _state.map { state -> state.conversations.firstOrNull { it.id == id } }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        _state.map { state -> state.messagesFor(conversationId) }

    suspend fun getConversation(id: String): Conversation? =
        _state.value.conversations.firstOrNull { it.id == id }

    suspend fun getMessages(conversationId: String): List<ChatMessage> =
        _state.value.messagesFor(conversationId)

    suspend fun createConversation(
        model: String,
        systemPrompt: String? = null,
        params: GenerationParams = GenerationParams.Default,
        toolsEnabled: Boolean = false,
        title: String = Conversation.DEFAULT_TITLE,
        agentId: String? = null,
    ): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = title,
            model = model,
            systemPrompt = systemPrompt?.takeIf { it.isNotBlank() },
            params = params,
            toolsEnabled = toolsEnabled,
            createdAt = now,
            updatedAt = now,
            agentId = agentId,
        )
        mutate { it.copy(conversations = it.conversations + conversation) }
        return conversation
    }

    /**
     * Threads touched since [since], newest first — what the nightly memory pass reads.
     *
     * Agent runs are excluded on purpose. Memory is meant to be what the *user* said, and a
     * scheduled prompt that runs daily would otherwise be harvested as a durable fact about them
     * every single night, drowning the store in restatements of the agent's own instructions.
     */
    suspend fun recentlyUpdated(since: Long, limit: Int): List<Conversation> =
        _state.value.conversations
            .filter { it.updatedAt > since && !it.archived && it.agentId == null }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    /** Threads this agent wrote that nobody has replied to — replying clears [Conversation.agentId]. */
    suspend fun conversationsForAgent(agentId: String): List<Conversation> =
        _state.value.conversations.filter { it.agentId == agentId }

    suspend fun deleteConversations(ids: List<String>) {
        if (ids.isEmpty()) return
        mutate { state ->
            state.copy(
                conversations = state.conversations.filterNot { it.id in ids },
                messages = state.messages.filterNot { it.conversationId in ids },
            )
        }
    }

    suspend fun updateConversation(conversation: Conversation) {
        mutate { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == conversation.id) conversation.copy(updatedAt = System.currentTimeMillis()) else it
                },
            )
        }
    }

    suspend fun rename(id: String, title: String) {
        val clean = title.trim().ifEmpty { Conversation.DEFAULT_TITLE }
        mutate { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == id) it.copy(title = clean, autoTitledAt = null, updatedAt = System.currentTimeMillis()) else it
                },
            )
        }
    }

    suspend fun applyAutoTitle(id: String, title: String, now: Long = System.currentTimeMillis()) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        mutate { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == id) it.copy(title = clean, autoTitledAt = now, updatedAt = now) else it
                },
            )
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        mutate { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == id) it.copy(pinned = pinned, updatedAt = System.currentTimeMillis()) else it
                },
            )
        }
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        mutate { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == id) it.copy(archived = archived, updatedAt = System.currentTimeMillis()) else it
                },
            )
        }
    }

    suspend fun deleteConversation(id: String) = deleteConversations(listOf(id))

    suspend fun deleteAllConversations() {
        mutate { ConversationStore() }
    }

    suspend fun appendMessage(
        conversationId: String,
        role: Role,
        content: String,
        thinking: String? = null,
        model: String? = null,
        attachments: List<Attachment> = emptyList(),
        toolInvocations: List<ToolInvocation> = emptyList(),
        errorMessage: String? = null,
        rawRequestJson: String? = null,
    ): ChatMessage {
        val sequence = (_state.value.messagesFor(conversationId).maxOfOrNull { it.sequence } ?: 0) + 1
        val messageId = UUID.randomUUID().toString()
        val message = ChatMessage(
            id = messageId,
            conversationId = conversationId,
            role = role,
            content = content,
            thinking = thinking,
            createdAt = System.currentTimeMillis(),
            sequence = sequence,
            model = model,
            toolInvocations = toolInvocations,
            errorMessage = errorMessage,
            attachments = attachments.map { it.copy(messageId = messageId) },
            rawRequestJson = rawRequestJson,
        )
        mutate { state ->
            state.copy(
                messages = state.messages + message,
                conversations = state.conversations.map {
                    if (it.id == conversationId) it.copy(updatedAt = message.createdAt) else it
                },
            )
        }
        return message
    }

    suspend fun updateMessageContent(
        messageId: String,
        content: String,
        thinking: String?,
        stats: GenerationStats?,
        toolInvocations: List<ToolInvocation>,
        errorMessage: String?,
    ) {
        mutate { state ->
            state.copy(
                messages = state.messages.map {
                    if (it.id == messageId) {
                        it.copy(
                            content = content,
                            thinking = thinking,
                            stats = stats,
                            toolInvocations = toolInvocations,
                            errorMessage = errorMessage,
                        )
                    } else {
                        it
                    }
                },
            )
        }
    }

    suspend fun editMessageText(messageId: String, content: String) {
        mutate { state ->
            state.copy(
                messages = state.messages.map {
                    if (it.id == messageId) it.copy(content = content) else it
                },
            )
        }
    }

    suspend fun deleteMessage(messageId: String) {
        mutate { state -> state.copy(messages = state.messages.filterNot { it.id == messageId }) }
    }

    suspend fun truncateFrom(messageId: String) {
        val target = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        mutate { state ->
            state.copy(
                messages = state.messages.filterNot {
                    it.conversationId == target.conversationId && it.sequence >= target.sequence
                },
            )
        }
    }

    suspend fun fork(conversationId: String, throughMessageId: String): Conversation? {
        val state = _state.value
        val source = state.conversations.firstOrNull { it.id == conversationId } ?: return null
        val messages = state.messagesFor(conversationId)
        val cutoffIndex = messages.indexOfFirst { it.id == throughMessageId }
        if (cutoffIndex < 0) return null

        val now = System.currentTimeMillis()
        val forkId = UUID.randomUUID().toString()
        val fork = source.copy(
            id = forkId,
            title = source.title.take(MAX_TITLE_LENGTH) + " (branch)",
            createdAt = now,
            updatedAt = now,
            pinned = false,
            archived = false,
            forkedFromConversationId = conversationId,
            forkedFromMessageId = throughMessageId,
            agentId = null,
        )
        val forkedMessages = messages.take(cutoffIndex + 1).map { row ->
            val newMessageId = UUID.randomUUID().toString()
            row.copy(
                id = newMessageId,
                conversationId = forkId,
                attachments = row.attachments.map { it.copy(id = UUID.randomUUID().toString(), messageId = newMessageId) },
            )
        }
        mutate { current ->
            current.copy(
                conversations = current.conversations + fork,
                messages = current.messages + forkedMessages,
            )
        }
        return fork
    }

    fun observePresets(): Flow<List<Preset>> = _state.map { it.presets }

    suspend fun getPreset(id: String): Preset? = _state.value.presets.firstOrNull { it.id == id }

    suspend fun upsertPreset(preset: Preset) {
        mutate { state ->
            val without = state.presets.filterNot { it.id == preset.id }
            state.copy(presets = without + preset)
        }
    }

    suspend fun deletePreset(id: String) {
        mutate { state -> state.copy(presets = state.presets.filterNot { it.id == id }) }
    }

    suspend fun presetCount(): Int = _state.value.presets.size

    private suspend fun mutate(transform: (ConversationStore) -> ConversationStore) {
        _state.value = transform(_state.value)
        store.write(ConversationStore.serializer(), _state.value)
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 60
    }
}

/** The on-disk shape: three flat lists, keyed by id rather than nested. */
@Serializable
private data class ConversationStore(
    val conversations: List<Conversation> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val presets: List<Preset> = emptyList(),
) {
    fun messagesFor(conversationId: String): List<ChatMessage> =
        messages.filter { it.conversationId == conversationId }.sortedBy { it.sequence }

    /**
     * The drawer's list — ordinary conversations only.
     *
     * An agent run is a real conversation, which is what makes every transcript feature work on it
     * for free, but a daily agent contributes a thread a day to this list and after a fortnight the
     * list is mostly machine. Runs are not hidden; they live on the agent that wrote them.
     */
    fun summaries(archived: Boolean): List<ConversationSummary> =
        conversations
            .filter { it.archived == archived && it.agentId == null }
            .sortedByDescending { it.updatedAt }
            .map { conversation ->
                val thread = messagesFor(conversation.id)
                ConversationSummary(
                    conversation = conversation,
                    messageCount = thread.size,
                    lastMessagePreview = thread.lastOrNull()?.content?.take(120),
                )
            }
}
