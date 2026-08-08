package dev.klaiber.cirrus.ui.chat

import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.ToolInvocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExporterTest {

    private val conversation = Conversation(
        id = "c1",
        title = "Quantum chat",
        model = "qwen3",
        systemPrompt = "Be concise.",
        params = GenerationParams(temperature = 0.7f),
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun message(role: Role, content: String, sequence: Int) = ChatMessage(
        id = "m$sequence",
        conversationId = "c1",
        role = role,
        content = content,
        createdAt = 0L,
        sequence = sequence,
    )

    @Test
    fun `exports title model and system prompt`() {
        val markdown = ConversationExporter.toMarkdown(conversation, emptyList())
        assertTrue(markdown.startsWith("# Quantum chat"))
        assertTrue(markdown.contains("- **Model:** `qwen3`"))
        assertTrue(markdown.contains("- **System prompt:** Be concise."))
        assertTrue(markdown.contains("- **Parameters:** temperature=0.7"))
    }

    @Test
    fun `exports messages with speakers`() {
        val messages = listOf(
            message(Role.USER, "What is a qubit?", 0),
            message(Role.ASSISTANT, "A qubit is a two-state system.", 1),
        )
        val markdown = ConversationExporter.toMarkdown(conversation, messages)
        assertTrue(markdown.contains("## User"))
        assertTrue(markdown.contains("What is a qubit?"))
        assertTrue(markdown.contains("## Assistant"))
        assertTrue(markdown.contains("A qubit is a two-state system."))
    }

    @Test
    fun `exports thinking and tool invocations`() {
        val messages = listOf(
            message(Role.ASSISTANT, "Answer", 0).copy(
                thinking = "Let me think...",
                toolInvocations = listOf(
                    ToolInvocation(
                        id = "t1",
                        name = "web_search",
                        argumentsJson = """{"query":"x"}""",
                        resultJson = """{"results":[]}""",
                    ),
                ),
            ),
        )
        val markdown = ConversationExporter.toMarkdown(conversation, messages)
        assertTrue(markdown.contains("<details><summary>Reasoning</summary>"))
        assertTrue(markdown.contains("Let me think..."))
        assertTrue(markdown.contains("`web_search` → {\"query\":\"x\"}"))
    }

    @Test
    fun `exports errors`() {
        val messages = listOf(
            message(Role.ASSISTANT, "", 0).copy(errorMessage = "Network error"),
        )
        val markdown = ConversationExporter.toMarkdown(conversation, messages)
        assertTrue(markdown.contains("> **Error:** Network error"))
    }

    @Test
    fun `skips system messages`() {
        val messages = listOf(
            message(Role.SYSTEM, "You are helpful.", 0),
            message(Role.USER, "Hi", 1),
        )
        val markdown = ConversationExporter.toMarkdown(conversation, messages)
        assertFalse(markdown.contains("You are helpful."))
        assertTrue(markdown.contains("## User"))
    }
}
