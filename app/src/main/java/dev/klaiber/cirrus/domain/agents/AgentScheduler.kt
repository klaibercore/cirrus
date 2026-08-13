package dev.klaiber.cirrus.domain.agents

import android.content.Context
import androidx.hilt.work.HiltWorker
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

    /** Called at startup and whenever an agent changes, so the queue always matches the store. */
    suspend fun syncAll() {
        val enabled = agents.enabled()
        enabled.forEach(::schedule)
        // Anything disabled or deleted since the last sync is cancelled by tag sweep below.
        WorkManager.getInstance(context).pruneWork()
    }

    fun schedule(agent: Agent) {
        val manager = WorkManager.getInstance(context)
        if (!agent.enabled || agent.days.isEmpty()) {
            manager.cancelUniqueWork(workName(agent.id))
            return
        }

        val delay = delayUntilNextRun(agent)
        val request = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_AGENT_ID, agent.id).build())
            // A run is a network call; firing it offline just burns a slot and records a failure.
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
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
            .enqueueUniqueWork(manualName(agentId), ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val KEY_AGENT_ID = "agentId"
        const val KEY_MANUAL = "manual"
        const val TAG = "cirrus-agent"

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

        return try {
            runner.run(agentId)
            Result.success()
        } catch (error: Throwable) {
            // Retrying a whole generation on a transient failure would double the cost of a bad
            // night; the failure is already recorded on the agent for the user to see.
            Result.failure()
        } finally {
            if (!manual) {
                agents.byId(agentId)?.let(scheduler::schedule)
            }
        }
    }
}
