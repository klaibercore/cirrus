package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRun
import dev.klaiber.cirrus.domain.model.AgentRunStatus
import dev.klaiber.cirrus.domain.model.AgentRunTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agents, and every attempt each one has made.
 *
 * Two Room tables become one JSON document, because they are only ever read together and a run row
 * is meaningless without the agent it belongs to. The queries the DAOs expressed in SQL — enabled
 * agents, a run history bounded per agent, the recent feed across all of them — are the same
 * queries, expressed as list operations over a snapshot that is small by construction: an agent
 * keeps ten threads by default and thirty run rows.
 */
@Singleton
class AgentRepository @Inject constructor(
    private val store: JsonStore,
    private val conversations: ConversationRepository,
) {

    private val _state = MutableStateFlow(AgentStore())

    val agents: Flow<List<Agent>> = _state.map { it.agents.map(StoredAgent::toDomain) }

    /** Reads the agents and their run history off disk. Called once, at start-up. */
    suspend fun load() {
        _state.value = store.read(AgentStore.serializer()) { AgentStore() }
    }

    fun runs(agentId: String, limit: Int = RUN_HISTORY): Flow<List<AgentRun>> = _state.map { state ->
        state.runs
            .filter { it.agentId == agentId }
            .sortedByDescending { it.startedAt }
            .take(limit)
            .map(StoredRun::toDomain)
    }

    /** The most recent runs across every agent — the activity feed on the agents screen. */
    fun recentRuns(limit: Int = RECENT_RUNS): Flow<List<AgentRun>> = _state.map { state ->
        state.runs.sortedByDescending { it.startedAt }.take(limit).map(StoredRun::toDomain)
    }

    suspend fun enabled(): List<Agent> = all().filter { it.enabled }

    suspend fun all(): List<Agent> = _state.value.agents.map(StoredAgent::toDomain)

    suspend fun byId(id: String): Agent? =
        _state.value.agents.firstOrNull { it.id == id }?.toDomain()

    suspend fun create(
        name: String,
        prompt: String,
        model: String?,
        minuteOfDay: Int,
        days: Set<DayOfWeek>,
        toolsEnabled: Boolean,
        notifyOnFinish: Boolean,
        keepRuns: Int = Agent.DEFAULT_KEEP_RUNS,
    ): Agent {
        val agent = Agent(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Untitled agent" },
            prompt = prompt.trim(),
            model = model,
            minuteOfDay = minuteOfDay.coerceIn(0, MINUTES_IN_DAY - 1),
            days = days,
            enabled = true,
            toolsEnabled = toolsEnabled,
            notifyOnFinish = notifyOnFinish,
            createdAt = System.currentTimeMillis(),
            lastRunAt = null,
            lastStatus = null,
            lastSummary = null,
            lastConversationId = null,
            keepRuns = keepRuns.coerceIn(MIN_KEEP, MAX_KEEP),
        )
        upsert(agent)
        return agent
    }

    suspend fun update(agent: Agent) = upsert(agent)

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val agent = byId(id) ?: return
        upsert(agent.copy(enabled = enabled))
    }

    suspend fun recordRun(
        id: String,
        status: AgentRunStatus,
        summary: String?,
        conversationId: String?,
    ) = mutate { state ->
        state.copy(
            agents = state.agents.map { agent ->
                if (agent.id != id) {
                    agent
                } else {
                    agent.copy(
                        lastRunAt = System.currentTimeMillis(),
                        lastStatus = status.name,
                        lastSummary = summary?.take(SUMMARY_CHARS),
                        lastConversationId = conversationId ?: agent.lastConversationId,
                    )
                }
            },
        )
    }

    /** Opens a run row and returns its id, so the outcome can be written back against it. */
    suspend fun startRun(agentId: String, trigger: AgentRunTrigger): String {
        val id = UUID.randomUUID().toString()
        mutate { state ->
            state.copy(
                runs = state.runs + StoredRun(
                    id = id,
                    agentId = agentId,
                    conversationId = null,
                    startedAt = System.currentTimeMillis(),
                    finishedAt = null,
                    status = AgentRunStatus.RUNNING.name,
                    trigger = trigger.name,
                ),
            )
        }
        return id
    }

    suspend fun finishRun(
        runId: String,
        status: AgentRunStatus,
        conversationId: String?,
        summary: String?,
        errorMessage: String?,
        toolCalls: Int,
        tokens: Int?,
    ) = mutate { state ->
        state.copy(
            runs = state.runs.map { run ->
                if (run.id != runId) {
                    run
                } else {
                    run.copy(
                        conversationId = conversationId,
                        finishedAt = System.currentTimeMillis(),
                        status = status.name,
                        summary = summary?.take(SUMMARY_CHARS),
                        errorMessage = errorMessage?.take(SUMMARY_CHARS),
                        toolCalls = toolCalls,
                        tokens = tokens,
                    )
                }
            },
        )
    }

    /**
     * Closes out runs that were killed rather than finished.
     *
     * A run only ever ends by writing `finishedAt`, so anything still open when the process starts
     * again was interrupted — by a crash, by the machine sleeping through the run's own timeout, or
     * by the user quitting mid-generation. Left alone, the agents screen shows a spinner for a run
     * that stopped days ago and the agent itself reads as permanently "running".
     */
    suspend fun failInterruptedRuns(before: Long): Int {
        val stale = _state.value.runs.filter { it.finishedAt == null && it.startedAt < before }
        if (stale.isEmpty()) return 0
        val staleIds = stale.map { it.id }.toSet()
        val now = System.currentTimeMillis()

        mutate { state ->
            state.copy(
                runs = state.runs.map { run ->
                    if (run.id !in staleIds) {
                        run
                    } else {
                        run.copy(
                            finishedAt = now,
                            status = AgentRunStatus.FAILED.name,
                            errorMessage = INTERRUPTED,
                        )
                    }
                },
                agents = state.agents.map { agent ->
                    val theirs = stale.firstOrNull { it.agentId == agent.id }
                    if (theirs == null || agent.lastStatus != AgentRunStatus.RUNNING.name) {
                        agent
                    } else {
                        agent.copy(
                            lastRunAt = theirs.startedAt,
                            lastStatus = AgentRunStatus.FAILED.name,
                            lastSummary = INTERRUPTED,
                            lastConversationId = theirs.conversationId ?: agent.lastConversationId,
                        )
                    }
                },
            )
        }
        return stale.size
    }

    /**
     * Applies an agent's retention limit.
     *
     * Only threads still marked as this agent's runs are eligible: replying to a run detaches it,
     * and something you have joined in on is a conversation, not an artefact to be swept up.
     */
    suspend fun pruneRuns(agentId: String, keep: Int) {
        val limit = keep.coerceIn(MIN_KEEP, MAX_KEEP)

        val beyond = _state.value.runs
            .filter { it.agentId == agentId && it.conversationId != null }
            .sortedByDescending { it.startedAt }
            .drop(limit)
            .mapNotNull { it.conversationId }
            .toSet()

        if (beyond.isNotEmpty()) {
            val stillOwned = conversations.conversationsForAgent(agentId)
                .filter { it.id in beyond }
                .map { it.id }
            conversations.deleteConversations(stillOwned)
        }

        // Run rows outlive their threads, but not forever: a few times the thread limit is enough
        // to still show a pattern of failures after the text has been reclaimed.
        val keepRows = limit * RUN_ROWS_PER_THREAD
        mutate { state ->
            val kept = state.runs
                .filter { it.agentId == agentId }
                .sortedByDescending { it.startedAt }
                .take(keepRows)
                .map { it.id }
                .toSet()
            state.copy(runs = state.runs.filter { it.agentId != agentId || it.id in kept })
        }
    }

    /** Deletes the agent, its run history, and every thread it wrote that nobody has replied to. */
    suspend fun delete(id: String) {
        val owned = conversations.conversationsForAgent(id).map { it.id }
        conversations.deleteConversations(owned)
        mutate { state ->
            state.copy(
                agents = state.agents.filterNot { it.id == id },
                runs = state.runs.filterNot { it.agentId == id },
            )
        }
    }

    private suspend fun upsert(agent: Agent) = mutate { state ->
        val stored = StoredAgent.of(agent)
        val index = state.agents.indexOfFirst { it.id == agent.id }
        state.copy(
            agents = if (index >= 0) {
                state.agents.toMutableList().also { it[index] = stored }
            } else {
                state.agents + stored
            },
        )
    }

    private suspend fun mutate(transform: (AgentStore) -> AgentStore) {
        _state.value = transform(_state.value)
        store.write(AgentStore.serializer(), _state.value)
    }

    private companion object {
        const val MINUTES_IN_DAY = 24 * 60
        const val SUMMARY_CHARS = 240
        const val RUN_HISTORY = 30
        const val RECENT_RUNS = 20
        const val MIN_KEEP = 1
        const val MAX_KEEP = 100
        const val RUN_ROWS_PER_THREAD = 3
        const val INTERRUPTED = "The run was interrupted before it finished."
    }
}

