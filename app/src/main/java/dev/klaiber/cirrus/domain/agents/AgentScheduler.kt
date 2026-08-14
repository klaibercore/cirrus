package dev.klaiber.cirrus.domain.agents

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRunTrigger
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts agents on the clock.
 *
 * Each agent is scheduled as a *one-shot* at its next due time rather than as periodic work, and
 * re-schedules itself after it runs. Periodic work in WorkManager has a fifteen-minute floor and
 * drifts relative to wall-clock time, which is fine for a sync and useless for "07:30 on weekdays"
 * — the whole point of the feature is that it happens at a time the user chose.
 */
@Singleton
class AgentScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agents: AgentRepository,
) {

    /**
     * Called at startup and whenever an agent changes, so the queue always matches the store.
     *
     * It also cancels what should no longer fire. `pruneWork` only discards work that has already
     * finished, so an agent switched off while the app was dead used to keep its booking and run
     * anyway — the one failure mode where a switch marked "off" does the thing anyway.
     */
    suspend fun syncAll() {
        val manager = WorkManager.getInstance(context)
        agents.all().forEach { agent ->
            if (agent.isScheduled) schedule(agent) else manager.cancelUniqueWork(workName(agent.id))
        }
        // Runs that were killed rather than finished — by a reboot, or by the platform reclaiming
        // the app mid-generation — are still marked as in progress. Close them out, or the agents
        // screen shows a spinner for something that stopped days ago.
        agents.failInterruptedRuns(System.currentTimeMillis() - STALE_RUN_MS)
        manager.pruneWork()
    }

    fun schedule(agent: Agent) {
        val manager = WorkManager.getInstance(context)
        if (!agent.isScheduled) {
            manager.cancelUniqueWork(workName(agent.id))
            return
        }

        val delay = delayUntilNextRun(agent)
        if (delay == Long.MAX_VALUE) {
            manager.cancelUniqueWork(workName(agent.id))
            return
        }

        val request = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_AGENT_ID, agent.id).build())
            // A run is a network call; firing it offline just burns a slot and records a failure.
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        manager.enqueueUniqueWork(workName(agent.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(agentId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(agentId))
    }

    /** Runs an agent now, outside its schedule, without disturbing the next scheduled run. */
    fun runNow(agentId: String) {
        val request = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_AGENT_ID, agentId)
                    .putBoolean(KEY_MANUAL, true)
                    .build(),
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            // KEEP, not REPLACE: tapping "run now" twice means someone is impatient, not that they
            // want the first run cancelled halfway and started again.
            .enqueueUniqueWork(manualName(agentId), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val KEY_AGENT_ID = "agentId"
        const val KEY_MANUAL = "manual"
        const val TAG = "cirrus-agent"

        /** Two quick attempts is the whole retry budget; see [AgentWorker]. */
        const val MAX_ATTEMPTS = 3
        private const val BACKOFF_SECONDS = 30L

        /** Longer than any run can legitimately take, including its own timeout and retries. */
        private const val STALE_RUN_MS = 30L * 60 * 1000

        fun workName(agentId: String) = "agent-$agentId"

        private fun manualName(agentId: String) = "agent-now-$agentId"

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

/**
 * The scheduled run itself.
 *
 * Re-schedules before returning, so the chain survives reboots and doze: WorkManager persists the
 * next request, and a missed window fires late rather than being skipped.
 */
@HiltWorker
class AgentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: AgentRunner,
    private val agents: AgentRepository,
    private val scheduler: AgentScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val agentId = inputData.getString(AgentScheduler.KEY_AGENT_ID) ?: return Result.failure()
        val manual = inputData.getBoolean(AgentScheduler.KEY_MANUAL, false)

        val outcome = try {
            runner.run(
                agentId = agentId,
                trigger = if (manual) AgentRunTrigger.MANUAL else AgentRunTrigger.SCHEDULED,
            )
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (error: Throwable) {
            // Nothing should reach here — the runner records its own failures — but an exception
            // escaping this method skips the re-booking below, and an agent that stops being
            // scheduled because of one bad morning is the failure this whole file exists to avoid.
            AgentRunner.Outcome.Failed(error.message ?: "The run failed unexpectedly.")
        }

        // A dropped socket at 07:30 used to mean no briefing that day: every failure was final.
        // Two more attempts, thirty seconds apart, costs nothing when the network is simply back —
        // and a rejected key is deliberately *not* retryable, because burning three generations to
        // rediscover that the key is still wrong helps nobody.
        val retrying = outcome is AgentRunner.Outcome.Retryable &&
            runAttemptCount < AgentScheduler.MAX_ATTEMPTS - 1

        // Re-booking uses the same unique work name as the retry chain, so doing it now would
        // cancel the very retry we just asked for.
        if (!manual && !retrying) {
            agents.byId(agentId)?.let(scheduler::schedule)
        }

        return when {
            retrying -> Result.retry()
            outcome is AgentRunner.Outcome.Finished -> Result.success()
            // The failure is already recorded on the agent, and on the run, for the user to see.
            else -> Result.failure()
        }
    }
}
