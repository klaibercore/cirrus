package dev.klaiber.cirrus.ui.memory

import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.memory.ConsolidationScheduler
import dev.klaiber.cirrus.domain.memory.MemoryRetriever
import dev.klaiber.cirrus.domain.model.Memory
import dev.klaiber.cirrus.domain.model.MemoryKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

data class MemoryUiState(
    val pinned: List<Memory> = emptyList(),
    val others: List<Memory> = emptyList(),
    val retired: List<Memory> = emptyList(),
    val query: String = "",
    val kindFilter: MemoryKind? = null,
    val memoryEnabled: Boolean = true,
    val consolidationEnabled: Boolean = true,
    val lastConsolidationAt: Long = 0L,
    val isConsolidating: Boolean = false,
) {
    val total: Int get() = pinned.size + others.size

    val isEmpty: Boolean get() = total == 0 && retired.isEmpty()
}

/**
 * State for the screen, as a plain class rather than a `ViewModel`.
 *
 * A desktop window has no back stack for one to survive, so the equivalent lifetime is the
 * composition and the scope is passed in — see `ConversationsModel` for the full argument.
 */
class MemoryModel(
    private val memories: MemoryRepository,
    private val settings: SettingsRepository,
    private val consolidation: ConsolidationScheduler,
    private val scope: CoroutineScope,
) {

    private val query = MutableStateFlow("")
    private val kindFilter = MutableStateFlow<MemoryKind?>(null)
    private val consolidating = MutableStateFlow(false)

    val uiState: StateFlow<MemoryUiState> = combine(
        memories.allMemories,
        settings.settings,
        query,
        kindFilter,
        consolidating,
    ) { all, appSettings, search, kind, isConsolidating ->
        val (retired, active) = all.partition { it.archived }
        // Ranked rather than filtered when there is a query: the same scoring the model gets, so
        // what you see here is what a recall would have returned.
        val matched = when {
            search.isBlank() -> active
            else -> MemoryRetriever.rank(active, search, limit = active.size)
        }.filter { kind == null || it.kind == kind }

        MemoryUiState(
            pinned = matched.filter { it.pinned },
            others = matched.filterNot { it.pinned },
            retired = retired.filter { search.isBlank() || it.content.contains(search, true) },
            query = search,
            kindFilter = kind,
            memoryEnabled = appSettings.memoryEnabled,
            consolidationEnabled = appSettings.memoryConsolidationEnabled,
            lastConsolidationAt = appSettings.lastConsolidationAt,
            isConsolidating = isConsolidating,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setKindFilter(kind: MemoryKind?) {
        kindFilter.value = kind
    }

    fun add(content: String, kind: MemoryKind, pinned: Boolean) {
        scope.launch { memories.remember(content, kind, pinned = pinned) }
    }

    fun save(memory: Memory, content: String, kind: MemoryKind, pinned: Boolean) {
        scope.launch {
            memories.update(memory.copy(content = content.trim(), kind = kind, pinned = pinned))
        }
    }

    fun togglePin(memory: Memory) {
        scope.launch { memories.setPinned(memory.id, !memory.pinned) }
    }

    fun archive(memory: Memory) {
        scope.launch { memories.archive(memory.id, archived = true) }
    }

    fun restore(memory: Memory) {
        scope.launch { memories.archive(memory.id, archived = false) }
    }

    fun delete(memory: Memory) {
        scope.launch { memories.delete(memory.id) }
    }

    fun forgetEverything() {
        scope.launch { memories.deleteAll() }
    }

    /**
     * Kicks off the nightly pass by hand.
     *
     * The flag is optimistic — the work runs in WorkManager, out of this ViewModel's reach — but
     * the store is observed, so the list updates itself the moment the pass writes anything.
     */
    fun consolidateNow() {
        consolidating.value = true
        consolidation.runNow()
        scope.launch {
            kotlinx.coroutines.delay(CONSOLIDATION_FEEDBACK_MS)
            consolidating.value = false
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch { settings.setMemoryEnabled(enabled) }
    }

    private companion object {
        const val CONSOLIDATION_FEEDBACK_MS = 2_500L
    }
}
