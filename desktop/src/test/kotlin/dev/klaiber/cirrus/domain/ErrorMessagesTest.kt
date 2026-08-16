package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.data.remote.OllamaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The wording a failure reaches the user as.
 *
 * Worth pinning even though it is only a `when`: this is the one place the app explains itself when
 * something has gone wrong, it is shared between the chat screen and a turn that outlives it, and
 * every branch is supposed to name something the reader can actually do. A silent regression here
 * looks like the app shrugging.
 */
class ErrorMessagesTest {

    @Test
    fun `a missing key points at settings`() {
        val message = OllamaException.MissingApiKey().userMessage()

        assertTrue(message, message.contains("Settings"))
    }

    @Test
    fun `a rejected key says the key was the problem`() {
        val message = OllamaException.Unauthorized(detail = null).userMessage()

        assertTrue(message, message.contains("Settings"))
        assertTrue(message, message.contains("rejected", ignoreCase = true))
    }

    @Test
    fun `rate limiting quotes the wait when the server gave one`() {
        assertEquals(
            "Rate limited. Try again in 30s.",
            OllamaException.RateLimited(detail = null, retryAfterSeconds = 30L).userMessage(),
        )
    }

    @Test
    fun `rate limiting stays useful when the server gave no wait`() {
        val message = OllamaException.RateLimited(detail = null, retryAfterSeconds = null).userMessage()

        assertEquals("Rate limited. Try again shortly.", message)
        assertFalse("must not render a null delay", message.contains("null"))
    }

    @Test
    fun `a missing model names the model`() {
        val message = OllamaException.ModelNotFound(model = "qwen3:8b", detail = null).userMessage()

        assertTrue(message, message.contains("qwen3:8b"))
    }

    /**
     * A truncated reply is the one failure with a specific, non-obvious remedy, and the message is
     * the only place the user is told what it is.
     */
    @Test
    fun `a truncated reply tells the reader to regenerate`() {
        val message = OllamaException.Truncated().userMessage()

        assertTrue(message, message.contains("Regenerate", ignoreCase = true))
    }

    @Test
    fun `a network failure carries the underlying reason`() {
        val message = OllamaException.Network(IOException("connection reset")).userMessage()

        assertTrue(message, message.contains("connection reset"))
    }

    @Test
    fun `an unrecognised failure falls back to its own message`() {
        assertEquals("disk is full", IOException("disk is full").userMessage())
    }

    @Test
    fun `a failure with no message still says something`() {
        val message = IllegalStateException().userMessage()

        assertEquals("Something went wrong.", message)
        assertFalse("null must never reach the banner", message.contains("null"))
    }

    /** Whatever the failure, the banner gets a sentence rather than a stack trace or a blank. */
    @Test
    fun `every failure produces non-empty prose`() {
        val failures = listOf(
            OllamaException.MissingApiKey(),
            OllamaException.Unauthorized(detail = null),
            OllamaException.RateLimited(detail = null, retryAfterSeconds = null),
            OllamaException.RateLimited(detail = null, retryAfterSeconds = 5L),
            OllamaException.ModelNotFound(model = "m", detail = null),
            OllamaException.Truncated(),
            OllamaException.Network(IOException("x")),
            IOException("y"),
            IllegalStateException(),
        )

        failures.forEach { failure ->
            val message = failure.userMessage()
            assertTrue("blank message for ${failure::class.simpleName}", message.isNotBlank())
            assertFalse(
                "leaked a class name for ${failure::class.simpleName}: $message",
                message.contains("Exception"),
            )
        }
    }
}
