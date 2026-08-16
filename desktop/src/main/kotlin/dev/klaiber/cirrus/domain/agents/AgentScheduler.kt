package dev.klaiber.cirrus.domain.agents

import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRunTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts agents on the clock.
 *
 * Android books each agent as a one-shot WorkManager request that re-books itself, because periodic
 * work there has a fifteen-minute floor and drifts against the wall clock — useless for "07:30 on
 * weekdays". The desktop has no work manager to argue with, so the same shape is a coroutine per
 * agent that sleeps until the agent is due, runs it, and books the next one.
 *
 * The behavioural difference worth being explicit about: **agents only run while Cirrus is open.**
 * WorkManager persists its queue, so a phone that was asleep at 07:30 fires the run late; a desktop
 * app that was not running simply missed it, and the next occurrence is booked instead. Firing a
 * fortnight of missed briefings at launch would be worse than skipping them.
 */
@Singleton
class AgentScheduler @Inject constructor(
    private val agents: AgentRepository,
    private val runner: AgentRunner,
    private val scope: CoroutineScope,
) {

    /** One sleeping coroutine per scheduled agent. Replacing a booking cancels the old one. */
    private val bookings = mutableMapOf<String, Job>()
    private val lock = Mutex()

    /**
     * Called at startup and whenever an agent changes, so the queue always matches the store.
     *
     * It also cancels what should no longer fire. An agent switched off has its booking dropped
     * here rather than left to notice at wake-up — the one failure mode where a switch marked
     * "off" does the thing anyway.
     */
    suspend fun syncAll() {
        agents.all().forEach { agent ->
            if (agent.isScheduled) schedule(agent) else cancel(agent.id)
        }
        // Runs that were killed rather than finished — a crash, a quit mid-generation, a machine
        // that slept through the run's own timeout — are still marked as in progress. Close them
        // out, or the agents screen shows a spinner for something that stopped days ago.
        agents.failInterruptedRuns(System.currentTimeMillis() - STALE_RUN_MS)
    }

    fun schedule(agent: Agent) {
        scope.launch {
            lock.withLock {
                bookings.remove(agent.id)?.cancel()
                if (!agent.isScheduled) return@withLock
                val delayMs = delayUntilNextRun(agent)
                if (delayMs == Long.MAX_VALUE) return@withLock
                bookings[agent.id] = scope.launch { sleepThenRun(agent.id, delayMs) }
            }
        }
    }

    fun cancel(agentId: String) {
        scope.launch { lock.withLock { bookings.remove(agentId)?.cancel() } }
    }

    /** Runs an agent now, outside its schedule, without disturbing the next scheduled run. */
    fun runNow(agentId: String) {
        scope.launch { attempt(agentId, AgentRunTrigger.MANUAL) }
    }

    /**
     * Sleeps until due, runs, then books the next occurrence.
     *
     * The re-booking is in a `finally` so it survives the run throwing: an agent that stops being
     * scheduled because of one bad morning is the failure this whole file exists to avoid. It is
     * skipped only on cancellation, which is what `cancel` and a replaced booking both do.
     */
    private suspend fun sleepThenRun(agentId: String, delayMs: Long) {
        delay(delayMs)
        try {
            attempt(agentId, AgentRunTrigger.SCHEDULED)
        } finally {
            // Re-read rather than reuse: the agent may have been edited while this one slept.
            agents.byId(agentId)?.let(::schedule)
        }
    }

    /**
     * One run, with two more tries if the failure looks transient.
     *
     * A dropped socket at 07:30 used to mean no briefing that day: every failure was final. Two
     * more attempts, thirty seconds apart, costs nothing when the network is simply back — and a
     * rejected key is deliberately *not* retryable, because burning three generations to
     * rediscover that the key is still wrong helps nobody.
     */
    private suspend fun attempt(agentId: String, trigger: AgentRunTrigger) {
        repeat(MAX_ATTEMPTS) { attempt ->
            val outcome = try {
                runner.run(agentId = agentId, trigger = trigger)
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (error: Throwable) {
                // Nothing should reach here — the runner records its own failures — but an
                // exception escaping would skip the re-booking in the caller's `finally`.
                AgentRunner.Outcome.Failed(error.message ?: "The run failed unexpectedly.")
            }

            if (outcome !is AgentRunner.Outcome.Retryable) return
            if (attempt == MAX_ATTEMPTS - 1) return
            delay(BACKOFF_MS shl attempt)
        }
    }

    companion object {

        /** Two quick attempts is the whole retry budget. */
        const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MS = 30_000L

        /** Longer than any run can legitimately take, including its own timeout and retries. */
        private const val STALE_RUN_MS = 30L * 60 * 1000

        /**
         * Milliseconds until this agent is next due.
         *
         * Walks forward a day at a time from today, which handles "later today", "tomorrow" and
         * "not until next Tuesday" with the same three lines — and, because it works in
         * [LocalDateTime], gets daylight saving right by construction: 07:30 stays 07:30.
         */
        fun delayUntilNextRun(
            agent: Agent,
            now: LocalDateTime = LocalDateTime.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Long {
            if (agent.days.isEmpty()) return Long.MAX_VALUE
            val time = now.toLocalDate().atStartOfDay().plusMinutes(agent.minuteOfDay.toLong())

            for (offset in 0..DAYS_AHEAD) {
                val candidate = time.plusDays(offset.toLong())
                val day = DayOfWeek.of(candidate.dayOfWeek.value)
                if (day in agent.days && candidate.isAfter(now)) {
                    return candidate.atZone(zone).toInstant().toEpochMilli() -
                        now.atZone(zone).toInstant().toEpochMilli()
                }
            }
            return Long.MAX_VALUE
        }

        /** When this agent next runs, as a wall-clock instant, or null if it never does. */
        fun nextRunAt(
            agent: Agent,
            now: LocalDateTime = LocalDateTime.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Long? {
            if (!agent.isScheduled) return null
            val delay = delayUntilNextRun(agent, now, zone)
            if (delay == Long.MAX_VALUE) return null
            return now.atZone(zone).toInstant().toEpochMilli() + delay
        }

        private const val DAYS_AHEAD = 8
    }
}
