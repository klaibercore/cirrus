package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.local.dao.AgentDao
import dev.klaiber.cirrus.data.local.entity.AgentEntity
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val dao: AgentDao,
) {

    val agents: Flow<List<Agent>> = dao.observeAll().map { rows -> rows.map(::toDomain) }

    suspend fun enabled(): List<Agent> = dao.enabled().map(::toDomain)

    suspend fun byId(id: String): Agent? = dao.byId(id)?.let(::toDomain)

    suspend fun create(
        name: String,
        prompt: String,
        model: String?,
        minuteOfDay: Int,
        days: Set<java.time.DayOfWeek>,
        toolsEnabled: Boolean,
        notifyOnFinish: Boolean,
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

    suspend fun delete(id: String) = dao.delete(id)

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
    )

    private companion object {
        const val MINUTES_IN_DAY = 24 * 60
        const val SUMMARY_CHARS = 240
    }
}
