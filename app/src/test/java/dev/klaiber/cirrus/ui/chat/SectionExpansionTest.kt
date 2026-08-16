package dev.klaiber.cirrus.ui.chat

import dev.klaiber.cirrus.ui.chat.components.SectionExpansion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a reasoning trace or a group of tool calls is open.
 *
 * These are the rules that a `remember(key)` gets wrong, and they are worth pinning down because
 * the bug they replace is invisible in a single-round turn: everything here only differs from the
 * old behaviour once a turn thinks, answers, calls a tool, and thinks again.
 */
class SectionExpansionTest {

    @Test
    fun `opens for work that is still running`() {
        assertTrue(SectionExpansion(initiallyExpanded = true).expanded)
    }

    @Test
    fun `closes when the work it was covering lands`() {
        val expansion = SectionExpansion(initiallyExpanded = true)
        expansion.settle()
        assertFalse(expansion.expanded)
    }

    @Test
    fun `stays closed when new work arrives afterwards`() {
        val expansion = SectionExpansion(initiallyExpanded = true)
        expansion.settle()
        // A second round of thinking, or a sixth tool call: nothing here reopens the section, which
        // is the whole point. Reopening it shoves the sentence being read down the screen.
        assertFalse(expansion.expanded)
        expansion.settle()
        assertFalse(expansion.expanded)
    }

    @Test
    fun `a section opened by hand survives the work landing`() {
        val expansion = SectionExpansion(initiallyExpanded = false)
        expansion.toggle()
        assertTrue(expansion.expanded)
        expansion.settle()
        assertTrue(expansion.expanded)
    }

    @Test
    fun `a section closed by hand is not reopened by the automatic rule`() {
        val expansion = SectionExpansion(initiallyExpanded = true)
        expansion.toggle()
        assertFalse(expansion.expanded)
        expansion.settle()
        assertFalse(expansion.expanded)
    }

    @Test
    fun `toggling twice returns to where it was`() {
        val expansion = SectionExpansion(initiallyExpanded = true)
        expansion.toggle()
        expansion.toggle()
        assertTrue(expansion.expanded)
    }
}
