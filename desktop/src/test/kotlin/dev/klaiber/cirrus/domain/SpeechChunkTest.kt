package dev.klaiber.cirrus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a long answer is cut up before it is spoken.
 *
 * Two things must hold or the feature breaks audibly: no chunk may exceed the limit, because the
 * hosted API rejects the request outright, and no chunk may end mid-word, because the seam is
 * then something you can hear.
 */
class SpeechChunkTest {

    @Test
    fun `short text is left alone`() {
        assertEquals(listOf("Hello there."), chunk("Hello there.", 100))
    }

    @Test
    fun `chunks break at sentence ends`() {
        val text = "One sentence here. Two sentences here. Three sentences here."
        val chunks = chunk(text, 40)
        assertTrue(chunks.all { it.length <= 40 })
        assertTrue(chunks.first().endsWith("."))
    }

    @Test
    fun `no chunk ever exceeds the limit`() {
        val text = List(200) { "word$it" }.joinToString(" ")
        chunk(text, 64).forEach { assertTrue("\"$it\" is ${it.length} long", it.length <= 64) }
    }

    @Test
    fun `nothing is lost or duplicated`() {
        val text = "Alpha beta. Gamma delta! Epsilon zeta? Eta theta."
        assertEquals(
            text.replace(" ", ""),
            chunk(text, 20).joinToString("").replace(" ", ""),
        )
    }

    @Test
    fun `text with no sentence ends still breaks at a space`() {
        val text = List(50) { "aaaa" }.joinToString(" ")
        val chunks = chunk(text, 30)
        assertTrue(chunks.all { it.length <= 30 })
        assertTrue(chunks.none { it.endsWith("aa aa") })
    }

    @Test
    fun `a single unbroken run is cut rather than returned oversized`() {
        // Nothing to split on, and an over-length chunk would be rejected by the API.
        val chunks = chunk("x".repeat(100), 30)
        assertTrue(chunks.all { it.length <= 30 })
        assertEquals(100, chunks.sumOf { it.length })
    }
}
