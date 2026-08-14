package dev.klaiber.cirrus.domain.agents

import dev.klaiber.cirrus.domain.memory.ConsolidationScheduler
import dev.klaiber.cirrus.domain.model.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * When the next run actually happens.
 *
 * Worth pinning down precisely: an agent that fires an hour early is a bug you notice, and one that
 * fires a week late is a bug you do not.
 */
class AgentScheduleTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun agent(
        minuteOfDay: Int,
        days: Set<DayOfWeek>,
        enabled: Boolean = true,
    ) = Agent(
        id = "a",
        name = "Briefing",
        prompt = "Summarise",
        model = null,
        minuteOfDay = minuteOfDay,
        days = days,
        enabled = enabled,
        toolsEnabled = true,
        notifyOnFinish = true,
        createdAt = 0,
        lastRunAt = null,
        lastStatus = null,
        lastSummary = null,
        lastConversationId = null,
    )

    private fun hours(millis: Long) = millis / 3_600_000.0

    @Test
    fun `later today is later today`() {
        // Wednesday 09:00, due at 17:30 the same day.
        val now = LocalDateTime.of(2026, 8, 12, 9, 0)
        val delay = AgentScheduler.delayUntilNextRun(agent(17 * 60 + 30, Agent.WEEKDAYS), now, zone)
        assertEquals(8.5, hours(delay), 0.01)
    }

    @Test
    fun `a time already past today rolls to the next matching day`() {
        // Wednesday 18:00, due at 07:30 on weekdays: tomorrow morning, 13.5 hours away.
        val now = LocalDateTime.of(2026, 8, 12, 18, 0)
        val delay = AgentScheduler.delayUntilNextRun(agent(7 * 60 + 30, Agent.WEEKDAYS), now, zone)
        assertEquals(13.5, hours(delay), 0.01)
    }

    @Test
    fun `a weekday agent skips the weekend`() {
        // Friday 18:00 → Monday 07:00 is 61 hours.
        val now = LocalDateTime.of(2026, 8, 14, 18, 0)
        val delay = AgentScheduler.delayUntilNextRun(agent(7 * 60, Agent.WEEKDAYS), now, zone)
        assertEquals(61.0, hours(delay), 0.01)
    }

    @Test
    fun `a single-day agent waits a whole week when it has just missed`() {
        // Sunday 10:00, due Sundays at 09:00.
        val now = LocalDateTime.of(2026, 8, 16, 10, 0)
        val delay = AgentScheduler.delayUntilNextRun(agent(9 * 60, setOf(DayOfWeek.SUNDAY)), now, zone)
        assertEquals(167.0, hours(delay), 0.01)
    }

    @Test
    fun `an agent with no days never runs`() {
        val delay = AgentScheduler.delayUntilNextRun(agent(9 * 60, emptySet()), LocalDateTime.now(), zone)
        assertEquals(Long.MAX_VALUE, delay)
    }

    @Test
    fun `the delay is always in the future`() {
        val now = LocalDateTime.of(2026, 8, 12, 9, 0)
        DayOfWeek.entries.forEach { day ->
            val delay = AgentScheduler.delayUntilNextRun(agent(9 * 60, setOf(day)), now, zone)
            assertTrue("scheduling for $day went backwards", delay > 0)
        }
    }

    /**
     * The card says "next run tomorrow at 07:30", and that sentence has to agree with the booking
     * behind it — a screen that promises a run at a different time from the one that fires is worse
     * than a screen that promises nothing.
     */
    @Test
    fun `the next run time agrees with the delay it is scheduled for`() {
        val now = LocalDateTime.of(2026, 8, 12, 18, 0)
        val subject = agent(7 * 60 + 30, Agent.WEEKDAYS)
        val at = AgentScheduler.nextRunAt(subject, now, zone)!!
        val delay = AgentScheduler.delayUntilNextRun(subject, now, zone)
        assertEquals(now.atZone(zone).toInstant().toEpochMilli() + delay, at)
    }

    @Test
    fun `an agent that cannot run has no next run`() {
        val now = LocalDateTime.of(2026, 8, 12, 18, 0)
        assertNull(AgentScheduler.nextRunAt(agent(9 * 60, Agent.WEEKDAYS, enabled = false), now, zone))
        assertNull(AgentScheduler.nextRunAt(agent(9 * 60, emptySet()), now, zone))
    }

    @Test
    fun `scheduling is off unless the agent is on and has a day`() {
        assertTrue(agent(9 * 60, Agent.WEEKDAYS).isScheduled)
        assertFalse(agent(9 * 60, Agent.WEEKDAYS, enabled = false).isScheduled)
        assertFalse(agent(9 * 60, emptySet()).isScheduled)
    }

    @Test
    fun `days pack into a mask and come back out`() {
        listOf(Agent.WEEKDAYS, Agent.WEEKEND, DayOfWeek.entries.toSet(), setOf(DayOfWeek.WEDNESDAY))
            .forEach { days ->
                assertEquals(days, Agent.daysFromMask(Agent.daysToMask(days)))
            }
    }

    @Test
    fun `the nightly pass lands on the hour it was given`() {
        val now = LocalDateTime.of(2026, 8, 12, 23, 30)
        assertEquals(3.5, hours(ConsolidationScheduler.delayUntil(3, now, zone)), 0.01)

        val morning = LocalDateTime.of(2026, 8, 12, 4, 0)
        assertEquals(23.0, hours(ConsolidationScheduler.delayUntil(3, morning, zone)), 0.01)
    }
}
