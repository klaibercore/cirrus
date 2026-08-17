package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.domain.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a thread's name in step with what the conversation has become.
 *
 * This runs on the application scope rather than the chat screen's: naming happens *after* the
 * answer is on screen, which is exactly the moment people switch threads or back out, and a
 * title request cancelled halfway leaves the thread called "New chat" forever.
 *
 * A title the user typed is never touched — [Conversation.autoTitledAt] is null for those, and
 * `rename` clears it back to null whenever they type a new one.
 */
class ConversationTitler(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val engine: ChatEngine,
    private val scope: CoroutineScope,
) {

    /** Threads with a pass already running. Plain (non-suspending) so releasing it cannot fail. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /**
     * Fire-and-forget hook for the end of an assistant turn.
     *
     * Safe to call from a coroutine that is itself being cancelled — the work is handed to the
     * application scope, so stopping a generation still names the thread it produced.
     */
    fun onTurnFinished(conversationId: String) {
        scope.launch { retitle(conversationId) }
    }

    /**
     * Re-summarises [conversationId] if it is due, and names it locally if the model cannot.
     *
     * Concurrent passes for the same thread are dropped rather than queued: two turns finishing
     * close together would otherwise pay for the same title twice.
     */
    suspend fun retitle(conversationId: String) {
        if (!settings.current.value.autoTitleConversations) return
        if (!inFlight.add(conversationId)) return
        try {
            val conversation = conversations.getConversation(conversationId) ?: return
            val now = System.currentTimeMillis()
            if (!isDue(conversation, now)) return

            val messages = titleSources(conversations.getMessages(conversationId))
            if (messages.isEmpty()) return

            val suggested = engine.suggestTitle(
                model = conversation.model,
                transcript = digest(messages),
                supportsThinking = supportsThinking(conversation.model),
            )
            if (suggested != null) {
                conversations.applyAutoTitle(conversationId, suggested, now)
                return
            }

            // The host is unreachable, or the model answered with nothing usable. A thread the
            // user can recognise beats "New chat", so fall back to its opening message — stamped
            // as long-titled so the next turn still tries for a real summary.
            if (!conversation.isUntitled) return
            fallbackTitle(messages)?.let { title ->
                conversations.applyAutoTitle(conversationId, title, Conversation.FALLBACK_TITLED_AT)
            }
        } finally {
            inFlight.remove(conversationId)
        }
    }

    /** Server-reported capability when the catalogue has one, the model's name otherwise. */
    private fun supportsThinking(model: String): Boolean =
        models.find(model)?.supportsThinking ?: ModelInfo.mayThink(model)

    companion object {
        /**
         * How long an auto-title stands before the thread is summarised again. Long enough that
         * a busy half-hour of chat costs one extra request, not one per turn.
         */
        const val RETITLE_INTERVAL_MS = 30 * 60 * 1000L

        private const val TITLE_CONTEXT_MESSAGES = 8
        private const val TITLE_HEAD_MESSAGES = 2
        private const val TITLE_MESSAGE_CHARS = 400
        private const val MAX_TITLE_CHARS = 60

        private val WHITESPACE = Regex("\\s+")

        /**
         * Whether [conversation] is owed a title.
         *
         * A never-titled thread qualifies only while it still carries the placeholder, which is
         * what protects a name the user typed before the first answer arrived.
         */
        fun isDue(conversation: Conversation, now: Long): Boolean {
            val lastTitledAt = conversation.autoTitledAt ?: return conversation.isUntitled
            return now - lastTitledAt >= RETITLE_INTERVAL_MS
        }

        /** The messages worth summarising: real content from the two speakers, errors dropped. */
        fun titleSources(messages: List<ChatMessage>): List<ChatMessage> = messages
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .filter { it.errorMessage == null && it.content.isNotBlank() }

        /**
         * A digest of the conversation for the titler: the opening exchange, which sets the
         * topic, plus the latest turns, which show where it has got to. The middle is dropped —
         * it is the least informative part and the most expensive to send.
         */
        fun digest(messages: List<ChatMessage>): String {
            val excerpt = if (messages.size <= TITLE_CONTEXT_MESSAGES) {
                messages
            } else {
                messages.take(TITLE_HEAD_MESSAGES) +
                    messages.takeLast(TITLE_CONTEXT_MESSAGES - TITLE_HEAD_MESSAGES)
            }
            return excerpt.joinToString("\n\n") { message ->
                val speaker = if (message.role == Role.USER) "User" else "Assistant"
                "$speaker: ${message.content.take(TITLE_MESSAGE_CHARS)}"
            }
        }

        /**
         * A title taken from the opening question, for when the model cannot supply one.
         *
         * Cut at a word boundary: "How do I migrate a Room datab" reads like a bug, whereas the
         * ellipsis reads like a summary.
         */
        fun fallbackTitle(messages: List<ChatMessage>): String? {
            val opening = messages.firstOrNull { it.role == Role.USER } ?: return null
            val text = WHITESPACE.replace(opening.content.trim(), " ")
            if (text.isEmpty()) return null
            if (text.length <= MAX_TITLE_CHARS) return text

            val cut = text.take(MAX_TITLE_CHARS - 1)
            val boundary = cut.lastIndexOf(' ')
            val head = if (boundary > MAX_TITLE_CHARS / 3) cut.take(boundary) else cut
            return head.trimEnd(' ', ',', ';', ':', '-') + "…"
        }
    }
}
