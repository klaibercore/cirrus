package dev.klaiber.cirrus.domain.memory

import dev.klaiber.cirrus.domain.model.Memory
import dev.klaiber.cirrus.domain.model.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What comes back when the model asks what it knows.
 *
 * The scoring is the whole feature: recall that returns the wrong six memories is worse than no
 * recall at all, because the model will use them.
 */
class MemoryRetrieverTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun memory(
        content: String,
        pinned: Boolean = false,
        ageDays: Long = 1,
        recallCount: Int = 0,
        kind: MemoryKind = MemoryKind.FACT,
    ) = Memory(
        id = content.hashCode().toString(),
        content = content,
        kind = kind,
        sourceConversationId = null,
        createdAt = now - ageDays * day,
        updatedAt = now - ageDays * day,
        lastRecalledAt = null,
        recallCount = recallCount,
        pinned = pinned,
        archived = false,
        confidence = Memory.DEFAULT_CONFIDENCE,
    )

    private val store = listOf(
        memory("Prefers Kotlin and dislikes Java's ceremony"),
        memory("Works on an Android chat client called Cirrus"),
        memory("Drinks coffee black, and only before noon"),
        memory("The database is Postgres, hosted on Hetzner"),
        memory("Ships on Fridays, and never on a Friday afternoon"),
    )

    @Test
    fun `finds the memory that shares the rare words`() {
        val hits = MemoryRetriever.rank(store, "what database are we using", limit = 2, now = now)
        assertTrue(hits.first().content.contains("Postgres"))
    }

    @Test
    fun `a query about nothing in the store returns nothing`() {
        // Padding an empty recall with the freshest memories is how a model starts confidently
        // using facts nobody asked about.
        assertEquals(emptyList<Memory>(), MemoryRetriever.rank(store, "quantum chromodynamics", now = now))
    }

    @Test
    fun `partial words still match`() {
        val hits = MemoryRetriever.rank(store, "android", limit = 1, now = now)
        assertTrue(hits.single().content.contains("Android"))
    }

    @Test
    fun `an empty query falls back to pinned then recent`() {
        val memories = listOf(
            memory("Old but pinned", pinned = true, ageDays = 400),
            memory("Fresh and unpinned", ageDays = 0),
        )
        val hits = MemoryRetriever.rank(memories, "   ", limit = 2, now = now)
        assertEquals("Old but pinned", hits.first().content)
    }

    @Test
    fun `common words do not decide the ranking`() {
        // Both mention "the"; only one is about deployment. Without rarity weighting the tie is
        // broken by recency, which is the wrong answer.
        val memories = listOf(
            memory("The kitchen light is on a timer"),
            memory("The deployment runs through GitHub Actions", ageDays = 30),
        )
        val hits = MemoryRetriever.rank(memories, "how does the deployment work", limit = 1, now = now)
        assertTrue(hits.single().content.contains("deployment"))
    }

    @Test
    fun `a pinned memory outranks an equal unpinned one`() {
        val memories = listOf(
            memory("Postgres is the database"),
            memory("Postgres is the database, pinned", pinned = true),
        )
        val hits = MemoryRetriever.rank(memories, "postgres database", limit = 2, now = now)
        assertTrue(hits.first().pinned)
    }

    @Test
    fun `a memory that keeps proving useful climbs`() {
        val memories = listOf(
            memory("Deploys with Docker Compose"),
            memory("Deploys with Docker Swarm", recallCount = 20),
        )
        val hits = MemoryRetriever.rank(memories, "docker deploys", limit = 2, now = now)
        assertTrue(hits.first().content.contains("Swarm"))
    }

    @Test
    fun `the limit is respected`() {
        assertEquals(2, MemoryRetriever.rank(store, "kotlin android coffee postgres friday", 2, now).size)
    }

    @Test
    fun `an empty store is not an error`() {
        assertEquals(emptyList<Memory>(), MemoryRetriever.rank(emptyList(), "anything", now = now))
    }

    @Test
    fun `tokenizing drops the words that carry nothing`() {
        val terms = MemoryRetriever.tokenize("The user prefers, and always has, the Kotlin way!")
        assertTrue("kotlin" in terms)
        assertTrue("prefers" in terms)
        assertTrue("the" !in terms)
        assertTrue("and" !in terms)
        // Two letters is never a useful term and matches far too much.
        assertTrue(terms.none { it.length < 3 })
    }
}
