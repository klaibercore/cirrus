package dev.klaiber.cirrus.domain.model

import java.time.DayOfWeek

/**
 * A prompt that runs on a clock instead of on a keystroke.
 *
 * The result is written into a real conversation rather than into a log, so an agent's output is
 * something you can read, scroll, branch from and reply to like any other thread. That is the
 * whole design: an agent is a scheduled first message, not a separate kind of thing.
 */
data class Agent(
    val id: String,
    val name: String,
    val prompt: String,
    /** Null means whatever the default model is at the time it runs. */
    val model: String?,
    /** Local time of day, in minutes past midnight. */
    val minuteOfDay: Int,
    val days: Set<DayOfWeek>,
    val enabled: Boolean,
    val toolsEnabled: Boolean,
    /** Post a notification when the run finishes. Without it a run is silent until you look. */
    val notifyOnFinish: Boolean,
    val createdAt: Long,
    val lastRunAt: Long?,
    val lastStatus: AgentRunStatus?,
    /** First line or so of the last answer, for the list row. */
    val lastSummary: String?,
    val lastConversationId: String?,
) {
    val scheduleLabel: String
        get() {
            val time = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
            return "$time · ${daysLabel()}"
        }

    private fun daysLabel(): String = when {
        days.size == 7 -> "every day"
        days == WEEKDAYS -> "weekdays"
        days == WEEKEND -> "weekends"
        days.isEmpty() -> "never"
        else -> DayOfWeek.entries.filter { it in days }.joinToString(" ") {
            it.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
        }
    }

    companion object {
        val WEEKDAYS = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        /** Days packed into the low seven bits, Monday first, so a schedule is one column. */
        fun daysToMask(days: Set<DayOfWeek>): Int =
            days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

        fun daysFromMask(mask: Int): Set<DayOfWeek> =
            DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
    }
}

enum class AgentRunStatus { RUNNING, SUCCEEDED, FAILED }
