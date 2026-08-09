package dev.klaiber.cirrus.ui.chat

import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateTest {

    private val conversation = Conversation(
        id = "c1",
        title = "My thread",
        model = "qwen3",
        params = GenerationParams(temperature = 0.5f),
        toolsEnabled = true,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `canSend - requires model and content`() {
        assertFalse(ChatUiState().canSend)
        assertFalse(ChatUiState(settings = AppSettings(defaultModel = "qwen3")).canSend)
        assertTrue(ChatUiState(conversation = conversation, input = "hi").canSend)
    }

    @Test
    fun `canSend - blocked while generating`() {
        val state = ChatUiState(conversation = conversation, input = "hi", isGenerating = true)
        assertFalse(state.canSend)
    }

    @Test
    fun `canSend - blocked when api key needed`() {
        val state = ChatUiState(conversation = conversation, input = "hi", needsApiKey = true)
        assertFalse(state.canSend)
    }

    @Test
    fun `canSend - attachments alone are enough`() {
        val attachment = Attachment(
            id = "a1",
            messageId = "m1",
            displayName = "photo.png",
            mimeType = "image/png",
            sizeBytes = 100,
            localPath = "/tmp/photo.png",
            kind = Attachment.Kind.IMAGE,
        )
        assertTrue(ChatUiState(conversation = conversation, pendingAttachments = listOf(attachment)).canSend)
    }

    @Test
    fun `composerText appends dictation still being transcribed`() {
        val state = ChatUiState(conversation = conversation, input = "hello", voicePartial = " world")
        assertEquals("hello world", state.composerText)
    }

    @Test
    fun `canSend - unfinalised dictation alone is enough`() {
        val state = ChatUiState(conversation = conversation, voicePartial = "hello")
        assertTrue(state.canSend)
    }

    @Test
    fun `title and model fall back to settings`() {
        assertEquals("My thread", ChatUiState(conversation = conversation).title)
        assertEquals("New chat", ChatUiState().title)
        assertEquals("qwen3", ChatUiState(conversation = conversation).model)
        assertEquals("qwen3", ChatUiState(settings = AppSettings(defaultModel = "qwen3")).model)
    }

    @Test
    fun `params and tools fall back to settings`() {
        assertEquals(0.5f, ChatUiState(conversation = conversation).params.temperature!!)
        assertTrue(ChatUiState(conversation = conversation).toolsEnabled)
        assertTrue(ChatUiState(settings = AppSettings(toolsEnabledByDefault = true)).toolsEnabled)
    }

    @Test
    fun `isEmpty`() {
        assertTrue(ChatUiState().isEmpty)
        assertFalse(ChatUiState(conversation = conversation, isGenerating = true).isEmpty)
    }
}
