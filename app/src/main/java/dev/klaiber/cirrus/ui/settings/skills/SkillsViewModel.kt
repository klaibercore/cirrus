package dev.klaiber.cirrus.ui.settings.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.data.repository.SkillInstallResult
import dev.klaiber.cirrus.data.repository.SkillRepository
import dev.klaiber.cirrus.data.skills.SKILL_CATALOG
import dev.klaiber.cirrus.data.skills.SkillLibrary
import dev.klaiber.cirrus.domain.skills.Skill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One installed library, and the skills that came from it. */
data class SkillLibraryGroup(
    val repository: String,
    val skills: List<Skill>,
)

data class SkillsUiState(
    val skillsEnabled: Boolean = true,
    val libraries: List<SkillLibraryGroup> = emptyList(),
    /** Catalogue entries that are not installed yet — the rest would just be noise. */
    val suggestions: List<SkillLibrary> = emptyList(),
    val installing: String? = null,
    val notice: String? = null,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = libraries.isEmpty()
    val count: Int get() = libraries.sumOf { it.skills.size }
}

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skills: SkillRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private data class Transient(
        val installing: String? = null,
        val notice: String? = null,
        val error: String? = null,
    )

    private val transient = MutableStateFlow(Transient())

    /** What is typed into the source field. Held here so a rotation does not lose it. */
    private val _source = MutableStateFlow("")
    val source: StateFlow<String> = _source

    val uiState: StateFlow<SkillsUiState> = combine(
        skills.skills,
        settings.settings,
        transient,
    ) { installed, appSettings, state ->
        val libraries = installed
            .groupBy { it.origin.repository }
            .toSortedMap()
            .map { (repository, group) ->
                SkillLibraryGroup(repository, group.sortedBy { it.name })
            }

        SkillsUiState(
            skillsEnabled = appSettings.skillsEnabled,
            libraries = libraries,
            suggestions = SKILL_CATALOG.filterNot { entry ->
                libraries.any { it.repository.equals(entry.repository, ignoreCase = true) }
            },
            installing = state.installing,
            notice = state.notice,
            error = state.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkillsUiState())

    fun onSourceChange(value: String) {
        _source.value = value
    }

    fun installTyped() {
        val typed = _source.value.trim()
        if (typed.isEmpty()) return
        install(typed) { _source.value = "" }
    }

    fun install(source: String, onSuccess: () -> Unit = {}) {
        if (transient.value.installing != null) return
        transient.update { Transient(installing = source) }
        viewModelScope.launch {
            when (val result = skills.install(source)) {
                is SkillInstallResult.Installed -> {
                    onSuccess()
                    transient.value = Transient(notice = result.describe(source))
                }

                is SkillInstallResult.Failure -> transient.value = Transient(error = result.message)
            }
        }
    }

    fun update(skillId: String) {
        if (transient.value.installing != null) return
        transient.update { Transient(installing = skillId) }
        viewModelScope.launch {
            transient.value = when (val result = skills.update(skillId)) {
                is SkillInstallResult.Installed -> Transient(notice = "Re-read from GitHub.")
                is SkillInstallResult.Failure -> Transient(error = result.message)
            }
        }
    }

    fun setEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch { skills.setEnabled(skillId, enabled) }
    }

    fun remove(skillId: String) {
        viewModelScope.launch { skills.remove(skillId) }
    }

    fun removeLibrary(repository: String) {
        viewModelScope.launch { skills.removeLibrary(repository) }
    }

    fun setSkillsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setSkillsEnabled(enabled) }
    }

    fun dismissMessage() = transient.update { it.copy(notice = null, error = null) }

    /** "3 skills from anthropics/skills", or the honest version when they were already there. */
    private fun SkillInstallResult.Installed.describe(source: String): String {
        val added = skills.size - updated
        return when {
            added == 0 && updated > 0 -> "Updated $updated ${plural(updated)} from $source."
            updated == 0 -> "Installed ${skills.size} ${plural(skills.size)} from $source."
            else -> "Installed $added and updated $updated from $source."
        }
    }

    private fun plural(count: Int) = if (count == 1) "skill" else "skills"
}
