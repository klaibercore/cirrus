package dev.klaiber.cirrus.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.klaiber.cirrus.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY archived ASC, pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE archived = 0")
    suspend fun activeMemories(): List<MemoryEntity>

    /** Pinned memories ride along on every turn, so this is the hottest read in the app. */
    @Query("SELECT * FROM memories WHERE archived = 0 AND pinned = 1 ORDER BY updatedAt DESC")
    suspend fun pinned(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: String): MemoryEntity?

    @Query("SELECT COUNT(*) FROM memories WHERE archived = 0")
    fun observeActiveCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("UPDATE memories SET lastRecalledAt = :at, recallCount = recallCount + 1 WHERE id IN (:ids)")
    suspend fun markRecalled(ids: List<String>, at: Long)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
