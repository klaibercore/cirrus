package dev.klaiber.cirrus.domain.agents

import dev.klaiber.cirrus.data.remote.OllamaException
import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.ChatEngine
import dev.klaiber.cirrus.domain.TurnEvent
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRunStatus
import dev.klaiber.cirrus.domain.model.AgentRunTrigger
import dev.klaiber.cirrus.domain.model.GenerationStats
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.ToolInvocation
import dev.klaiber.cirrus.domain.notify.Notifier
import dev.klaiber.cirrus.domain.tools.SendNotificationTool
import dev.klaiber.cirrus.domain.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one agent, start to finish, with nobody watching.
 *
 * The result is written into an ordinary conversation. That is the design decision that makes the
 * whole feature cheap: an agent run is a thread you can open, read, scroll, branch from and reply
 * to, so none of the transcript UI, export, search or sharing has to know agents exist. The thread
 * is stamped with the agent that wrote it, which is what keeps it out of the drawer until somebody
 * replies to it.
 *
 * Unlike a chat turn there is no live buffer — nothing is on screen to stream to — so the answer
 * is accumulated and written once at the end.
 */
@Singleton
class AgentRunner @Inject constructor(
    private val agents: AgentRepository,
    private val conversations: ConversationRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val memories: MemoryRepository,
    private val engine: ChatEngine,
    private val notifier: Notifier,
    private val notificationTool: SendNotificationTool,
) {

    /** What the caller has to decide next: give up, or come back and try again. */
    sealed interface Outcome {
        data class Finished(val conversationId: String) : Outcome

        /** Failed for a reason that will still be true in a minute — a bad key, a missing model. */
        data class Failed(val reason: String) : Outcome

        /** Failed for a reason that might not be — a dropped socket, a rate limit, a timeout. */
        data class Retryable(val reason: String) : Outcome
    }

    /**
     * Only one agent runs at a time.
     *
     * Two reasons, and the second is a bug this fixes rather than a preference. Two generations at
     * once on a phone is a poor trade at the best of times; and [SendNotificationTool] is a
     * singleton carrying the conversation a notification should open, so two overlapping runs used
     * to hand each other's threads to each other's notifications. Agents fire on the minute, and
     * "08:00 on weekdays" is the single most likely time for anyone to have scheduled two.
     */
    private val runLock = Mutex()

    suspend fun run(
        agentId: String,
        trigger: AgentRunTrigger = AgentRunTrigger.SCHEDULED,
    ): Outcome = runLock.withLock { runExclusive(agentId, trigger) }

    private suspend fun runExclusive(agentId: String, trigger: AgentRunTrigger): Outcome {
        val agent = agents.byId(agentId)
            ?: return Outcome.Failed("This agent no longer exists.")
        val appSettings = settings.current.value
        val model = agent.model?.takeIf { it.isNotBlank() }
            ?: appSettings.defaultModel.takeIf { it.isNotBlank() }
            ?: models.models.value.firstOrNull()?.name

        val runId = agents.startRun(agent.id, trigger)

        if (model.isNullOrBlank()) {
            return fail(agent, runId, NO_MODEL, conversationId = null, retryable = false)
        }

        agents.recordRun(agent.id, AgentRunStatus.RUNNING, null, null)

        val conversation = conversations.createConversation(
            model = model,
            systemPrompt = systemPrompt(agent),
            params = appSettings.defaultParams,
            toolsEnabled = agent.toolsEnabled,
            title = agent.name,
            agentId = agent.id,
        )
        conversations.appendMessage(
            conversationId = conversation.id,
            role = Role.USER,
            content = agent.prompt,
        )
        val placeholder = conversations.appendMessage(
            conversationId = conversation.id,
            role = Role.ASSISTANT,
            content = "",
            model = model,
        )

        // A notification the agent sends itself should open this thread, not the last one read.
        notificationTool.conversationId = conversation.id

        val content = StringBuilder()
        val thinking = StringBuilder()
        val tools = mutableListOf<ToolInvocation>()
        var stats = GenerationStats()
        var failure: Throwable? = null

        try {
            // A stream that stalls does not fail — it simply never delivers another byte, and the
            // socket read blocks until the work manager kills the whole worker at its own deadline.
            // That leaves the run marked as still going and, worse, skips the re-booking that would
            // have scheduled tomorrow's. Ending it ourselves, inside that deadline, is what keeps
            // one bad night from silently retiring the agent.
            withTimeout(RUN_TIMEOUT_MS) {
                engine.respond(
                    conversation = conversation,
                    history = conversations.getMessages(conversation.id)
                        .filter { it.id != placeholder.id },
                    settings = appSettings,
                    memoryBrief = memoryBrief(appSettings.memoryEnabled),
                ).collect { event ->
                    when (event) {
                        is TurnEvent.ContentDelta -> content.append(event.text)
                        is TurnEvent.ThinkingDelta -> thinking.append(event.text)
                        is TurnEvent.ToolStarted -> tools += event.invocation
                        is TurnEvent.ToolFinished -> {
                            val at = tools.indexOfFirst { it.id == event.invocation.id }
                            if (at >= 0) tools[at] = event.invocation else tools += event.invocation
                        }
                        is TurnEvent.Finished -> stats = event.stats
                        is TurnEvent.RequestPrepared -> Unit
                    }
                }
            }
        } catch (error: Throwable) {
            // A CancellationException that is not our own timeout means the worker is being
            // stopped, and swallowing it would record a killed run as a finished one — which is
            // exactly how an agent comes to look like it answered when it did not. Everything
            // else, transport failure or bug, is the agent's failure and belongs in its history.
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            failure = error
        } finally {
            notificationTool.conversationId = null
        }

        val message = failure?.let { it.asRunMessage() }

        conversations.updateMessageContent(
            messageId = placeholder.id,
            content = content.toString(),
            thinking = thinking.toString().takeIf { it.isNotEmpty() },
            stats = stats,
            toolInvocations = tools,
            errorMessage = message,
        )

        if (failure != null) {
            return fail(
                agent = agent,
                runId = runId,
                reason = message.orEmpty(),
                conversationId = conversation.id,
                retryable = failure.isRetryable(),
                toolCalls = tools.size,
                tokens = stats.evalCount,
            )
        }

        val summary = plainSummary(content.toString())

        agents.recordRun(
            id = agent.id,
            status = AgentRunStatus.SUCCEEDED,
            summary = summary,
            conversationId = conversation.id,
        )
        agents.finishRun(
            runId = runId,
            status = AgentRunStatus.SUCCEEDED,
            conversationId = conversation.id,
            summary = summary,
            errorMessage = null,
            toolCalls = tools.size,
            tokens = stats.evalCount,
        )
        agents.pruneRuns(agent.id, agent.keepRuns)

        if (agent.notifyOnFinish) {
            notifier.notify(
                title = agent.name,
                body = summary.ifBlank { "Finished with nothing to report." },
                channel = Notifier.Channel.AGENTS,
                conversationId = conversation.id,
            )
        }

        return Outcome.Finished(conversation.id)
    }

    /**
     * Records a failure everywhere it has to be visible, and says so out loud.
     *
     * An agent that quietly stopped working three weeks ago is worse than one that never ran, so a
     * failure notifies on the same terms a success does — including the retryable ones, because a
     * retry that also fails is exactly the case where nobody is watching.
     */
    private suspend fun fail(
        agent: Agent,
        runId: String,
        reason: String,
        conversationId: String?,
        retryable: Boolean,
        toolCalls: Int = 0,
        tokens: Int? = null,
    ): Outcome {
        agents.recordRun(agent.id, AgentRunStatus.FAILED, reason, conversationId)
        agents.finishRun(
            runId = runId,
            status = AgentRunStatus.FAILED,
            conversationId = conversationId,
            summary = null,
            errorMessage = reason,
            toolCalls = toolCalls,
            tokens = tokens,
        )
        // Retention runs on failures too. A half-written thread is still a thread, and an agent
        // that has been failing all week is precisely the one that would otherwise pile them up.
        if (conversationId != null) agents.pruneRuns(agent.id, agent.keepRuns)
        if (agent.notifyOnFinish && !retryable) {
            notifier.notify(
                title = "${agent.name} failed",
                body = reason.ifBlank { "The run did not finish." },
                channel = Notifier.Channel.AGENTS,
                conversationId = conversationId,
            )
        }
        return if (retryable) Outcome.Retryable(reason) else Outcome.Failed(reason)
    }

    private fun Throwable.asRunMessage(): String = when (this) {
        is TimeoutCancellationException -> TIMED_OUT
        else -> userMessage()
    }

    /**
     * Whether coming back in a few minutes might get a different answer.
     *
     * A rejected key or a missing model will be just as rejected and just as missing on the next
     * attempt; burning three generations to find that out costs tokens and tells nobody anything.
     */
    private fun Throwable.isRetryable(): Boolean = when (this) {
        is TimeoutCancellationException -> true
        is OllamaException.Network, is OllamaException.Truncated -> true
        is OllamaException.RateLimited -> true
        is OllamaException.ServerError -> code >= 500
        else -> false
    }

    /**
     * The first line of the answer, as prose.
     *
     * It goes on a notification and into a list row, and neither renders markdown — a summary
     * reading `**Three things:**` is the model's formatting leaking somewhere it does not belong.
     */
    private fun plainSummary(content: String): String = content
        .lineSequence()
        .map { line -> line.trim().trimStart('#').trim() }
        .map { line -> line.removePrefix("- ").removePrefix("* ").trim() }
        .map { line -> line.replace(EMPHASIS, "").replace('`', ' ').trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

    private suspend fun memoryBrief(enabled: Boolean): String? {
        if (!enabled) return null
        val pinned = memories.pinned()
        if (pinned.isEmpty()) return null
        return buildString {
            append("What you already know about this user:\n")
            pinned.forEach { append("- ").append(it.content).append('\n') }
        }
    }

    /**
     * Tells the agent where it is.
     *
     * Without this a scheduled run answers as though someone had just typed the prompt, and asks
     * clarifying questions nobody will read for eight hours.
     */
    private fun systemPrompt(agent: Agent): String = buildString {
        append("You are running unattended as a scheduled agent named \"")
        append(agent.name)
        append("\". Nobody is at the screen and there is no way to ask a follow-up question, so ")
        append("make reasonable assumptions and say what you assumed. ")
        append("Lead with the answer in the first line — that line is what appears on the lock ")
        append("screen. Keep it short unless the task genuinely needs length. ")
        if (agent.notifyOnFinish) {
            append("A notification will be sent automatically when you finish, so do not send one ")
            append("yourself unless there is something urgent that the summary line would miss.")
        } else {
            append("No notification is sent for this agent, so use send_notification if the user ")
            append("needs to know now rather than the next time they open the app.")
        }
    }

    companion object {
        const val NO_MODEL = "No model is configured, so this agent could not run."
        const val TIMED_OUT = "The run took too long and was stopped."

        /**
         * Comfortably inside the work manager's own ten-minute execution limit.
         *
         * Being stopped by us leaves a recorded failure and a re-booked next run; being stopped by
         * the platform leaves neither.
         */
        const val RUN_TIMEOUT_MS = 6L * 60 * 1000

        /** Bold and italic markers only; anything cleverer belongs in the markdown renderer. */
        private val EMPHASIS = Regex("\\*{1,3}|_{2,3}")
    }
}
