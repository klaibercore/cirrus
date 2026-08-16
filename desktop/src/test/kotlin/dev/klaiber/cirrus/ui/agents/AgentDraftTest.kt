package dev.klaiber.cirrus.ui.agents

import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * The editor's draft.
 *
 * It exists so that an agent, a template and a blank sheet all reach the editor as one shape. The
 * property worth testing is that the round trip through it changes nothing else about the agent —
 * an edit that silently reset the run history or the created date would be invisible until the day
 * somebody noticed their agent had forgotten every run it ever made.
 */
class AgentDraftTest {

    private val agent = Agent(
        id = "a1",
        name = "Morning briefing",
        prompt = "Summarise the news",
        model = "qwen3",
        minuteOfDay = 7 * 60 + 30,
        days = Agent.WEEKDAYS,
        enabled = true,
        toolsEnabled = true,
        notifyOnFinish = true,
        createdAt = 1_000L,
        lastRunAt = 2_000L,
        lastStatus = null,
        lastSummary = "Three things happened",
        lastConversationId = "c1",
        keepRuns = 30,
    )

    @Test
    fun `an untouched round trip changes nothing`() {
        assertEquals(agent, agent.applying(agent.toDraft()))
    }

    @Test
    fun `editing carries the history through untouched`() {
        val edited = agent.applying(agent.toDraft().copy(name = "Evening briefing", keepRuns = 3))

        assertEquals("Evening briefing", edited.name)
        assertEquals(3, edited.keepRuns)
        assertEquals(agent.createdAt, edited.createdAt)
        assertEquals(agent.lastRunAt, edited.lastRunAt)
        assertEquals(agent.lastSummary, edited.lastSummary)
        assertEquals(agent.lastConversationId, edited.lastConversationId)
        assertEquals(agent.enabled, edited.enabled)
    }

    @Test
    fun `a template arrives ready to save`() {
        AgentTemplate.All.forEach { template ->
            assertTrue("${template.name} is not saveable", template.toDraft().isValid)
        }
    }

    @Test
    fun `a schedule with no days cannot be saved`() {
        assertFalse(AgentDraft(name = "x", prompt = "y", days = emptySet()).isValid)
        assertFalse(AgentDraft(name = " ", prompt = "y").isValid)
        assertFalse(AgentDraft(name = "x", prompt = "").isValid)
        assertTrue(AgentDraft(name = "x", prompt = "y", days = setOf(DayOfWeek.MONDAY)).isValid)
    }
}
