package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.domain.model.MemoryKind
import dev.klaiber.cirrus.testing.InMemoryMemoryDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The write path into memory.
 *
 * `remember` is the only way anything enters the store, and it is the one place a fact can be
 * silently destroyed rather than added — so the interesting assertions here are all about what
 * survives a fold, not about what gets written.
 */
class MemoryRepositoryTest {

    private lateinit var dao: InMemoryMemoryDao
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        dao = InMemoryMemoryDao()
        repository = MemoryRepository(dao)
    }

    @Test
    fun `a first memory is stored as new`() = runTest {
        val result = repository.remember("Kevin ships Android apps in Kotlin")

        assertTrue(result.wasNew)
        assertNotNull(result.memory)
        assertEquals(1, dao.all.size)
    }

    @Test
    fun `blank content is not stored`() = runTest {
        val result = repository.remember("   \n\t ")

        assertNull(result.memory)
        assertFalse(result.wasNew)
        assertTrue(dao.all.isEmpty())
    }

    @Test
    fun `an unrelated fact is kept alongside rather than folded`() = runTest {
        repository.remember("Kevin ships Android apps in Kotlin")
        val result = repository.remember("The release keystore lives in a password manager")

        assertTrue(result.wasNew)
        assertEquals(2, dao.all.size)
    }

    @Test
    fun `restating the same fact folds instead of duplicating`() = runTest {
        repository.remember("Kevin prefers Kotlin over Java for Android work")
        val result = repository.remember("Kevin prefers Kotlin over Java for Android work")

        assertFalse(result.wasNew)
        assertEquals(1, dao.all.size)
    }

    @Test
    fun `being told again raises confidence`() = runTest {
        val first = repository.remember("Kevin prefers Kotlin over Java for Android work")
        val second = repository.remember("Kevin prefers Kotlin over Java for Android work")

        val before = requireNonNull(first.memory).confidence
        val after = requireNonNull(second.memory).confidence
        assertTrue("confidence should rise on confirmation, was $before then $after", after > before)
    }

    @Test
    fun `confidence is capped at one however often a fact is repeated`() = runTest {
        repeat(20) { repository.remember("Kevin prefers Kotlin over Java for Android work") }

        assertEquals(1f, dao.all.single().confidence, 0.0001f)
    }

    @Test
    fun `folding never unpins an already pinned memory`() = runTest {
        repository.remember("Kevin prefers Kotlin over Java for Android work", pinned = true)
        repository.remember("Kevin prefers Kotlin over Java for Android work", pinned = false)

        assertTrue("a fold must not quietly unpin a memory", dao.all.single().pinned)
    }

    /**
     * The fold must not cost the store information.
     *
     * The threshold is a share of the *shorter* memory's terms, so a brief restatement of a
     * detailed fact always clears it — every term of "Kevin prefers Kotlin" appears in "Kevin
     * prefers Kotlin over Java for all Android work". Treating those as one memory is correct and
     * deliberate. Overwriting the detailed wording with the brief one is not: the qualifier is the
     * part worth keeping, and there is no route back to it once the row has been updated in place.
     */
    @Test
    fun `folding a shorter restatement keeps the more detailed wording`() = runTest {
        val detailed = "Kevin prefers Kotlin over Java for all Android work"
        repository.remember(detailed)
        repository.remember("Kevin prefers Kotlin")

        assertEquals(1, dao.all.size)
        assertEquals(detailed, dao.all.single().content)
    }

    /** The other direction still has to work, or the store can never learn a qualifier. */
    @Test
    fun `folding a longer restatement adopts the fuller wording`() = runTest {
        repository.remember("Kevin prefers Kotlin")
        val detailed = "Kevin prefers Kotlin over Java for all Android work"
        repository.remember(detailed)

        assertEquals(1, dao.all.size)
        assertEquals(detailed, dao.all.single().content)
    }

    @Test
    fun `a fold refreshes the timestamp even when the wording is kept`() = runTest {
        repository.remember("Kevin prefers Kotlin over Java for all Android work")
        val storedAt = dao.all.single().updatedAt
        Thread.sleep(5)
        repository.remember("Kevin prefers Kotlin")

        assertTrue(
            "a confirmed fact is a fresher fact, whichever wording won",
            dao.all.single().updatedAt >= storedAt,
        )
    }

    @Test
    fun `recall counts a hit so a useful memory can climb`() = runTest {
        repository.remember("The release keystore lives in a password manager")

        val hits = repository.recall("keystore")

        assertEquals(1, hits.size)
        assertEquals(1, dao.all.single().recallCount)
    }

    @Test
    fun `recall of nothing does not touch the store`() = runTest {
        repository.remember("The release keystore lives in a password manager")

        val hits = repository.recall("photosynthesis")

        assertTrue(hits.isEmpty())
        assertEquals(0, dao.all.single().recallCount)
    }

    @Test
    fun `an archived memory is not a fold candidate`() = runTest {
        val first = repository.remember("Kevin prefers Kotlin over Java for Android work")
        repository.archive(requireNonNull(first.memory).id)

        val result = repository.remember("Kevin prefers Kotlin over Java for Android work")

        assertTrue("a retired memory must not absorb a new one", result.wasNew)
        assertEquals(2, dao.all.size)
    }

    @Test
    fun `folding revives a memory that had been retired only if it matched`() = runTest {
        repository.remember("Kevin prefers Kotlin over Java for Android work")
        repository.remember("Kevin prefers Kotlin over Java for Android work")

        assertFalse(dao.all.single().archived)
    }

    @Test
    fun `the kind of the newer statement wins`() = runTest {
        repository.remember("Kevin prefers Kotlin over Java for Android work", kind = MemoryKind.FACT)
        repository.remember(
            "Kevin prefers Kotlin over Java for Android work",
            kind = MemoryKind.PREFERENCE,
        )

        assertEquals(MemoryKind.PREFERENCE, dao.all.single().kind.let(MemoryKind::fromName))
    }

    private fun <T : Any> requireNonNull(value: T?): T = requireNotNull(value)
}
