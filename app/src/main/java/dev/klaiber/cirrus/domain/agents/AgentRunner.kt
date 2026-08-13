package dev.klaiber.cirrus.domain.agents

import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.ChatEngine
import dev.klaiber.cirrus.domain.TurnEvent
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRunStatus
import dev.klaiber.cirrus.domain.model.GenerationStats
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.ToolInvocation
import dev.klaiber.cirrus.domain.notify.Notifier
import dev.klaiber.cirrus.domain.tools.SendNotificationTool
import dev.klaiber.cirrus.domain.userMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one agent, start to finish, with nobody watching.
 *
 * The result is written into an ordinary conversation. That is the design decision that makes the
 * whole feature cheap: an agent run is a thread you can open, read, scroll, branch from and reply
 * to, so none of the transcript UI, export, search or sharing has to know agents exist.
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

    /**
     * Runs [agentId] and returns the conversation it wrote into, or null if it could not start.
     *
     * Failures are recorded on the agent and, if it notifies, surfaced as a notification: an agent
     * that quietly stopped working three weeks ago is worse than one that never ran.
     */
    suspend fun run(agentId: String): String? {
        val agent = agents.byId(agentId) ?: return null
        val appSettings = settings.current.value
        val model = agent.model?.takeIf { it.isNotBlank() }
            ?: appSettings.defaultModel.takeIf { it.isNotBlank() }
            ?: models.models.value.firstOrNull()?.name

        if (model.isNullOrBlank()) {
            agents.recordRun(agent.id, AgentRunStatus.FAILED, NO_MODEL, null)
            return null
        }

        agents.recordRun(agent.id, AgentRunStatus.RUNNING, null, null)

        val conversation = conversations.createConversation(
            model = model,
            systemPrompt = systemPrompt(agent),
            params = appSettings.defaultParams,
            toolsEnabled = agent.toolsEnabled,
            title = agent.name,
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
        var failure: String? = null

        try {
            engine.respond(
                conversation = conversation,
                history = conversations.getMessages(conversation.id).filter { it.id != placeholder.id },
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
        } catch (error: Throwable) {
            failure = error.userMessage()
        } finally {
            notificationTool.conversationId = null
        }

        conversations.updateMessageContent(
            messageId = placeholder.id,
            content = content.toString(),
            thinking = thinking.toString().takeIf { it.isNotEmpty() },
            stats = stats,
            toolInvocations = tools,
            errorMessage = failure,
        )

        val summary = failure ?: plainSummary(content.toString())

        agents.recordRun(
            id = agent.id,
            status = if (failure == null) AgentRunStatus.SUCCEEDED else AgentRunStatus.FAILED,
            summary = summary,
            conversationId = conversation.id,
        )

        if (agent.notifyOnFinish) {
            notifier.notify(
                title = if (failure == null) agent.name else "${agent.name} failed",
                body = summary.ifBlank { "Finished with nothing to report." },
                channel = Notifier.Channel.AGENTS,
                conversationId = conversation.id,
            )
        }

        return conversation.id
    }

    /**
     * The first line of the answer, as prose.
     *
     * It goes on a notification and into a list row, and neither renders markdown — a summary
     * reading `**Three things:**` is the model's formatting leaking somewhere it does not belong.
     */
    private fun plainSummary(content: String): String = content
        .lineSequence()
        .map { line -> line.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim() }
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

    private companion object {
        const val NO_MODEL = "No model is configured, so this agent could not run."

        /** Bold and italic markers only; anything cleverer belongs in the markdown renderer. */
        val EMPHASIS = Regex("\\*{1,3}|_{2,3}")
    }
}
