package dev.klaiber.cirrus.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.domain.model.ConversationSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationsUiState(
    val query: String = "",
    val showArchived: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
    /** Shown beside the drawer's agents row, so a schedule is visible without going looking. */
    val agentCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repository: ConversationRepository,
    agents: AgentRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val showArchived = MutableStateFlow(false)

    private val results = combine(
        // Debounced so typing does not issue a LIKE scan per keystroke.
        query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
        showArchived,
    ) { text, archived -> text to archived }
        .flatMapLatest { (text, archived) ->
            if (text.isBlank()) {
                repository.observeSummaries(archived)
            } else {
                repository.searchConversations(text)
            }
        }

    val uiState: StateFlow<ConversationsUiState> = combine(
        query,
        showArchived,
        results,
        agents.agents,
    ) { text, archived, conversations, agentList ->
        ConversationsUiState(text, archived, conversations, agentList.count { it.enabled })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun toggleArchivedView() {
        showArchived.value = !showArchived.value
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { repository.rename(id, title) }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(id, pinned) }
    }

    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(id, archived) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteConversation(id) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L
    }
}
