package dev.klaiber.cirrus.domain.model

import java.time.DayOfWeek

/**
 * A ready-made agent.
 *
 * The blank editor is the reason most people never make a second agent — and often not a first one.
 * "A prompt that runs on a schedule" is an accurate description and a useless starting point, so the
 * screen opens with half a dozen worked examples that can be edited before they are saved.
 *
 * Every template is deliberately specific about *format*, not just topic. An unattended prompt is
 * answered without anyone to say "shorter, please", so the instruction to be short has to be in the
 * prompt itself.
 */
data class AgentTemplate(
    val name: String,
    val summary: String,
    val prompt: String,
    val minuteOfDay: Int,
    val days: Set<DayOfWeek>,
    val toolsEnabled: Boolean = true,
    /** True when the template is only useful once a GitHub token has been added. */
    val needsGitHub: Boolean = false,
) {
    companion object {
        private const val MORNING = 7 * 60 + 30
        private const val MIDDAY = 12 * 60
        private const val EVENING = 18 * 60
        private const val NIGHT = 21 * 60

        val All: List<AgentTemplate> = listOf(
            AgentTemplate(
                name = "Morning briefing",
                summary = "What happened overnight, in five bullets",
                prompt = "Search the web for what has happened in the last 24 hours in " +
                    "technology and world news. Give me at most five bullets, each one sentence, " +
                    "most important first. Skip anything you would call filler, and say so if " +
                    "there is genuinely nothing worth reporting.",
                minuteOfDay = MORNING,
                days = Agent.WEEKDAYS,
            ),
            AgentTemplate(
                name = "Topic watch",
                summary = "Track one subject and report only what is new",
                prompt = "Search for anything published in the last week about <your topic here>. " +
                    "Report only what is genuinely new — not background, not explainers. Three " +
                    "bullets at most, each with a link. If nothing new has appeared, say exactly " +
                    "that in one line.",
                minuteOfDay = MIDDAY,
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            ),
            AgentTemplate(
                name = "Repository triage",
                summary = "Issues and pull requests that need a person",
                prompt = "Look at the open issues and pull requests on my repositories. List the " +
                    "ones that are waiting on me specifically — a review requested, a question " +
                    "asked, a failing check on something I opened. One line each, newest first. " +
                    "Ignore anything that is only waiting on someone else.",
                minuteOfDay = 9 * 60,
                days = Agent.WEEKDAYS,
                needsGitHub = true,
            ),
            AgentTemplate(
                name = "Weekly review",
                summary = "What you worked on, pulled back together",
                prompt = "Look back over what we have talked about this week using what you " +
                    "remember about me. Write three short sections: what moved forward, what is " +
                    "still open, and the one thing worth starting on Monday. Be concrete — name " +
                    "the actual things, not categories of thing.",
                minuteOfDay = EVENING,
                days = setOf(DayOfWeek.FRIDAY),
            ),
            AgentTemplate(
                name = "Tomorrow, tonight",
                summary = "A short plan while there is still time to change it",
                prompt = "Based on what you remember about what I am working on, write tomorrow's " +
                    "plan: at most three things, in the order I should do them, with one sentence " +
                    "each on why that order. If you do not know enough to be useful, say so " +
                    "rather than inventing a plausible day.",
                minuteOfDay = NIGHT,
                days = setOf(
                    DayOfWeek.SUNDAY,
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                ),
            ),
            AgentTemplate(
                name = "One thing a day",
                summary = "A single idea, explained properly",
                prompt = "Teach me one genuinely interesting idea from mathematics, engineering " +
                    "or the history of computing. Two short paragraphs: what it is, and why " +
                    "somebody needed it. No preamble, no list of further reading, and do not " +
                    "repeat an idea you have sent before.",
                minuteOfDay = 8 * 60,
                days = DayOfWeek.entries.toSet(),
                toolsEnabled = false,
            ),
        )
    }
}
