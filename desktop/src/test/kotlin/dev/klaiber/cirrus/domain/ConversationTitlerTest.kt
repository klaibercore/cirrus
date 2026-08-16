package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitlerTest {

    private val now = 1_700_000_000_000L

    private fun conversation(
        title: String = Conversation.DEFAULT_TITLE,
        autoTitledAt: Long? = null,
    ) = Conversation(
        id = "conv-1",
        title = title,
        model = "qwen3",
        createdAt = 0L,
        updatedAt = 0L,
        autoTitledAt = autoTitledAt,
    )

    private fun message(
        role: Role,
        content: String,
        sequence: Int = 0,
        errorMessage: String? = null,
    ) = ChatMessage(
        id = "msg-$sequence-$role",
        conversationId = "conv-1",
        role = role,
        content = content,
        createdAt = 0L,
        sequence = sequence,
        errorMessage = errorMessage,
    )

    // ---- When a thread is due a title --------------------------------------------------------

    @Test
    fun `a thread still carrying the placeholder is due`() {
        assertTrue(ConversationTitler.isDue(conversation(), now))
    }

    @Test
    fun `a name the user typed is never touched`() {
        assertFalse(ConversationTitler.isDue(conversation(title = "Taxes"), now))
    }

    @Test
    fun `a freshly auto-titled thread waits out the interval`() {
        val justTitled = conversation(title = "Room migrations", autoTitledAt = now - 60_000L)
        assertFalse(ConversationTitler.isDue(justTitled, now))

        val stale = conversation(
            title = "Room migrations",
            autoTitledAt = now - ConversationTitler.RETITLE_INTERVAL_MS - 1,
        )
        assertTrue(ConversationTitler.isDue(stale, now))
    }

    /** A locally derived title is stamped at the epoch so the next turn can improve on it. */
    @Test
    fun `a fallback title is due again immediately`() {
        val fallback = conversation(
            title = "How do I center a div",
            autoTitledAt = Conversation.FALLBACK_TITLED_AT,
        )
        assertTrue(ConversationTitler.isDue(fallback, now))
    }

    // ---- What gets summarised ---------------------------------------------------------------

    @Test
    fun `only real content from the two speakers is summarised`() {
        val messages = listOf(
            message(Role.SYSTEM, "You are helpful.", 0),
            message(Role.USER, "Hello", 1),
            message(Role.ASSISTANT, "", 2),
            message(Role.ASSISTANT, "Broke", 3, errorMessage = "Network error"),
            message(Role.TOOL, """{"ok":true}""", 4),
            message(Role.ASSISTANT, "Hi there", 5),
        )
        val sources = ConversationTitler.titleSources(messages)
        assertEquals(listOf("Hello", "Hi there"), sources.map { it.content })
    }

    @Test
    fun `digest labels the speakers`() {
        val digest = ConversationTitler.digest(
            listOf(
                message(Role.USER, "What is Room?", 0),
                message(Role.ASSISTANT, "A database.", 1),
            ),
        )
        assertEquals("User: What is Room?\n\nAssistant: A database.", digest)
    }

    @Test
    fun `digest keeps the opening exchange and the latest turns`() {
        val messages = (0 until 20).map { index ->
            message(if (index % 2 == 0) Role.USER else Role.ASSISTANT, "m$index", index)
        }
        val digest = ConversationTitler.digest(messages)

        assertTrue(digest.contains("m0"))
        assertTrue(digest.contains("m1"))
        assertTrue(digest.contains("m19"))
        // The middle is the least informative part and the most expensive to send.
        assertFalse(digest.contains("m10"))
        assertEquals(8, digest.split("\n\n").size)
    }

    // ---- The local fallback ------------------------------------------------------------------

    @Test
    fun `falls back to the opening question`() {
        val messages = listOf(
            message(Role.USER, "  How do I  center\na div?  ", 0),
            message(Role.ASSISTANT, "With flexbox.", 1),
        )
        assertEquals("How do I center a div?", ConversationTitler.fallbackTitle(messages))
    }

    @Test
    fun `a long opening question is cut at a word boundary`() {
        val opening = "Explain the difference between a Room migration and a destructive " +
            "fallback in as much detail as you can"
        val title = ConversationTitler.fallbackTitle(listOf(message(Role.USER, opening, 0)))!!

        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= 60)
        assertTrue(opening.startsWith(title.removeSuffix("…")))
        assertFalse(title.removeSuffix("…").endsWith(" "))
    }

    @Test
    fun `there is no fallback without something the user said`() {
        assertNull(ConversationTitler.fallbackTitle(emptyList()))
        assertNull(ConversationTitler.fallbackTitle(listOf(message(Role.ASSISTANT, "Hi", 0))))
        assertNull(ConversationTitler.fallbackTitle(listOf(message(Role.USER, "   ", 0))))
    }
}
