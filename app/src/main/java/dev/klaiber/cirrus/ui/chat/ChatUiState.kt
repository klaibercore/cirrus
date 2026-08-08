package dev.klaiber.cirrus.ui.chat

import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.ModelInfo

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val input: String = "",
    val pendingAttachments: List<Attachment> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val availableModels: List<ModelInfo> = emptyList(),
    val errorBanner: String? = null,
    /** True until the API key exists; the composer is replaced by an onboarding prompt. */
    val needsApiKey: Boolean = false,
) {
    val title: String get() = conversation?.title ?: "New chat"

    val model: String get() = conversation?.model ?: settings.defaultModel

    val params: GenerationParams get() = conversation?.params ?: settings.defaultParams

    val toolsEnabled: Boolean
        get() = conversation?.toolsEnabled ?: settings.toolsEnabledByDefault

    val systemPrompt: String? get() = conversation?.systemPrompt

    val modelInfo: ModelInfo? get() = availableModels.firstOrNull { it.name == model }

    val canSend: Boolean
        get() = !isGenerating &&
            !needsApiKey &&
            model.isNotBlank() &&
            (input.isNotBlank() || pendingAttachments.isNotEmpty())

    val isEmpty: Boolean get() = messages.isEmpty() && !isGenerating
}

/** One-shot effects that the screen consumes rather than rendering from state. */
sealed interface ChatEvent {
    data class NavigateToConversation(val conversationId: String) : ChatEvent

    data class ShowMessage(val text: String) : ChatEvent

    data class ShareText(val text: String, val subject: String) : ChatEvent
}
