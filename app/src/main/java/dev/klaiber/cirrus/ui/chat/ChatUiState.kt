package dev.klaiber.cirrus.ui.chat

import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.domain.model.StarterPrompt

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val input: String = "",
    /** Dictation the recogniser has not finalised yet; rendered after [input]. */
    val voicePartial: String = "",
    val pendingAttachments: List<Attachment> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val availableModels: List<ModelInfo> = emptyList(),
    val errorBanner: String? = null,
    /** True until the API key exists; the composer is replaced by an onboarding prompt. */
    val needsApiKey: Boolean = false,
    val search: ChatSearch = ChatSearch(),
    /** Set while this thread is still an agent's run, so the transcript can say whose it is. */
    val agentName: String? = null,
) {
    /**
     * True for a thread an agent wrote and nobody has replied to yet.
     *
     * Worth saying out loud in the transcript: it explains why the thread is not in the drawer,
     * and why the first message reads like an instruction rather than a question.
     */
    val isAgentRun: Boolean get() = conversation?.isAgentRun == true

    /** Openers for a blank conversation, matched to what is actually switched on. */
    val starterPrompts: List<StarterPrompt>
        get() = if (settings.showStarterPrompts) {
            StarterPrompt.forSettings(settings, toolsEnabled)
        } else {
            emptyList()
        }

    val title: String get() = conversation?.title ?: Conversation.DEFAULT_TITLE

    val model: String get() = conversation?.model ?: settings.defaultModel

    val params: GenerationParams get() = conversation?.params ?: settings.defaultParams

    val toolsEnabled: Boolean
        get() = conversation?.toolsEnabled ?: settings.toolsEnabledByDefault

    val systemPrompt: String? get() = conversation?.systemPrompt

    val modelInfo: ModelInfo? get() = availableModels.firstOrNull { it.name == model }

    /** What the composer shows: typed text plus any dictation still being transcribed. */
    val composerText: String get() = input + voicePartial

    val canSend: Boolean
        get() = !isGenerating &&
            !needsApiKey &&
            model.isNotBlank() &&
            (composerText.isNotBlank() || pendingAttachments.isNotEmpty())

    val isEmpty: Boolean get() = messages.isEmpty() && !isGenerating
}

/**
 * Find-in-conversation.
 *
 * Matching is per message rather than per occurrence: the transcript scrolls by message, so a
 * "next" that lands twice inside the same answer would look like it had done nothing. Every
 * occurrence is still highlighted — only the jumping is coarse.
 */
data class ChatSearch(
    val isActive: Boolean = false,
    val query: String = "",
    val matchIds: List<String> = emptyList(),
    val currentIndex: Int = 0,
) {
    /** The query, but only once it is worth highlighting for. */
    val highlight: String get() = if (isActive && query.isNotBlank()) query else ""

    val currentId: String? get() = matchIds.getOrNull(currentIndex)

    val hasMatches: Boolean get() = matchIds.isNotEmpty()

    val label: String
        get() = when {
            query.isBlank() -> ""
            matchIds.isEmpty() -> "No matches"
            else -> "${currentIndex + 1}/${matchIds.size}"
        }
}

/** One-shot effects that the screen consumes rather than rendering from state. */
sealed interface ChatEvent {
    data class NavigateToConversation(val conversationId: String) : ChatEvent

    data class ShowMessage(val text: String) : ChatEvent

    data class ShareText(val text: String, val subject: String) : ChatEvent
}
