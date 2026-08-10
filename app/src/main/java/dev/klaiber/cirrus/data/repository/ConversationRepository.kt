package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.local.EntityMapper
import dev.klaiber.cirrus.data.local.dao.ConversationDao
import dev.klaiber.cirrus.data.local.dao.MessageDao
import dev.klaiber.cirrus.data.local.dao.PresetDao
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
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val presetDao: PresetDao,
    private val mapper: EntityMapper,
) {

    fun observeSummaries(archived: Boolean = false): Flow<List<ConversationSummary>> =
        conversationDao.observeSummaries(archived).map { rows -> rows.map(mapper::toDomain) }

    fun searchConversations(query: String): Flow<List<ConversationSummary>> =
        conversationDao.search(query).map { rows -> rows.map(mapper::toDomain) }

    fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).map { entity -> entity?.let(mapper::toDomain) }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeForConversation(conversationId).map { rows -> rows.map(mapper::toDomain) }

    suspend fun getConversation(id: String): Conversation? =
        conversationDao.getById(id)?.let(mapper::toDomain)

    suspend fun getMessages(conversationId: String): List<ChatMessage> =
        messageDao.getForConversation(conversationId).map(mapper::toDomain)

    suspend fun createConversation(
        model: String,
        systemPrompt: String? = null,
        params: GenerationParams = GenerationParams.Default,
        toolsEnabled: Boolean = false,
        title: String = Conversation.DEFAULT_TITLE,
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
        )
        conversationDao.upsert(mapper.toEntity(conversation))
        return conversation
    }

    suspend fun updateConversation(conversation: Conversation) {
        conversationDao.update(
            mapper.toEntity(conversation.copy(updatedAt = System.currentTimeMillis())),
        )
    }

    suspend fun rename(id: String, title: String) = conversationDao.rename(
        id,
        title.trim().ifEmpty { Conversation.DEFAULT_TITLE },
        System.currentTimeMillis(),
    )

    /** Records a title the model wrote, along with the moment it was written. */
    suspend fun applyAutoTitle(id: String, title: String, now: Long = System.currentTimeMillis()) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        conversationDao.applyAutoTitle(id, clean, now)
    }

    suspend fun setPinned(id: String, pinned: Boolean) =
        conversationDao.setPinned(id, pinned, System.currentTimeMillis())

    suspend fun setArchived(id: String, archived: Boolean) =
        conversationDao.setArchived(id, archived, System.currentTimeMillis())

    suspend fun deleteConversation(id: String) = conversationDao.delete(id)

    suspend fun deleteAllConversations() = conversationDao.deleteAll()

    /**
     * Appends a message and returns it with its assigned sequence.
     *
     * Sequences are allocated from the current maximum so that deleting or regenerating earlier
     * messages never produces a collision.
     */
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
        val sequence = messageDao.maxSequence(conversationId) + 1
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
        messageDao.upsert(mapper.toEntity(message))
        if (message.attachments.isNotEmpty()) {
            messageDao.upsertAttachments(message.attachments.map(mapper::toEntity))
        }
        conversationDao.touch(conversationId, message.createdAt)
        return message
    }

    /** Persists the accumulated state of a streaming assistant message. */
    suspend fun updateMessageContent(
        messageId: String,
        content: String,
        thinking: String?,
        stats: GenerationStats?,
        toolInvocations: List<ToolInvocation>,
        errorMessage: String?,
    ) {
        messageDao.updateContent(
            id = messageId,
            content = content,
            thinking = thinking,
            statsJson = stats?.let(mapper::encodeStats),
            toolsJson = toolInvocations.takeIf { it.isNotEmpty() }?.let(mapper::encodeToolInvocations),
            errorMessage = errorMessage,
        )
    }

    suspend fun editMessageText(messageId: String, content: String) {
        val existing = messageDao.getById(messageId) ?: return
        messageDao.upsert(existing.message.copy(content = content))
        conversationDao.touch(existing.message.conversationId, System.currentTimeMillis())
    }

    suspend fun deleteMessage(messageId: String) = messageDao.delete(messageId)

    /** Removes [messageId] and everything after it, used before regenerating a response. */
    suspend fun truncateFrom(messageId: String) {
        val target = messageDao.getById(messageId) ?: return
        messageDao.deleteFromSequence(target.message.conversationId, target.message.sequence)
    }

    /**
     * Copies a conversation up to and including [throughMessageId] into a new thread.
     *
     * Branching keeps the original intact so alternative continuations can be compared, which is
     * the reason edits and regenerations offer "branch" alongside "replace".
     */
    suspend fun fork(conversationId: String, throughMessageId: String): Conversation? {
        val source = conversationDao.getById(conversationId) ?: return null
        val messages = messageDao.getForConversation(conversationId)
        val cutoffIndex = messages.indexOfFirst { it.message.id == throughMessageId }
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
        )
        conversationDao.upsert(fork)

        messages.take(cutoffIndex + 1).forEach { row ->
            val newMessageId = UUID.randomUUID().toString()
            messageDao.upsert(row.message.copy(id = newMessageId, conversationId = forkId))
            if (row.attachments.isNotEmpty()) {
                messageDao.upsertAttachments(
                    row.attachments.map { attachment ->
                        attachment.copy(
                            id = UUID.randomUUID().toString(),
                            messageId = newMessageId,
                        )
                    },
                )
            }
        }
        return mapper.toDomain(fork)
    }

    fun observePresets(): Flow<List<Preset>> =
        presetDao.observeAll().map { rows -> rows.map(mapper::toDomain) }

    suspend fun getPreset(id: String): Preset? = presetDao.getById(id)?.let(mapper::toDomain)

    suspend fun upsertPreset(preset: Preset) = presetDao.upsert(mapper.toEntity(preset))

    suspend fun deletePreset(id: String) = presetDao.delete(id)

    suspend fun presetCount(): Int = presetDao.count()

    private companion object {
        const val MAX_TITLE_LENGTH = 60
    }
}
