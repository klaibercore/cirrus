package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.local.dao.MemoryDao
import dev.klaiber.cirrus.data.local.entity.MemoryEntity
import dev.klaiber.cirrus.domain.memory.MemoryRetriever
import dev.klaiber.cirrus.domain.model.Memory
import dev.klaiber.cirrus.domain.model.MemoryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Cirrus remembers between conversations.
 *
 * Writes go through [remember], which folds a new memory into a near-duplicate rather than
 * appending it. Without that the store fills up with five phrasings of the same fact within a
 * week, and every recall then spends its budget saying the same thing five times.
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val dao: MemoryDao,
) {

    val memories: Flow<List<Memory>> = dao.observeActive().map { rows -> rows.map(::toDomain) }

    /** Includes archived rows, for the viewer's "retired" section. */
    val allMemories: Flow<List<Memory>> = dao.observeAll().map { rows -> rows.map(::toDomain) }

    val activeCount: Flow<Int> = dao.observeActiveCount()

    suspend fun pinned(): List<Memory> = dao.pinned().map(::toDomain)

    suspend fun active(): List<Memory> = dao.activeMemories().map(::toDomain)

    /**
     * Records something worth keeping, or updates what is already there.
     *
     * Returns the memory as stored, so a caller can tell the model whether it created something
     * new or refreshed a fact it had already been told.
     */
    suspend fun remember(
        content: String,
        kind: MemoryKind = MemoryKind.FACT,
        sourceConversationId: String? = null,
        pinned: Boolean = false,
    ): RememberResult {
        val text = content.trim().take(Memory.MAX_CONTENT_CHARS)
        if (text.isEmpty()) return RememberResult(null, wasNew = false)

        val existing = findNearDuplicate(text)
        val now = System.currentTimeMillis()

        if (existing != null) {
            // The newer phrasing wins, and being told again is evidence the fact is real.
            val merged = existing.copy(
                content = text,
                kind = kind,
                updatedAt = now,
                pinned = existing.pinned || pinned,
                confidence = (existing.confidence + CONFIRMATION_BOOST).coerceAtMost(1f),
                archived = false,
            )
            dao.update(toEntity(merged))
            return RememberResult(merged, wasNew = false)
        }

        val memory = Memory(
            id = UUID.randomUUID().toString(),
            content = text,
            kind = kind,
            sourceConversationId = sourceConversationId,
            createdAt = now,
            updatedAt = now,
            lastRecalledAt = null,
            recallCount = 0,
            pinned = pinned,
            archived = false,
            confidence = Memory.DEFAULT_CONFIDENCE,
        )
        dao.upsert(toEntity(memory))
        return RememberResult(memory, wasNew = true)
    }

    /**
     * The memories most relevant to [query], marked as recalled.
     *
     * The bookkeeping is the point of doing this here rather than in the retriever: a memory that
     * keeps proving useful should outrank one that never does, and that only works if every real
     * recall is counted.
     */
    suspend fun recall(query: String, limit: Int = DEFAULT_RECALL): List<Memory> {
        val hits = MemoryRetriever.rank(active(), query, limit)
        if (hits.isNotEmpty()) {
            dao.markRecalled(hits.map { it.id }, System.currentTimeMillis())
        }
        return hits
    }

    suspend fun update(memory: Memory) {
        dao.update(toEntity(memory.copy(updatedAt = System.currentTimeMillis())))
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        val existing = dao.byId(id) ?: return
        dao.update(existing.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))
    }

    /** Retires a memory without losing it; the viewer can bring it back. */
    suspend fun archive(id: String, archived: Boolean = true) {
        val existing = dao.byId(id) ?: return
        dao.update(existing.copy(archived = archived, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun byId(id: String): Memory? = dao.byId(id)?.let(::toDomain)

    /**
     * An existing memory saying substantially the same thing.
     *
     * Term overlap rather than string equality, because "prefers Kotlin" and "prefers Kotlin over
     * Java" are the same memory and should not both be kept.
     */
    private suspend fun findNearDuplicate(content: String): Memory? {
        val terms = MemoryRetriever.tokenize(content)
        if (terms.isEmpty()) return null
        return dao.activeMemories().map(::toDomain).firstOrNull { candidate ->
            val other = MemoryRetriever.tokenize(candidate.content)
            if (other.isEmpty()) return@firstOrNull false
            val shared = terms.intersect(other).size.toFloat()
            shared / minOf(terms.size, other.size).toFloat() >= DUPLICATE_THRESHOLD
        }
    }

    private fun toDomain(entity: MemoryEntity) = Memory(
        id = entity.id,
        content = entity.content,
        kind = MemoryKind.fromName(entity.kind),
        sourceConversationId = entity.sourceConversationId,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        lastRecalledAt = entity.lastRecalledAt,
        recallCount = entity.recallCount,
        pinned = entity.pinned,
        archived = entity.archived,
        confidence = entity.confidence,
    )

    private fun toEntity(memory: Memory) = MemoryEntity(
        id = memory.id,
        content = memory.content,
        kind = memory.kind.name,
        sourceConversationId = memory.sourceConversationId,
        createdAt = memory.createdAt,
        updatedAt = memory.updatedAt,
        lastRecalledAt = memory.lastRecalledAt,
        recallCount = memory.recallCount,
        pinned = memory.pinned,
        archived = memory.archived,
        confidence = memory.confidence,
    )

    data class RememberResult(val memory: Memory?, val wasNew: Boolean)

    private companion object {
        const val DEFAULT_RECALL = 6

        /** Share of the shorter memory's terms that must match before the two are treated as one. */
        const val DUPLICATE_THRESHOLD = 0.75f
        const val CONFIRMATION_BOOST = 0.1f
    }
}
