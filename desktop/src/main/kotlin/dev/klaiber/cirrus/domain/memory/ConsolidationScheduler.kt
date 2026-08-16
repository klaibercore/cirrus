package dev.klaiber.cirrus.domain.memory

import dev.klaiber.cirrus.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Books the nightly memory pass.
 *
 * The same shape as [dev.klaiber.cirrus.domain.agents.AgentScheduler] and for the same reason: a
 * one-shot that sleeps until the chosen hour and re-books itself, rather than periodic work that
 * drifts. The Android version also waits for the device to be charging; there is no such condition
 * to ask for on a desktop, and a laptop on battery at 3am is asleep anyway.
 *
 * As with agents, this only fires while Cirrus is running. A pass missed overnight is skipped
 * rather than run at breakfast: the whole point of the hour is that nobody is using the model then.
 */
@Singleton
class ConsolidationScheduler @Inject constructor(
    private val settings: SettingsRepository,
    private val consolidator: MemoryConsolidator,
    private val scope: CoroutineScope,
) {

    private var booking: Job? = null

    /** Called at startup and whenever the setting changes, so the booking matches the store. */
    fun sync() {
        booking?.cancel()
        booking = null

        val current = settings.current.value
        if (!current.memoryEnabled || !current.memoryConsolidationEnabled) return

        booking = scope.launch {
            delay(delayUntil(current.memoryConsolidationHour))
            try {
                consolidator.consolidate()
            } catch (error: Throwable) {
                // Tomorrow night will do. Retrying now would mean waking the model up again
                // immediately, which is the one thing this hour exists to avoid.
            } finally {
                sync()
            }
        }
    }

    /** Runs the pass now, for the button on the memory screen. */
    fun runNow() {
        scope.launch { runCatching { consolidator.consolidate() } }
    }

    companion object {

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
