package dev.klaiber.cirrus.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.klaiber.cirrus.data.local.entity.ConversationEntity
import dev.klaiber.cirrus.data.local.entity.ConversationSummaryRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Transaction
    @Query(
        """
        SELECT c.*,
            (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id) AS messageCount,
            (
                SELECT m2.content FROM messages m2
                WHERE m2.conversationId = c.id AND m2.content != ''
                ORDER BY m2.sequence DESC LIMIT 1
            ) AS lastMessagePreview
        FROM conversations c
        WHERE c.archived = :archived
        ORDER BY c.pinned DESC, c.updatedAt DESC
        """,
    )
    fun observeSummaries(archived: Boolean = false): Flow<List<ConversationSummaryRow>>

    /**
     * Matches the title or any message body. `LIKE` with a leading wildcard cannot use an index,
     * but conversation counts here are personal-scale and this keeps the schema migration-free.
     */
    @Transaction
    @Query(
        """
        SELECT c.*,
            (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id) AS messageCount,
            (
                SELECT m2.content FROM messages m2
                WHERE m2.conversationId = c.id AND m2.content != ''
                ORDER BY m2.sequence DESC LIMIT 1
            ) AS lastMessagePreview
        FROM conversations c
        WHERE c.title LIKE '%' || :query || '%' COLLATE NOCASE
           OR EXISTS (
                SELECT 1 FROM messages m3
                WHERE m3.conversationId = c.id
                  AND m3.content LIKE '%' || :query || '%' COLLATE NOCASE
           )
        ORDER BY c.pinned DESC, c.updatedAt DESC
        """,
    )
    fun search(query: String): Flow<List<ConversationSummaryRow>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    /** Threads touched since a point in time, newest first — what the nightly pass reads. */
    @Query(
        """
        SELECT * FROM conversations
        WHERE updatedAt > :since AND archived = 0
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun updatedSince(since: Long, limit: Int): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    /** A hand-typed title clears [ConversationEntity.autoTitledAt] so nothing overwrites it. */
    @Query("UPDATE conversations SET title = :title, autoTitledAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    /**
     * Writes a model-generated title.
     *
     * `updatedAt` is deliberately left alone: re-titling is bookkeeping, and touching it would
     * reshuffle the drawer, which is sorted by that column.
     */
    @Query("UPDATE conversations SET title = :title, autoTitledAt = :now WHERE id = :id")
    suspend fun applyAutoTitle(id: String, title: String, now: Long)

    @Query("UPDATE conversations SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE conversations SET archived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, now: Long)

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
