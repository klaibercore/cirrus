package dev.klaiber.cirrus.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "conversations",
    indices = [
        Index("updatedAt"),
        Index("archived"),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val systemPrompt: String?,
    /** [dev.klaiber.cirrus.domain.model.GenerationParams] serialized as JSON. */
    val paramsJson: String,
    val toolsEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val forkedFromConversationId: String?,
    val forkedFromMessageId: String?,
    /**
     * When the model last wrote this title. Null means the title is the user's — either typed by
     * hand or still the placeholder — and auto-titling must leave it alone.
     */
    val autoTitledAt: Long?,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId", "sequence"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val thinking: String?,
    val createdAt: Long,
    /** Monotonic position within the conversation; gaps are allowed after edits. */
    val sequence: Int,
    val model: String?,
    val statsJson: String?,
    val toolInvocationsJson: String?,
    val errorMessage: String?,
    /** Captured only while developer mode is enabled. */
    val rawRequestJson: String?,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Absolute path inside app-private storage; the original content URI may expire. */
    val localPath: String,
    val kind: String,
    val extractedText: String?,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val systemPrompt: String,
    val model: String?,
    val paramsJson: String,
    val toolsEnabled: Boolean,
    val createdAt: Long,
)

/**
 * A remembered sentence.
 *
 * Indexed on the two things every read filters by: whether it is retired, and whether it is
 * pinned — pinned memories are fetched on every single turn, so that lookup has to be cheap.
 */
@Entity(
    tableName = "memories",
    indices = [Index("archived"), Index("pinned"), Index("updatedAt")],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val kind: String,
    val sourceConversationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRecalledAt: Long?,
    val recallCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val confidence: Float,
)

/** A scheduled prompt. [daysMask] packs the weekdays into seven bits, Monday first. */
@Entity(tableName = "agents", indices = [Index("enabled")])
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val model: String?,
    val minuteOfDay: Int,
    val daysMask: Int,
    val enabled: Boolean,
    val toolsEnabled: Boolean,
    val notifyOnFinish: Boolean,
    val createdAt: Long,
    val lastRunAt: Long?,
    val lastStatus: String?,
    val lastSummary: String?,
    val lastConversationId: String?,
)

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<AttachmentEntity>,
)

/** Conversation row plus the aggregates the drawer shows, computed in SQL. */
data class ConversationSummaryRow(
    @Embedded val conversation: ConversationEntity,
    val messageCount: Int,
    val lastMessagePreview: String?,
)
