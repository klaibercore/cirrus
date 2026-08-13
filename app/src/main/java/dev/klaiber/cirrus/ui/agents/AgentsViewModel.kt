package dev.klaiber.cirrus.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.agents.AgentScheduler
import dev.klaiber.cirrus.domain.model.Agent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class AgentsUiState(
    val agents: List<Agent> = emptyList(),
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val notificationsAllowed: Boolean = true,
)

@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val agents: AgentRepository,
    private val scheduler: AgentScheduler,
    modelRepository: ModelRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<AgentsUiState> = combine(
        agents.agents,
        modelRepository.models,
        settings.settings,
    ) { list, models, appSettings ->
        AgentsUiState(
            agents = list,
            models = models.map { it.name },
            defaultModel = appSettings.defaultModel,
            notificationsAllowed = appSettings.notificationToolEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentsUiState())

    fun create(
        name: String,
        prompt: String,
        model: String?,
        minuteOfDay: Int,
        days: Set<DayOfWeek>,
        toolsEnabled: Boolean,
        notifyOnFinish: Boolean,
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
            agents.delete(agent.id)
        }
    }
}