/** The on-disk shape: the agents and their runs, in one document. */
@Serializable
private data class AgentStore(
    val agents: List<StoredAgent> = emptyList(),
    val runs: List<StoredRun> = emptyList(),
)

/**
 * An agent as stored.
 *
 * Days keep the bit mask the Room entity used and the enums keep their names, for the reason the
 * entity did it that way: a `DayOfWeek` set and a status are both things whose serialised form has
 * to survive a rename in the source, and `valueOf` failing here falls back rather than throwing
 * away the row.
 */
@Serializable
private data class StoredAgent(
    val id: String,
    val name: String,
    val prompt: String,
    val model: String? = null,
    val minuteOfDay: Int,
    val daysMask: Int,
    val enabled: Boolean = true,
    val toolsEnabled: Boolean = false,
    val notifyOnFinish: Boolean = true,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val lastStatus: String? = null,
    val lastSummary: String? = null,
    val lastConversationId: String? = null,
    val keepRuns: Int = Agent.DEFAULT_KEEP_RUNS,
) {
    fun toDomain() = Agent(
        id = id,
        name = name,
        prompt = prompt,
        model = model,
        minuteOfDay = minuteOfDay,
        days = Agent.daysFromMask(daysMask),
        enabled = enabled,
        toolsEnabled = toolsEnabled,
        notifyOnFinish = notifyOnFinish,
        createdAt = createdAt,
        lastRunAt = lastRunAt,
        lastStatus = lastStatus?.let { runCatching { AgentRunStatus.valueOf(it) }.getOrNull() },
        lastSummary = lastSummary,
        lastConversationId = lastConversationId,
        keepRuns = keepRuns,
    )

    companion object {
        fun of(agent: Agent) = StoredAgent(
            id = agent.id,
            name = agent.name,
            prompt = agent.prompt,
            model = agent.model,
            minuteOfDay = agent.minuteOfDay,
            daysMask = Agent.daysToMask(agent.days),
            enabled = agent.enabled,
            toolsEnabled = agent.toolsEnabled,
            notifyOnFinish = agent.notifyOnFinish,
            createdAt = agent.createdAt,
            lastRunAt = agent.lastRunAt,
            lastStatus = agent.lastStatus?.name,
            lastSummary = agent.lastSummary,
            lastConversationId = agent.lastConversationId,
            keepRuns = agent.keepRuns.coerceIn(1, 100),
        )
    }
}

@Serializable
private data class StoredRun(
    val id: String,
    val agentId: String,
    val conversationId: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: String,
    val trigger: String,
    val summary: String? = null,
    val errorMessage: String? = null,
    val toolCalls: Int = 0,
    val tokens: Int? = null,
) {
    fun toDomain() = AgentRun(
        id = id,
        agentId = agentId,
        conversationId = conversationId,
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = runCatching { AgentRunStatus.valueOf(status) }.getOrDefault(AgentRunStatus.FAILED),
        trigger = runCatching { AgentRunTrigger.valueOf(trigger) }
            .getOrDefault(AgentRunTrigger.SCHEDULED),
        summary = summary,
        errorMessage = errorMessage,
        toolCalls = toolCalls,
        tokens = tokens,
    )
}
