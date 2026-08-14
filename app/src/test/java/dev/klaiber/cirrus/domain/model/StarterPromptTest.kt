package dev.klaiber.cirrus.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which openers get offered.
 *
 * The rule worth pinning down is the negative one: a suggestion that advertises a capability this
 * install does not have is worse than no suggestion, because pressing it produces a failure rather
 * than an answer.
 */
class StarterPromptTest {

    @Test
    fun `web suggestions need tools switched on`() {
        val without = StarterPrompt.forSettings(AppSettings(), toolsEnabled = false, limit = 10)
        assertFalse(without.any { it.prompt.contains("Search the web") })

        val with = StarterPrompt.forSettings(AppSettings(), toolsEnabled = true, limit = 10)
        assertTrue(with.any { it.prompt.contains("Search the web") })
    }

    @Test
    fun `the repository suggestion needs both the tools and a token`() {
        fun offered(settings: AppSettings) =
            StarterPrompt.forSettings(settings, toolsEnabled = true, limit = 10)
                .any { it.label == "Catch up on a repo" }

        assertFalse(offered(AppSettings()))
        assertFalse(offered(AppSettings(gitHubToolsEnabled = true)))
        assertFalse(offered(AppSettings(hasGitHubToken = true)))
        assertTrue(offered(AppSettings(gitHubToolsEnabled = true, hasGitHubToken = true)))
    }

    @Test
    fun `memory suggestion follows the memory switch`() {
        fun offered(enabled: Boolean) =
            StarterPrompt.forSettings(AppSettings(memoryEnabled = enabled), toolsEnabled = false, limit = 10)
                .any { it.label == "Pick up where we left off" }

        assertTrue(offered(true))
        assertFalse(offered(false))
    }

    @Test
    fun `there are always four, and they are always distinct`() {
        listOf(
            AppSettings() to false,
            AppSettings() to true,
            AppSettings(memoryEnabled = false) to false,
            AppSettings(gitHubToolsEnabled = true, hasGitHubToken = true) to true,
        ).forEach { (settings, tools) ->
            val offered = StarterPrompt.forSettings(settings, tools)
            assertEquals(4, offered.size)
            assertEquals(offered.size, offered.map { it.label }.toSet().size)
        }
    }
}
