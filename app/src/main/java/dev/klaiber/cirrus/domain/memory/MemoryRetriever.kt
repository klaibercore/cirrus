package dev.klaiber.cirrus.domain.memory

import dev.klaiber.cirrus.domain.model.Memory
import kotlin.math.ln
import kotlin.math.max

/**
 * Decides which memories are worth sending.
 *
 * There is no embedding model here on purpose. Running one on-device for a few hundred short
 * sentences would cost more than it returns, and the hosted API has no embedding call this app
 * already pays for. What is left — term overlap weighted by how rare each term is, nudged by
 * recency and by how often a memory has proved useful — is most of the way there for a corpus this
 * size, and it is a pure function, so its behaviour is pinned down by tests rather than by vibes.
 *
 * Rarity matters more than it looks: without it every memory containing "the" or "project" scores
 * alike, and the recall for "what did I decide about the database" returns whatever was written
 * most recently.
 */
object MemoryRetriever {

    /**
     * The [limit] best matches for [query], best first.
     *
     * Memories scoring nothing at all are dropped rather than padded in — an empty recall is a
     * useful answer, and filling the context with unrelated facts is how a model starts confidently
     * using them.
     */
    fun rank(
        memories: List<Memory>,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        now: Long = System.currentTimeMillis(),
    ): List<Memory> {
        if (memories.isEmpty()) return emptyList()
        val terms = tokenize(query)
        if (terms.isEmpty()) {
            // No query to match on: fall back to what is pinned, then to what is freshest.
            return memories
                .sortedWith(compareByDescending<Memory> { it.pinned }.thenByDescending { it.updatedAt })
                .take(limit)
        }

        val rarity = rarity(memories)
        return memories
            .map { it to score(it, terms, rarity, now) }
            .filter { (_, score) -> score > 0f }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (memory, _) -> memory }
    }

    private fun score(
        memory: Memory,
        terms: Set<String>,
        rarity: Map<String, Float>,
        now: Long,
    ): Float {
        val words = tokenize(memory.content)
        if (words.isEmpty()) return 0f

        var overlap = 0f
        terms.forEach { term ->
            val weight = rarity[term] ?: DEFAULT_RARITY
            when {
                term in words -> overlap += weight
                // A prefix match catches "coroutine" against "coroutines" without a stemmer.
                words.any { it.startsWith(term) || term.startsWith(it) } -> overlap += weight * PARTIAL
            }
        }
        if (overlap == 0f) return 0f

        // Normalising by the query keeps long memories from winning on length alone.
        val relevance = overlap / terms.sumOf { (rarity[it] ?: DEFAULT_RARITY).toDouble() }.toFloat()
        val ageDays = (now - memory.updatedAt).coerceAtLeast(0L) / DAY_MS.toFloat()
        val freshness = 1f / (1f + ageDays / HALF_LIFE_DAYS)
        val proven = ln(1f + memory.recallCount) * USE_WEIGHT
        val pin = if (memory.pinned) PIN_BONUS else 0f

        return relevance * (1f + freshness * FRESHNESS_WEIGHT) + proven + pin + memory.confidence * CONFIDENCE_WEIGHT
    }

    /**
     * How much each term is worth, from how many memories contain it.
     *
     * Inverse document frequency, capped so a term appearing exactly once does not swamp the sum.
     */
    private fun rarity(memories: List<Memory>): Map<String, Float> {
        val counts = mutableMapOf<String, Int>()
        memories.forEach { memory ->
            tokenize(memory.content).forEach { term -> counts[term] = (counts[term] ?: 0) + 1 }
        }
        val total = memories.size.toFloat()
        return counts.mapValues { (_, count) ->
            ln(1f + total / max(1f, count.toFloat())).coerceAtMost(MAX_RARITY)
        }
    }

    /** Lowercased words of three letters or more, minus the ones that carry no meaning. */
    internal fun tokenize(text: String): Set<String> = text
        .lowercase()
        .split(*SEPARATORS)
        .asSequence()
        .map { it.trim() }
        .filter { it.length >= MIN_TERM_LENGTH && it !in STOPWORDS }
        .toSet()

    private const val DEFAULT_LIMIT = 6
    private const val MIN_TERM_LENGTH = 3
    private const val DAY_MS = 86_400_000L
    private const val HALF_LIFE_DAYS = 45f
    private const val FRESHNESS_WEIGHT = 0.35f
    private const val USE_WEIGHT = 0.08f
    private const val PIN_BONUS = 0.5f
    private const val CONFIDENCE_WEIGHT = 0.15f
    private const val PARTIAL = 0.6f
    private const val DEFAULT_RARITY = 1f
    private const val MAX_RARITY = 3f

    private val SEPARATORS = charArrayOf(
        ' ', '\n', '\t', '\r', ',', '.', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}',
        '"', '\'', '/', '\\', '—', '–', '-', '*', '#', '`', '<', '>', '=', '|',
    )

    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "that", "this", "was", "were", "are", "but", "not",
        "you", "your", "has", "have", "had", "his", "her", "its", "our", "their", "they",
        "them", "she", "him", "who", "what", "when", "where", "why", "how", "all", "any", "can",
        "will", "would", "should", "could", "about", "into", "than", "then", "there", "here",
        "some", "such", "only", "just", "also", "very", "more", "most", "much", "many",
        "does", "did", "done", "from", "get", "got", "use", "used", "using", "like", "want",
    )
}
