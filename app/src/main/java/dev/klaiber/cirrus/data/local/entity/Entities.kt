package dev.klaiber.cirrus.data.local.entity

import androidx.room.ColumnInfo
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
        Index("agentId"),
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
    /**
     * The agent that wrote this thread, if any.
     *
     * Non-null means the thread was produced by a scheduled run and belongs on the agent's own
     * screen rather than in the drawer. Indexed because every drawer query filters on it.
     */
    val agentId: String?,
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
    /**
     * How many finished runs to keep. Older threads are deleted after each run.
     *
     * The default is declared here as well as in the migration so both sides of Room's schema
     * check agree: a column added with a default that the entity does not know about is the kind
     * of mismatch that only shows up as a crash on someone else's upgrade.
     */
    @ColumnInfo(defaultValue = "10")
    val keepRuns: Int,
)

/**
 * One attempt at running an agent.
 *
 * Kept apart from the agent row, which only ever remembers the latest attempt: "it worked this
 * morning" and "it has failed every morning this week" look identical from a single column, and the
 * second is the only one worth being told about.
 *
 * The link to the conversation is `SET NULL` rather than `CASCADE`: deleting the thread an agent
 * wrote should lose the text, not the record that the agent ran at all.
 */
@Entity(
    tableName = "agent_runs",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["agentId", "startedAt"]), Index("conversationId")],
)
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val conversationId: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String,
    /** SCHEDULED or MANUAL — a failure you caused by tapping "run now" reads differently. */
    val trigger: String,
    val summary: String?,
    val errorMessage: String?,
    val toolCalls: Int,
    val tokens: Int?,
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
