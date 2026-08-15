package dev.klaiber.cirrus.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.SuggestionGenerator
import dev.klaiber.cirrus.domain.agents.AgentScheduler
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRun
import dev.klaiber.cirrus.domain.model.AgentTemplate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class AgentsUiState(
    val agents: List<Agent> = emptyList(),
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val notificationsAllowed: Boolean = true,
    /** Templates worth offering — the GitHub one is hidden until there is a token to use. */
    val templates: List<AgentTemplate> = AgentTemplate.All,
    /** Next scheduled fire time per agent id, so a card can say when rather than how often. */
    val nextRuns: Map<String, Long> = emptyMap(),
    /** Ids currently mid-run, from the run log rather than from a local guess. */
    val running: Set<String> = emptySet(),
) {
    fun nextRunFor(agent: Agent): Long? = nextRuns[agent.id]
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val agents: AgentRepository,
    private val scheduler: AgentScheduler,
    private val suggestions: SuggestionGenerator,
    modelRepository: ModelRepository,
    settings: SettingsRepository,
) : ViewModel() {

    /** Which agent's history is open, if any. */
    private val historyFor = MutableStateFlow<String?>(null)

    init {
        // Asked for once, when the screen that shows them is opened. The static six remain in
        // place until an answer lands, so nothing waits on this.
        suggestions.ensureAgentIdeas()
    }

    val uiState: StateFlow<AgentsUiState> = combine(
        agents.agents,
        modelRepository.models,
        settings.settings,
        agents.recentRuns(),
        suggestions.agentIdeas,
    ) { list, models, appSettings, recent, ideas ->
        val hasGitHub = appSettings.gitHubToolsEnabled && appSettings.hasGitHubToken
        AgentsUiState(
            agents = list,
            models = models.map { it.name },
            defaultModel = appSettings.defaultModel,
            notificationsAllowed = appSettings.notificationToolEnabled,
            // The written-in-advance six are the floor: they are what the sheet opens with, and
            // what it keeps if the model cannot be reached. Ideas from the user's own model go in
            // front of them, because they were written knowing what this install can actually do.
            templates = ideas + AgentTemplate.All.filter { !it.needsGitHub || hasGitHub },
            // Computed here rather than stored: a next-run time written to the database would be
            // wrong the moment the clock crossed it, and right only until then.
            nextRuns = list.mapNotNull { agent ->
                AgentScheduler.nextRunAt(agent)?.let { agent.id to it }
            }.toMap(),
            running = recent.filter { it.isRunning }.map { it.agentId }.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentsUiState())

    /** The open agent's run log. Empty until a history sheet asks for one. */
    val history: StateFlow<List<AgentRun>> = historyFor
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else agents.runs(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openHistory(agentId: String?) {
        historyFor.value = agentId
    }

    fun create(
        name: String,
        prompt: String,
        model: String?,
        minuteOfDay: Int,
        days: Set<DayOfWeek>,
        toolsEnabled: Boolean,
        notifyOnFinish: Boolean,
        keepRuns: Int,
    ) {
        viewModelScope.launch {
            val agent = agents.create(
                name = name,
                prompt = prompt,
                model = model,
                minuteOfDay = minuteOfDay,
                days = days,
                toolsEnabled = toolsEnabled,
                notifyOnFinish = notifyOnFinish,
                keepRuns = keepRuns,
            )
            scheduler.schedule(agent)
        }
    }

    fun update(agent: Agent) {
        viewModelScope.launch {
            agents.update(agent)
            // Re-booking on every edit is what keeps the queue honest: changing the time on a
            // scheduled agent has to move the run, not add a second one.
            scheduler.schedule(agent)
            // Lowering the retention limit should take effect now, not after the next run.
            agents.pruneRuns(agent.id, agent.keepRuns)
        }
    }

    /** Copies an agent, off by default — an accidental second briefing every morning is noise. */
    fun duplicate(agent: Agent) {
        viewModelScope.launch {
            val copy = agents.create(
                name = "${agent.name} copy",
                prompt = agent.prompt,
                model = agent.model,
                minuteOfDay = agent.minuteOfDay,
                days = agent.days,
                toolsEnabled = agent.toolsEnabled,
                notifyOnFinish = agent.notifyOnFinish,
                keepRuns = agent.keepRuns,
            )
            agents.setEnabled(copy.id, false)
            scheduler.cancel(copy.id)
        }
    }

    fun setEnabled(agent: Agent, enabled: Boolean) {
        viewModelScope.launch {
            agents.setEnabled(agent.id, enabled)
            agents.byId(agent.id)?.let(scheduler::schedule)
        }
    }

    fun runNow(agent: Agent) = scheduler.runNow(agent.id)

    fun delete(agent: Agent) {
        viewModelScope.launch {
            scheduler.cancel(agent.id)
            // Deletes the agent's own threads with it. They are invisible everywhere else in the
            // app, so leaving them behind would be leaving rows nobody can ever find or remove.
            agents.delete(agent.id)
        }
    }
}
