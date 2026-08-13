package dev.klaiber.cirrus.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.klaiber.cirrus.data.local.entity.AgentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {

    @Query("SELECT * FROM agents ORDER BY enabled DESC, minuteOfDay ASC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE enabled = 1")
    suspend fun enabled(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun byId(id: String): AgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentEntity)

    @Query(
        """
        UPDATE agents
        SET lastRunAt = :at, lastStatus = :status, lastSummary = :summary,
            lastConversationId = :conversationId
        WHERE id = :id
        """,
    )
    suspend fun recordRun(
        id: String,
        at: Long,
        status: String,
        summary: String?,
        conversationId: String?,
    )

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun delete(id: String)
}
