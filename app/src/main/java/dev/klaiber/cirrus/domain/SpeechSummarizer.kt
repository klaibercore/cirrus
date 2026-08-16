package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.domain.model.ReadAloudStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns an answer into something worth *listening* to.
 *
 * Reading a written answer out verbatim is the obvious implementation and the wrong one, because a
 * good written answer is built for a reader who can skim. It opens by restating the question, lays
 * its options out as a bulleted list, shows the code, and puts the caveats at the end — all of which
 * a reader takes in at a glance and a listener has to sit through in real time, in order, unable to
 * look ahead. Four minutes of "Option one, colon" is not the answer being read aloud; it is the
 * answer's *layout* being read aloud.
 *
 * So the spoken version is written for the ear: what the answer concluded, the reasoning that
 * matters, and what to do about it, in continuous prose. Semi-longform on purpose — the failure mode
 * on the other side is a one-line summary that says an answer exists without saying what it was,
 * which leaves the listener needing to go and read the thing anyway.
 *
 * Three things keep this from making read-aloud worse than it was:
 *
 *  - **Short answers are spoken as written.** Under [VERBATIM_BELOW] characters there is nothing to
 *    summarise: the answer is already about as long as its own summary, and paraphrasing it only
 *    introduces the chance of getting it wrong.
 *  - **Failure falls back rather than fails.** No model configured, a request that dies, an empty
 *    reply — every one of them returns the original text. The worst case is the behaviour this
 *    replaced, never silence.
 *  - **The user can turn it off.** [ReadAloudStyle.VERBATIM] is one tap away for anyone who wants
 *    the words on the screen and not a précis of them.
 */
@Singleton
class SpeechSummarizer @Inject constructor(
    private val engine: ChatEngine,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
) {

    /**
     * The text to actually speak for [spokenText], which is an answer already flattened for
     * speech. Returns [spokenText] itself whenever a summary is not wanted or not available.
     */
    suspend fun prepare(spokenText: String): String {
        val current = settings.current.value
        if (current.readAloudStyle != ReadAloudStyle.SUMMARY) return spokenText
        if (spokenText.length < VERBATIM_BELOW) return spokenText

        val model = current.defaultModel.takeIf { it.isNotBlank() } ?: return spokenText
        val thinks = models.find(model)?.supportsThinking ?: ModelInfo.mayThink(model)

        val summary = engine.complete(
            model = model,
            system = SYSTEM_PROMPT,
            user = spokenText.take(MAX_INPUT_CHARS),
            supportsThinking = thinks,
            // Two-thirds of the budget goes on reasoning the moment a thinking model ignores
            // `think: false`, and a summary cut off mid-sentence is worse than no summary.
            tokenBudget = if (thinks) THINKING_BUDGET else BUDGET,
            temperature = 0.3,
        )

        return summary?.let(::tidy)?.takeIf { it.isNotBlank() } ?: spokenText
    }

    /**
     * Strips the wrapping a model reaches for even when told not to.
     *
     * Reasoning tags matter more here than anywhere else in the app: a stray `<think>` block in a
     * title is an ugly string in a list, and in a summary it is ninety seconds of the model's inner
     * monologue read out in a human voice before the answer starts.
     */
    private fun tidy(raw: String): String {
        val withoutThinking = THINK_BLOCK.replace(raw, " ")
        val answer = THINK_OPEN.split(withoutThinking, limit = 2).first()
        return answer
            .lineSequence()
            .filterNot { it.trimStart().startsWith("```") }
            .joinToString("\n")
            .replace(LEAD_IN, "")
            .trim()
    }

    private companion object {
        val SYSTEM_PROMPT =
            "You are turning a written answer into something to be listened to. The listener " +
                "cannot see the screen, cannot skim, and cannot go back a line.\n\n" +
                "Write a spoken summary: what the answer actually concluded, the reasoning that " +
                "matters, and anything the listener has to act on or be careful about. Aim for a " +
                "few short paragraphs — long enough that they need not go and read the original, " +
                "short enough to be worth hearing instead of it. Weight it by substance, not by " +
                "how much room each part took on the page.\n\n" +
                "Plain continuous prose, in the second person, in the language of the answer. No " +
                "headings, no bullets, no numbering, no markdown, no code — describe what code " +
                "does rather than dictating it. Do not open by saying that this is a summary, and " +
                "do not mention the original. Just say it."

        /**
         * Under this, an answer is its own summary.
         *
         * Roughly a screen of prose. The cost of the cut-off being slightly wrong is small in both
         * directions: a little verbatim reading, or a little paraphrase.
         */
        const val VERBATIM_BELOW = 700

        /** Enough of a very long answer to summarise faithfully, without a huge prompt. */
        const val MAX_INPUT_CHARS = 24_000

        const val BUDGET = 900
        const val THINKING_BUDGET = 2_400

        val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        val THINK_OPEN = Regex("<think>")

        /** "Here is a summary of the answer:" and its cousins, which the prompt asks against. */
        val LEAD_IN = Regex(
            "^\\s*(here('s| is)[^.\\n]{0,60}summary[^.\\n]{0,40}[.:]|summary[.:])\\s*",
            setOf(RegexOption.IGNORE_CASE),
        )
    }
}
