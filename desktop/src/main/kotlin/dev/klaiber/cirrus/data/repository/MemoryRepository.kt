package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.domain.memory.MemoryRetriever
import dev.klaiber.cirrus.domain.model.Memory
import dev.klaiber.cirrus.domain.model.MemoryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * What Cirrus remembers between conversations, backed by a JSON file.
 *
 * Writes go through [remember], which folds a new memory into a near-duplicate rather than
 * appending it. Without that the store fills up with five phrasings of the same fact within a
 * week, and every recall then spends its budget saying the same thing five times.
 */
class MemoryRepository(
    private val store: JsonStore,
) {

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())

    suspend fun load() {
        _memories.value = store.read(ListSerializer(Memory.serializer())) { emptyList() }
    }

    val memories: Flow<List<Memory>> = _memories.map { rows -> rows.filterNot { it.archived } }

    /** Includes archived rows, for the viewer's "retired" section. */
    val allMemories: Flow<List<Memory>> = _memories

    val activeCount: Flow<Int> = _memories.map { rows -> rows.count { !it.archived } }

    suspend fun pinned(): List<Memory> = _memories.value.filter { it.pinned && !it.archived }

    suspend fun active(): List<Memory> = _memories.value.filterNot { it.archived }

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
            val merged = existing.copy(
                content = mergedContent(existing.content, text),
                kind = kind,
                updatedAt = now,
                pinned = existing.pinned || pinned,
                confidence = (existing.confidence + CONFIRMATION_BOOST).coerceAtMost(1f),
                archived = false,
            )
            mutate { rows -> rows.map { if (it.id == merged.id) merged else it } }
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
        mutate { it + memory }
        return RememberResult(memory, wasNew = true)
    }

    suspend fun recall(query: String, limit: Int = DEFAULT_RECALL): List<Memory> {
        val hits = MemoryRetriever.rank(active(), query, limit)
        if (hits.isNotEmpty()) {
            val now = System.currentTimeMillis()
            mutate { rows ->
                rows.map { memory ->
                    if (hits.any { it.id == memory.id }) {
                        memory.copy(lastRecalledAt = now, recallCount = memory.recallCount + 1)
                    } else {
                        memory
                    }
                }
            }
        }
        return hits
    }

    suspend fun update(memory: Memory) {
        mutate { rows -> rows.map { if (it.id == memory.id) memory.copy(updatedAt = System.currentTimeMillis()) else it } }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        mutate { rows ->
            rows.map { if (it.id == id) it.copy(pinned = pinned, updatedAt = System.currentTimeMillis()) else it }
        }
    }

    suspend fun archive(id: String, archived: Boolean = true) {
        mutate { rows ->
            rows.map { if (it.id == id) it.copy(archived = archived, updatedAt = System.currentTimeMillis()) else it }
        }
    }

    suspend fun delete(id: String) {
        mutate { rows -> rows.filterNot { it.id == id } }
    }

    suspend fun deleteAll() {
        mutate { emptyList() }
    }

    suspend fun byId(id: String): Memory? = _memories.value.firstOrNull { it.id == id }

    private suspend fun mutate(transform: (List<Memory>) -> List<Memory>) {
        _memories.value = transform(_memories.value)
        store.write(ListSerializer(Memory.serializer()), _memories.value)
    }

    private fun mergedContent(existing: String, incoming: String): String {
        val known = MemoryRetriever.tokenize(existing)
        val offered = MemoryRetriever.tokenize(incoming)
        return if (offered.any { it !in known }) incoming else existing
    }

    private suspend fun findNearDuplicate(content: String): Memory? {
        val terms = MemoryRetriever.tokenize(content)
        if (terms.isEmpty()) return null
        return active().firstOrNull { candidate ->
            val other = MemoryRetriever.tokenize(candidate.content)
            if (other.isEmpty()) return@firstOrNull false
            val shared = terms.intersect(other).size.toFloat()
            shared / minOf(terms.size, other.size).toFloat() >= DUPLICATE_THRESHOLD
        }
    }

    data class RememberResult(val memory: Memory?, val wasNew: Boolean)

    private companion object {
        const val DEFAULT_RECALL = 6
        const val DUPLICATE_THRESHOLD = 0.75f
        const val CONFIRMATION_BOOST = 0.1f
    }
}
