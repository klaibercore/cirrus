package dev.klaiber.cirrus.ui.conversations

import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.domain.model.ConversationSummary
import kotlinx.coroutines.CoroutineScope
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

data class ConversationsUiState(
    val query: String = "",
    val showArchived: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
)

/**
 * The drawer's state, as a plain class rather than a `ViewModel`.
 *
 * Android gives each conversation its own back-stack entry and therefore its own ViewModel, which
 * is what that class buys there: state that survives a screen going away. A desktop window has no
 * back stack, so the equivalent lifetime is the composition, and a class remembered in it is the
 * whole of what `ViewModel` was doing — minus a dependency and minus the temptation to run a turn
 * on a scope that dies with the screen.
 *
 * The scope is passed in for that reason: work that must outlive the screen gets the application
 * scope, and everything here gets the composition's.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ConversationsModel(
    private val repository: ConversationRepository,
    private val scope: CoroutineScope,
) {

    private val query = MutableStateFlow("")
    private val showArchived = MutableStateFlow(false)

    private val results = combine(
        // Debounced so typing does not re-filter the whole store per keystroke.
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
    ) { text, archived, conversations ->
        ConversationsUiState(text, archived, conversations)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun toggleArchivedView() {
        showArchived.value = !showArchived.value
    }

    fun rename(id: String, title: String) {
        scope.launch { repository.rename(id, title) }
    }

    fun setPinned(id: String, pinned: Boolean) {
        scope.launch { repository.setPinned(id, pinned) }
    }

    fun setArchived(id: String, archived: Boolean) {
        scope.launch { repository.setArchived(id, archived) }
    }

    fun delete(id: String) {
        scope.launch { repository.deleteConversation(id) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L
    }
}
