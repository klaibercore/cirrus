package dev.klaiber.cirrus.ui.chat

import dev.klaiber.cirrus.ui.chat.components.formatDuration
import dev.klaiber.cirrus.ui.chat.components.summarizeTools
import dev.klaiber.cirrus.ui.chat.components.toolIconName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one line a collapsed group of tool calls shows.
 *
 * Worth a test because it is the only part of the group a reader is guaranteed to see: if the
 * summary is wrong or unreadable, the calls behind it may as well not have been recorded.
 */
class ToolActivityTest {

    @Test
    fun `names are listed in the order they were first called`() {
        assertEquals(
            "web search · run command",
            summarizeTools(listOf("web_search", "run_command")),
        )
    }

    @Test
    fun `repeats fold into a count rather than repeating the name`() {
        assertEquals(
            "run command ×3 · web search",
            summarizeTools(listOf("run_command", "web_search", "run_command", "run_command")),
        )
    }

    /** The line has one line's worth of room, and "and 2 more" beats an ellipsis mid-word. */
    @Test
    fun `a long list is capped and the remainder counted`() {
        val summary = summarizeTools(
            listOf("web_search", "web_fetch", "run_command", "get_datetime", "list_issues"),
            limit = 3,
        )

        assertEquals("web search · web fetch · run command · and 2 more", summary)
    }

    @Test
    fun `no calls means no summary`() {
        assertEquals("", summarizeTools(emptyList()))
    }

    /** The glyph is what finds the shell command among four searches without reading any of them. */
    @Test
    fun `tools are grouped into families by name`() {
        assertEquals("web", toolIconName("web_search"))
        assertEquals("shell", toolIconName("run_command"))
        assertEquals("shell", toolIconName("clean_workspace"))
        assertEquals("time", toolIconName("get_datetime"))
        assertEquals("memory", toolIconName("remember_fact"))
        assertEquals("music", toolIconName("spotify_search"))
        assertEquals("tool", toolIconName("create_issue"))
    }

    @Test
    fun `durations read as milliseconds until they stop being readable as milliseconds`() {
        assertEquals("240 ms", formatDuration(240))
        assertEquals("1.2 s", formatDuration(1_240))
    }
}
