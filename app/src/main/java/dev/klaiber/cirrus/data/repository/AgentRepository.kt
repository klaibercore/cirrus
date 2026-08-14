package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.local.dao.AgentDao
import dev.klaiber.cirrus.data.local.dao.AgentRunDao
import dev.klaiber.cirrus.data.local.entity.AgentEntity
import dev.klaiber.cirrus.data.local.entity.AgentRunEntity
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRun
import dev.klaiber.cirrus.domain.model.AgentRunStatus
import dev.klaiber.cirrus.domain.model.AgentRunTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val dao: AgentDao,
    private val runDao: AgentRunDao,
    private val conversations: ConversationRepository,
) {

    val agents: Flow<List<Agent>> = dao.observeAll().map { rows -> rows.map(::toDomain) }

    fun runs(agentId: String, limit: Int = RUN_HISTORY): Flow<List<AgentRun>> =
        runDao.observeForAgent(agentId, limit).map { rows -> rows.map(::toDomain) }

    /** The most recent runs across every agent — the activity feed on the agents screen. */
    fun recentRuns(limit: Int = RECENT_RUNS): Flow<List<AgentRun>> =
        runDao.observeRecent(limit).map { rows -> rows.map(::toDomain) }

    suspend fun enabled(): List<Agent> = dao.enabled().map(::toDomain)

    suspend fun all(): List<Agent> = dao.all().map(::toDomain)

    suspend fun byId(id: String): Agent? = dao.byId(id)?.let(::toDomain)

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
        dao.upsert(toEntity(agent))
        return agent
    }

    suspend fun update(agent: Agent) = dao.upsert(toEntity(agent))

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val agent = dao.byId(id) ?: return
        dao.upsert(agent.copy(enabled = enabled))
    }

    suspend fun recordRun(
        id: String,
        status: AgentRunStatus,
        summary: String?,
        conversationId: String?,
    ) = dao.recordRun(
        id = id,
        at = System.currentTimeMillis(),
        status = status.name,
        summary = summary?.take(SUMMARY_CHARS),
        conversationId = conversationId,
    )

    /** Opens a run row and returns its id, so the outcome can be written back against it. */
    suspend fun startRun(agentId: String, trigger: AgentRunTrigger): String {
        val id = UUID.randomUUID().toString()
        runDao.upsert(
            AgentRunEntity(
                id = id,
                agentId = agentId,
                conversationId = null,
                startedAt = System.currentTimeMillis(),
                finishedAt = null,
                status = AgentRunStatus.RUNNING.name,
                trigger = trigger.name,
                summary = null,
                errorMessage = null,
                toolCalls = 0,
                tokens = null,
            ),
        )
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
    ) {
        val existing = runDao.byId(runId) ?: return
        runDao.upsert(
            existing.copy(
                conversationId = conversationId,
                finishedAt = System.currentTimeMillis(),
                status = status.name,
                summary = summary?.take(SUMMARY_CHARS),
                errorMessage = errorMessage?.take(SUMMARY_CHARS),
                toolCalls = toolCalls,
                tokens = tokens,
            ),
        )
    }

    /**
     * Closes out runs that were killed rather than finished.
     *
     * A run only ever ends by writing `finishedAt`, so anything still open when the process starts
     * again was interrupted — by a reboot, by the OS reclaiming the app, or by the work manager's
     * own deadline. Left alone, the agents screen shows a spinner for a run that stopped days ago
     * and the agent itself reads as permanently "running".
     */
    suspend fun failInterruptedRuns(before: Long): Int {
        val stale = runDao.unfinishedBefore(before)
        stale.forEach { run ->
            runDao.finish(
                id = run.id,
                status = AgentRunStatus.FAILED.name,
                errorMessage = INTERRUPTED,
                finishedAt = System.currentTimeMillis(),
            )
            val agent = dao.byId(run.agentId) ?: return@forEach
            if (agent.lastStatus == AgentRunStatus.RUNNING.name) {
                dao.recordRun(
                    id = agent.id,
                    at = run.startedAt,
                    status = AgentRunStatus.FAILED.name,
                    summary = INTERRUPTED,
                    conversationId = run.conversationId,
                )
            }
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
        val stale = runDao.conversationsBeyond(agentId, limit)
        if (stale.isNotEmpty()) {
            val stillOwned = conversations.conversationsForAgent(agentId)
                .filter { it.id in stale }
                .map { it.id }
            conversations.deleteConversations(stillOwned)
        }
        // Run rows outlive their threads, but not forever: a few times the thread limit is enough
        // to still show a pattern of failures after the text has been reclaimed.
        runDao.trim(agentId, limit * RUN_ROWS_PER_THREAD)
    }

    /** Deletes the agent, its run history, and every thread it wrote that nobody has replied to. */
    suspend fun delete(id: String) {
        val owned = conversations.conversationsForAgent(id).map { it.id }
        conversations.deleteConversations(owned)
        runDao.deleteForAgent(id)
        dao.delete(id)
    }

    private fun toDomain(entity: AgentEntity) = Agent(
        id = entity.id,
        name = entity.name,
        prompt = entity.prompt,
        model = entity.model,
        minuteOfDay = entity.minuteOfDay,
        days = Agent.daysFromMask(entity.daysMask),
        enabled = entity.enabled,
        toolsEnabled = entity.toolsEnabled,
        notifyOnFinish = entity.notifyOnFinish,
        createdAt = entity.createdAt,
        lastRunAt = entity.lastRunAt,
        lastStatus = entity.lastStatus?.let { name ->
            runCatching { AgentRunStatus.valueOf(name) }.getOrNull()
        },
        lastSummary = entity.lastSummary,
        lastConversationId = entity.lastConversationId,
        keepRuns = entity.keepRuns,
    )

    private fun toEntity(agent: Agent) = AgentEntity(
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
        keepRuns = agent.keepRuns.coerceIn(MIN_KEEP, MAX_KEEP),
    )

    private fun toDomain(entity: AgentRunEntity) = AgentRun(
        id = entity.id,
        agentId = entity.agentId,
        conversationId = entity.conversationId,
        startedAt = entity.startedAt,
        finishedAt = entity.finishedAt,
        status = runCatching { AgentRunStatus.valueOf(entity.status) }
            .getOrDefault(AgentRunStatus.FAILED),
        trigger = runCatching { AgentRunTrigger.valueOf(entity.trigger) }
            .getOrDefault(AgentRunTrigger.SCHEDULED),
        summary = entity.summary,
        errorMessage = entity.errorMessage,
        toolCalls = entity.toolCalls,
        tokens = entity.tokens,
    )

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
