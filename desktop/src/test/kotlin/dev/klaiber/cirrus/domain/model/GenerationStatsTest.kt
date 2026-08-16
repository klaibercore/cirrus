package dev.klaiber.cirrus.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationStatsTest {

    @Test
    fun `tokensPerSecond - derived from eval timings`() {
        val stats = GenerationStats(evalCount = 10, evalDurationNs = 1_000_000_000)
        assertEquals(10.0, stats.tokensPerSecond!!, 0.001)
    }

    @Test
    fun `tokensPerSecond - null when counters missing or zero`() {
        assertNull(GenerationStats().tokensPerSecond)
        assertNull(GenerationStats(evalCount = 10).tokensPerSecond)
        assertNull(GenerationStats(evalCount = 10, evalDurationNs = 0).tokensPerSecond)
    }

    @Test
    fun `totalTokens - sums prompt and eval`() {
        val stats = GenerationStats(promptEvalCount = 5, evalCount = 10)
        assertEquals(15, stats.totalTokens)
    }

    @Test
    fun `totalTokens - null when both missing`() {
        assertNull(GenerationStats().totalTokens)
    }
}
