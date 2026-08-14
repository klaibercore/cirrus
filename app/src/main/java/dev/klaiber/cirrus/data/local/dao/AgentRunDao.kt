package dev.klaiber.cirrus.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.klaiber.cirrus.data.local.entity.AgentRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRunDao {

    @Query("SELECT * FROM agent_runs WHERE agentId = :agentId ORDER BY startedAt DESC LIMIT :limit")
    fun observeForAgent(agentId: String, limit: Int): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AgentRunEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    suspend fun byId(id: String): AgentRunEntity?

    /**
     * Runs that claim to still be in progress but cannot be.
     *
     * A run only ever ends by writing `finishedAt`, so a row without one after the process has
     * restarted is a run that was killed mid-flight — by the OS reclaiming the app, by a reboot, or
     * by the work manager's own deadline. Left alone it shows as a spinner that never stops.
     */
    @Query("SELECT * FROM agent_runs WHERE finishedAt IS NULL AND startedAt < :before")
    suspend fun unfinishedBefore(before: Long): List<AgentRunEntity>

    @Query(
        """
        UPDATE agent_runs
        SET status = :status, errorMessage = :errorMessage, finishedAt = :finishedAt
        WHERE id = :id
        """,
    )
    suspend fun finish(id: String, status: String, errorMessage: String?, finishedAt: Long)

    /** The conversations this agent wrote, oldest first — what retention walks. */
    @Query(
        """
        SELECT conversationId FROM agent_runs
        WHERE agentId = :agentId AND conversationId IS NOT NULL
        ORDER BY startedAt DESC
        LIMIT -1 OFFSET :keep
        """,
    )
    suspend fun conversationsBeyond(agentId: String, keep: Int): List<String>

    /** Drops run rows past the point where they are still history rather than clutter. */
    @Query(
        """
        DELETE FROM agent_runs
        WHERE agentId = :agentId AND id NOT IN (
            SELECT id FROM agent_runs WHERE agentId = :agentId
            ORDER BY startedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(agentId: String, keep: Int)

    @Query("DELETE FROM agent_runs WHERE agentId = :agentId")
    suspend fun deleteForAgent(agentId: String)
}
