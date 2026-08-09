package dev.klaiber.cirrus.data.local

import dev.klaiber.cirrus.data.local.entity.AttachmentEntity
import dev.klaiber.cirrus.data.local.entity.ConversationEntity
import dev.klaiber.cirrus.data.local.entity.ConversationSummaryRow
import dev.klaiber.cirrus.data.local.entity.MessageEntity
import dev.klaiber.cirrus.data.local.entity.MessageWithAttachments
import dev.klaiber.cirrus.data.local.entity.PresetEntity
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.ConversationSummary
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.GenerationStats
import dev.klaiber.cirrus.domain.model.Preset
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.ToolInvocation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Entity <-> domain conversion.
 *
 * Structured sub-objects (params, stats, tool invocations) are stored as JSON columns rather
 * than separate tables: they are always read and written together with their owner row, and
 * keeping them opaque means adding a sampling knob needs no schema migration.
 */
class EntityMapper(private val json: Json) {

    fun toDomain(entity: ConversationEntity): Conversation = Conversation(
        id = entity.id,
        title = entity.title,
        model = entity.model,
        systemPrompt = entity.systemPrompt,
        params = decodeParams(entity.paramsJson),
        toolsEnabled = entity.toolsEnabled,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        pinned = entity.pinned,
        archived = entity.archived,
        forkedFromConversationId = entity.forkedFromConversationId,
        forkedFromMessageId = entity.forkedFromMessageId,
        autoTitledAt = entity.autoTitledAt,
    )

    fun toEntity(model: Conversation): ConversationEntity = ConversationEntity(
        id = model.id,
        title = model.title,
        model = model.model,
        systemPrompt = model.systemPrompt,
        paramsJson = json.encodeToString(GenerationParams.serializer(), model.params),
        toolsEnabled = model.toolsEnabled,
        createdAt = model.createdAt,
        updatedAt = model.updatedAt,
        pinned = model.pinned,
        archived = model.archived,
        forkedFromConversationId = model.forkedFromConversationId,
        forkedFromMessageId = model.forkedFromMessageId,
        autoTitledAt = model.autoTitledAt,
    )

    fun toDomain(row: ConversationSummaryRow): ConversationSummary = ConversationSummary(
        conversation = toDomain(row.conversation),
        messageCount = row.messageCount,
        lastMessagePreview = row.lastMessagePreview,
    )

    fun toDomain(entity: MessageWithAttachments): ChatMessage = ChatMessage(
        id = entity.message.id,
        conversationId = entity.message.conversationId,
        role = Role.fromWire(entity.message.role),
        content = entity.message.content,
        thinking = entity.message.thinking,
        createdAt = entity.message.createdAt,
        sequence = entity.message.sequence,
        model = entity.message.model,
        stats = entity.message.statsJson?.let(::decodeStats),
        toolInvocations = entity.message.toolInvocationsJson?.let(::decodeToolInvocations).orEmpty(),
        errorMessage = entity.message.errorMessage,
        isStreaming = false,
        attachments = entity.attachments.map(::toDomain),
        rawRequestJson = entity.message.rawRequestJson,
    )

    fun toEntity(model: ChatMessage): MessageEntity = MessageEntity(
        id = model.id,
        conversationId = model.conversationId,
        role = model.role.wire,
        content = model.content,
        thinking = model.thinking,
        createdAt = model.createdAt,
        sequence = model.sequence,
        model = model.model,
        statsJson = model.stats?.let { json.encodeToString(GenerationStats.serializer(), it) },
        toolInvocationsJson = model.toolInvocations
            .takeIf { it.isNotEmpty() }
            ?.let { json.encodeToString(ListSerializer(ToolInvocation.serializer()), it) },
        errorMessage = model.errorMessage,
        rawRequestJson = model.rawRequestJson,
    )

    fun toDomain(entity: AttachmentEntity): Attachment = Attachment(
        id = entity.id,
        messageId = entity.messageId,
        displayName = entity.displayName,
        mimeType = entity.mimeType,
        sizeBytes = entity.sizeBytes,
        localPath = entity.localPath,
        kind = runCatching { Attachment.Kind.valueOf(entity.kind) }
            .getOrDefault(Attachment.Kind.DOCUMENT),
        extractedText = entity.extractedText,
    )

    fun toEntity(model: Attachment): AttachmentEntity = AttachmentEntity(
        id = model.id,
        messageId = model.messageId,
        displayName = model.displayName,
        mimeType = model.mimeType,
        sizeBytes = model.sizeBytes,
        localPath = model.localPath,
        kind = model.kind.name,
        extractedText = model.extractedText,
    )

    fun toDomain(entity: PresetEntity): Preset = Preset(
        id = entity.id,
        name = entity.name,
        description = entity.description,
        systemPrompt = entity.systemPrompt,
        model = entity.model,
        params = decodeParams(entity.paramsJson),
        toolsEnabled = entity.toolsEnabled,
        createdAt = entity.createdAt,
    )

    fun toEntity(model: Preset): PresetEntity = PresetEntity(
        id = model.id,
        name = model.name,
        description = model.description,
        systemPrompt = model.systemPrompt,
        model = model.model,
        paramsJson = json.encodeToString(GenerationParams.serializer(), model.params),
        toolsEnabled = model.toolsEnabled,
        createdAt = model.createdAt,
    )

    fun encodeStats(stats: GenerationStats): String =
        json.encodeToString(GenerationStats.serializer(), stats)

    fun encodeToolInvocations(invocations: List<ToolInvocation>): String =
        json.encodeToString(ListSerializer(ToolInvocation.serializer()), invocations)

    // A stored blob that no longer parses should degrade to defaults, never crash the app.
    private fun decodeParams(raw: String): GenerationParams =
        runCatching { json.decodeFromString(GenerationParams.serializer(), raw) }
            .getOrDefault(GenerationParams.Default)

    private fun decodeStats(raw: String): GenerationStats? =
        runCatching { json.decodeFromString(GenerationStats.serializer(), raw) }.getOrNull()

    private fun decodeToolInvocations(raw: String): List<ToolInvocation> =
        runCatching { json.decodeFromString(ListSerializer(ToolInvocation.serializer()), raw) }
            .getOrDefault(emptyList())
}
