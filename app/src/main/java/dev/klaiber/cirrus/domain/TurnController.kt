package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.di.ApplicationScope
import dev.klaiber.cirrus.domain.model.GenerationStats
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.ToolInvocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every assistant turn in flight, for as long as it takes.
 *
 * This deliberately does not live on the chat screen. A turn used to run in the ViewModel's
 * scope, which dies when its back-stack entry does: switching threads, or Android reclaiming the
 * screen while the app sat in the background, took the answer with it — usually somewhere in the
 * middle, and always without saying so. Here a turn belongs to the process, so leaving the
 * screen only stops watching it.
 *
 * Keeping the process itself alive and unfrozen while a turn runs is the foreground service's
 * job; it follows [turns] and stops itself when this goes idle.
 */
@Singleton
class TurnController @Inject constructor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val engine: ChatEngine,
    private val titler: ConversationTitler,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** An in-flight assistant turn, held in memory so the UI updates per token without DB writes. */
    data class LiveTurn(
        val conversationId: String,
        val messageId: String,
        val content: String = "",
        val thinking: String = "",
        val tools: List<ToolInvocation> = emptyList(),
        val requestJson: String? = null,
        val stats: GenerationStats? = null,
    )

    private val _turns = MutableStateFlow<Map<String, LiveTurn>>(emptyMap())

    /** Live turns by conversation id. A conversation is generating exactly while it has one. */
    val turns: StateFlow<Map<String, LiveTurn>> = _turns.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The last failure per conversation, kept until dismissed.
     *
     * A turn can now fail while nothing is on screen to hear about it, so the banner has to
     * survive until the user comes back to that thread rather than being fired into the void.
     */
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Starts an assistant turn for [conversationId], replacing any turn already running on it.
     *
     * Returns immediately: the work belongs to the application scope, not to the caller's.
     */
    @Synchronized
    fun start(conversationId: String) {
        val previous = jobs.remove(conversationId)
        val job = scope.launch {
            previous?.cancelAndJoinQuietly()
            runTurn(conversationId)
        }
        jobs[conversationId] = job
        // Only clear the slot if it is still ours; a restart may have claimed it already.
        job.invokeOnCompletion { jobs.remove(conversationId, job) }
    }

    /** Stops the turn on [conversationId], keeping whatever it streamed so far. */
    fun stop(conversationId: String) {
        jobs[conversationId]?.cancel()
    }

    /** Stops every running turn — what the notification's Stop action does. */
    fun stopAll() {
        jobs.values.forEach { it.cancel() }
    }

    /** Stops and waits, for callers that are about to rewrite the messages the turn is using. */
    suspend fun stopAndJoin(conversationId: String) {
        jobs[conversationId]?.cancelAndJoinQuietly()
    }

    fun clearError(conversationId: String) {
        _errors.update { it - conversationId }
    }

    private suspend fun runTurn(conversationId: String) {
        val conversation = conversations.getConversation(conversationId) ?: return
        val history = conversations.getMessages(conversationId)
        val appSettings = settings.current.value

        val placeholder = conversations.appendMessage(
            conversationId = conversationId,
            role = Role.ASSISTANT,
            content = "",
            model = conversation.model,
        )
        _turns.update { it + (conversationId to LiveTurn(conversationId, placeholder.id)) }
        _errors.update { it - conversationId }
        var lastPersistAt = 0L

        try {
            engine.respond(conversation, history, appSettings).collect { event ->
                when (event) {
                    is TurnEvent.RequestPrepared -> update(conversationId) { turn ->
                        turn.copy(
                            requestJson = event.requestJson.takeIf { appSettings.developerMode },
                        )
                    }

                    is TurnEvent.ThinkingDelta -> {
                        update(conversationId) { it.copy(thinking = it.thinking + event.text) }
                        lastPersistAt = persistThrottled(conversationId, lastPersistAt)
                    }

                    is TurnEvent.ContentDelta -> {
                        update(conversationId) { it.copy(content = it.content + event.text) }
                        lastPersistAt = persistThrottled(conversationId, lastPersistAt)
                    }

                    is TurnEvent.ToolStarted -> update(conversationId) { turn ->
                        turn.copy(tools = turn.tools + event.invocation)
                    }

                    is TurnEvent.ToolFinished -> update(conversationId) { turn ->
                        turn.copy(
                            tools = turn.tools.map { existing ->
                                if (existing.id == event.invocation.id) event.invocation else existing
                            },
                        )
                    }

                    is TurnEvent.Finished -> update(conversationId) { it.copy(stats = event.stats) }
                }
            }
            finalize(conversationId, errorMessage = null)
            titler.onTurnFinished(conversationId)
        } catch (cancellation: CancellationException) {
            // Preserve whatever streamed before the user hit stop.
            withContext(NonCancellable) { finalize(conversationId, errorMessage = null) }
            // Titling runs on the application scope, so a stopped turn still gets named.
            titler.onTurnFinished(conversationId)
            throw cancellation
        } catch (error: Throwable) {
            finalize(conversationId, errorMessage = error.userMessage())
        }
    }

    private fun update(conversationId: String, transform: (LiveTurn) -> LiveTurn) {
        _turns.update { turns ->
            val current = turns[conversationId] ?: return@update turns
            turns + (conversationId to transform(current))
        }
    }

    /**
     * Writes the buffer to the database at most every [PERSIST_INTERVAL_MS].
     *
     * Per-token writes would hammer SQLite for no benefit; this bounds how much of a long
     * generation is lost if the process dies mid-stream.
     */
    private suspend fun persistThrottled(conversationId: String, lastPersistAt: Long): Long {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < PERSIST_INTERVAL_MS) return lastPersistAt
        val turn = _turns.value[conversationId] ?: return lastPersistAt
        conversations.updateMessageContent(
            messageId = turn.messageId,
            content = turn.content,
            thinking = turn.thinking.takeIf { it.isNotEmpty() },
            stats = null,
            toolInvocations = turn.tools,
            errorMessage = null,
        )
        return now
    }

    private suspend fun finalize(conversationId: String, errorMessage: String?) {
        val turn = _turns.value[conversationId] ?: return
        conversations.updateMessageContent(
            messageId = turn.messageId,
            content = turn.content,
            thinking = turn.thinking.takeIf { it.isNotEmpty() },
            stats = turn.stats,
            toolInvocations = turn.tools,
            errorMessage = errorMessage,
        )
        _turns.update { it - conversationId }
        if (errorMessage != null) {
            _errors.update { it + (conversationId to errorMessage) }
        }
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        runCatching { join() }
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 600L
    }
}
