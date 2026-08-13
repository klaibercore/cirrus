package dev.klaiber.cirrus.testing

import dev.klaiber.cirrus.data.local.dao.MemoryDao
import dev.klaiber.cirrus.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A working [MemoryDao] backed by a map.
 *
 * The existing suite has a DAO that answers empty to everything, which is enough to keep the tool
 * registry happy but cannot exercise anything that reads back what it wrote — and the interesting
 * behaviour in `MemoryRepository` is exactly that: whether a new memory folds into an existing one,
 * and what survives the merge. Room needs an Android runtime and so is out of reach of a JVM test,
 * but nothing about the fold decision is SQL, so a map is a faithful stand-in.
 *
 * The ordering of each query mirrors the `@Query` annotations it replaces. That matters more than
 * it looks: `findNearDuplicate` takes the *first* candidate that clears the threshold, so a DAO
 * that returned rows in a different order would test a different function.
 */
class InMemoryMemoryDao : MemoryDao {

    private val rows = MutableStateFlow<Map<String, MemoryEntity>>(emptyMap())

    /** Everything currently stored, newest first — for assertions rather than production reads. */
    val all: List<MemoryEntity> get() = rows.value.values.sortedByDescending { it.updatedAt }

    override fun observeActive(): Flow<List<MemoryEntity>> = rows.map { stored ->
        stored.values.filterNot { it.archived }
            .sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.updatedAt })
    }

    override fun observeAll(): Flow<List<MemoryEntity>> = rows.map { stored ->
        stored.values.sortedWith(
            compareBy<MemoryEntity> { it.archived }
                .thenByDescending { it.pinned }
                .thenByDescending { it.updatedAt },
        )
    }

    override fun observeActiveCount(): Flow<Int> = rows.map { stored ->
        stored.values.count { !it.archived }
    }

    override suspend fun activeMemories(): List<MemoryEntity> =
        rows.value.values.filterNot { it.archived }

    override suspend fun pinned(): List<MemoryEntity> =
        rows.value.values.filter { !it.archived && it.pinned }.sortedByDescending { it.updatedAt }

    override suspend fun byId(id: String): MemoryEntity? = rows.value[id]

    override suspend fun upsert(memory: MemoryEntity) {
        rows.value = rows.value + (memory.id to memory)
    }

    override suspend fun update(memory: MemoryEntity) {
        // Room's @Update is a no-op for a row that is not there; matching that keeps a test from
        // passing on a write the real DAO would have dropped.
        if (memory.id in rows.value) rows.value = rows.value + (memory.id to memory)
    }

    override suspend fun markRecalled(ids: List<String>, at: Long) {
        rows.value = rows.value.mapValues { (id, entity) ->
            if (id in ids) {
                entity.copy(lastRecalledAt = at, recallCount = entity.recallCount + 1)
            } else {
                entity
            }
        }
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}
