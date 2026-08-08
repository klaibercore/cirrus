package dev.klaiber.cirrus.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.klaiber.cirrus.data.local.entity.AttachmentEntity
import dev.klaiber.cirrus.data.local.entity.MessageEntity
import dev.klaiber.cirrus.data.local.entity.MessageWithAttachments
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageWithAttachments>>

    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun getForConversation(conversationId: String): List<MessageWithAttachments>

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageWithAttachments?

    @Query("SELECT COALESCE(MAX(sequence), -1) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxSequence(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Query(
        """
        UPDATE messages
        SET content = :content, thinking = :thinking, statsJson = :statsJson,
            toolInvocationsJson = :toolsJson, errorMessage = :errorMessage
        WHERE id = :id
        """,
    )
    suspend fun updateContent(
        id: String,
        content: String,
        thinking: String?,
        statsJson: String?,
        toolsJson: String?,
        errorMessage: String?,
    )

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Used when regenerating: drops everything after the message being retried. */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND sequence >= :fromSequence")
    suspend fun deleteFromSequence(conversationId: String, fromSequence: Int)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Transaction
    @Query(
        """
        SELECT * FROM messages
        WHERE content LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 200): List<MessageWithAttachments>
}
