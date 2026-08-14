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
    /**
     * How many of this agent's threads to keep.
     *
     * An agent that runs daily writes 365 threads a year, and the 300th is of no interest to
     * anybody. Older ones are deleted after each run — except any that have been replied to, which
     * stop being runs at that moment and become ordinary conversations.
     */
    val keepRuns: Int = DEFAULT_KEEP_RUNS,
) {
    val timeLabel: String get() = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    val scheduleLabel: String get() = "$timeLabel · ${daysLabel()}"

    /** True while the agent is switched on and has a day to run on. */
    val isScheduled: Boolean get() = enabled && days.isNotEmpty()

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
        /** Enough to see a pattern in the last week or two, few enough to never be a list. */
        const val DEFAULT_KEEP_RUNS = 10

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

/** What set a run going. A failure you caused by tapping "run now" reads differently from one at 3am. */
enum class AgentRunTrigger { SCHEDULED, MANUAL }

/**
 * One attempt at running an agent.
 *
 * The agent row only remembers the latest attempt, and one column cannot tell "it worked this
 * morning" apart from "it has failed every morning this week". The second is the only one worth
 * being told about, so every attempt is written down.
 */
data class AgentRun(
    val id: String,
    val agentId: String,
    /** Null once the thread it wrote has been deleted; the run itself still happened. */
    val conversationId: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: AgentRunStatus,
    val trigger: AgentRunTrigger,
    val summary: String?,
    val errorMessage: String?,
    val toolCalls: Int,
    val tokens: Int?,
) {
    val durationMs: Long? get() = finishedAt?.let { it - startedAt }

    val isRunning: Boolean get() = status == AgentRunStatus.RUNNING && finishedAt == null
}
