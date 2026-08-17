package dev.klaiber.cirrus.ui.chat

import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders a conversation as portable Markdown for sharing or archiving. */
object ConversationExporter {

    fun toMarkdown(conversation: Conversation, messages: List<ChatMessage>): String = buildString {
        appendLine("# ${conversation.title}")
        appendLine()
        appendLine("- **Model:** `${conversation.model}`")
        appendLine("- **Created:** ${formatTimestamp(conversation.createdAt)}")
        conversation.systemPrompt?.let {
            appendLine("- **System prompt:** $it")
        }
        if (conversation.params.hasOverrides) {
            appendLine("- **Parameters:** ${describeParams(conversation)}")
        }
        appendLine()
        appendLine("---")
        appendLine()

        messages.filter { it.role != Role.SYSTEM }.forEach { message ->
            val speaker = when (message.role) {
                Role.USER -> "User"
                Role.ASSISTANT -> "Assistant"
                Role.TOOL -> "Tool"
                Role.SYSTEM -> "System"
            }
            appendLine("## $speaker")
            appendLine()

            message.attachments.forEach { attachment ->
                appendLine("> Attached: `${attachment.displayName}` (${attachment.mimeType})")
            }
            if (message.attachments.isNotEmpty()) appendLine()

            message.thinking?.takeIf { it.isNotBlank() }?.let { thinking ->
                appendLine("<details><summary>Reasoning</summary>")
                appendLine()
                appendLine(thinking.trim())
                appendLine()
                appendLine("</details>")
                appendLine()
            }

            message.toolInvocations.forEach { invocation ->
                appendLine("> `${invocation.name}` → ${invocation.argumentsJson}")
            }
            if (message.toolInvocations.isNotEmpty()) appendLine()

            appendLine(message.content.trim())
            appendLine()

            message.errorMessage?.let {
                appendLine("> **Error:** $it")
                appendLine()
            }
        }
    }.trimEnd() + "\n"

    private fun describeParams(conversation: Conversation): String {
        val params = conversation.params
        return buildList {
            params.temperature?.let { add("temperature=$it") }
            params.topP?.let { add("top_p=$it") }
            params.topK?.let { add("top_k=$it") }
            params.minP?.let { add("min_p=$it") }
            params.repeatPenalty?.let { add("repeat_penalty=$it") }
            params.seed?.let { add("seed=$it") }
            params.numCtx?.let { add("num_ctx=$it") }
            params.numPredict?.let { add("num_predict=$it") }
            if (params.stop.isNotEmpty()) add("stop=${params.stop.joinToString("|")}")
        }.joinToString(", ").ifEmpty { "defaults" }
    }

    private fun formatTimestamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
}
