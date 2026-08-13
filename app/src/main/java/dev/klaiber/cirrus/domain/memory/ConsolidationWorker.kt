package dev.klaiber.cirrus.domain.memory

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.klaiber.cirrus.data.repository.SettingsRepository
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Books the nightly memory pass, then re-books it.
 *
 * Same one-shot-that-reschedules shape as the agents, and for the same reason: periodic work
 * drifts, and "sometime in this fifteen-minute window, roughly daily" is not what "at 3am" means.
 *
 * Charging is required rather than preferred. This is several model calls the user did not ask
 * for; spending phone battery on it while they are out is not a trade anyone would choose.
 */
@Singleton
class ConsolidationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    fun sync() {
        val current = settings.current.value
        val manager = WorkManager.getInstance(context)
        if (!current.memoryEnabled || !current.memoryConsolidationEnabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = OneTimeWorkRequestBuilder<ConsolidationWorker>()
            .setInitialDelay(delayUntil(current.memoryConsolidationHour), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(true)
                    .build(),
            )
            .addTag(TAG)
            .build()

        manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Runs the pass now, for the button on the memory screen. */
    fun runNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ConsolidationWorker>().addTag(TAG).build(),
        )
    }

    companion object {
        const val WORK_NAME = "memory-consolidation"
        const val MANUAL_NAME = "memory-consolidation-now"
        const val TAG = "cirrus-consolidation"

        /** Milliseconds until the next occurrence of [hour] o'clock, local time. */
        fun delayUntil(
            hour: Int,
            now: LocalDateTime = LocalDateTime.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Long {
            val today = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), 0))
            val next = if (today.isAfter(now)) today else today.plusDays(1)
            return next.atZone(zone).toInstant().toEpochMilli() -
                now.atZone(zone).toInstant().toEpochMilli()
        }
    }
}

@HiltWorker
class ConsolidationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val consolidator: MemoryConsolidator,
    private val scheduler: ConsolidationScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        consolidator.consolidate()
        Result.success()
    } catch (error: Throwable) {
        // Tomorrow night will do. Retrying now would mean waking the model up again immediately.
        Result.failure()
    } finally {
        scheduler.sync()
    }
}
