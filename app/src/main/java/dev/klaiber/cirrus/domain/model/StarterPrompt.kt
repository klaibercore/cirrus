package dev.klaiber.cirrus.domain.model

/**
 * The openers offered on an empty chat.
 *
 * A blank composer with a blinking cursor is the hardest screen in the app: it asks a question
 * ("what can this do?") that it does not answer. Four suggestions answer it in the only way that
 * actually helps — by being things you can press.
 *
 * Which four depends on what is switched on. Offering "check my pull requests" to someone with no
 * GitHub token is worse than offering nothing: it advertises a capability, and then fails.
 */
data class StarterPrompt(val label: String, val prompt: String) {
    companion object {
        private val Always = listOf(
            StarterPrompt(
                label = "Explain something",
                prompt = "Explain how HTTPS actually keeps a connection private, at the level of " +
                    "someone who writes software but has never implemented TLS.",
            ),
            StarterPrompt(
                label = "Draft a reply",
                prompt = "Help me write a short, polite reply declining a meeting invitation " +
                    "without giving a reason.",
            ),
            StarterPrompt(
                label = "Review some code",
                prompt = "I am going to paste a function. Tell me what is wrong with it, worst " +
                    "problem first, and stop at three points.",
            ),
            StarterPrompt(
                label = "Think it through",
                prompt = "I have a decision to make and I keep going round in circles. Ask me " +
                    "questions one at a time until you understand it, then tell me what you think.",
            ),
        )

        private val WithTools = listOf(
            StarterPrompt(
                label = "What happened today",
                prompt = "Search the web and tell me the three most significant things that " +
                    "happened in technology today. One sentence each, with a link.",
            ),
            StarterPrompt(
                label = "Compare two things",
                prompt = "Search for a current comparison of two things I am choosing between, " +
                    "and lay out the trade-off rather than picking a winner. I will tell you what " +
                    "the two things are.",
            ),
        )

        private val WithGitHub = listOf(
            StarterPrompt(
                label = "Catch up on a repo",
                prompt = "Summarise what has changed in one of my repositories in the last week " +
                    "— merged pull requests, new issues, anything waiting on me.",
            ),
        )

        private val WithMemory = listOf(
            StarterPrompt(
                label = "Pick up where we left off",
                prompt = "What do you remember about what I am working on? Start from there and " +
                    "ask me what has moved since.",
            ),
        )

        /** Four openers matched to what this install can actually do. */
        fun forSettings(settings: AppSettings, toolsEnabled: Boolean, limit: Int = 4): List<StarterPrompt> {
            val available = buildList {
                if (toolsEnabled) addAll(WithTools)
                if (toolsEnabled && settings.gitHubToolsEnabled && settings.hasGitHubToken) {
                    addAll(WithGitHub)
                }
                if (settings.memoryEnabled) addAll(WithMemory)
                addAll(Always)
            }
            return available.take(limit)
        }
    }
}
