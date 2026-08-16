package dev.klaiber.cirrus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.klaiber.cirrus.data.skills.SkillException
import dev.klaiber.cirrus.data.skills.SkillRegistry
import dev.klaiber.cirrus.di.ApplicationScope
import dev.klaiber.cirrus.domain.skills.Skill
import dev.klaiber.cirrus.domain.skills.SkillOrigin
import dev.klaiber.cirrus.domain.skills.parseSkillReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** What came of trying to install a source. */
sealed interface SkillInstallResult {
    data class Installed(
        val skills: List<Skill>,
        /** How many of them replaced a skill that was already there. */
        val updated: Int,
    ) : SkillInstallResult

    data class Failure(val message: String) : SkillInstallResult
}

/**
 * The skills the user has installed.
 *
 * DataStore rather than Room, which is the opposite of the usual call and is worth saying why. A
 * skill is a document of a few kilobytes, there are a handful of them, and nothing ever queries
 * across them — the tool layer wants the whole enabled set on every turn. That is a preferences
 * file, not a table. It also means adding skills needs no schema migration, and a schema file
 * cannot be regenerated except by a build.
 *
 * The size cap matters more here than it would in a database: preferences are read and written
 * whole, so a repository that shipped one enormous SKILL.md would make every unrelated setting
 * write slower. [SkillRegistry] caps the download and this caps what is kept.
 */
@Singleton
class SkillRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val registry: SkillRegistry,
    private val json: Json,
    @ApplicationScope scope: CoroutineScope,
) {

    val skills: Flow<List<Skill>> = dataStore.data.map { prefs ->
        prefs[Keys.SKILLS]?.let(::decode).orEmpty()
    }

    /**
     * A snapshot the tool layer can read without suspending.
     *
     * `ToolRegistry.definitions` and the standing brief are both built synchronously while a turn
     * is being assembled, which is the wrong moment to go and read a file.
     */
    val current: StateFlow<List<Skill>> = skills.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val enabled: List<Skill> get() = current.value.filter { it.enabled }

    fun find(name: String): Skill? = enabled.firstOrNull { it.name.equals(name.trim(), true) }

    /**
     * Fetches everything at [source] and installs it.
     *
     * Installing is idempotent by [Skill.id], which is the repository and path: running it again
     * after the library has been updated upstream replaces each skill in place rather than
     * accumulating copies of it. Whether a skill was switched off is preserved across that, since
     * an update is not a reason to undo the user's decision about it.
     */
    suspend fun install(source: String): SkillInstallResult {
        val reference = parseSkillReference(source)
            ?: return SkillInstallResult.Failure(
                "\"$source\" is not a GitHub repository. Try owner/repo — anthropics/skills, " +
                    "say — or paste the address of one.",
            )

        return try {
            val discovered = registry.discover(reference)
            val now = System.currentTimeMillis()
            val existing = current.value

            val installed = discovered.map { candidate ->
                val id = idFor(candidate.origin)
                Skill(
                    id = id,
                    name = candidate.document.name,
                    description = candidate.document.description,
                    instructions = candidate.document.body.take(MAX_INSTRUCTION_CHARS),
                    origin = candidate.origin,
                    // An update must not silently re-enable something switched off on purpose.
                    enabled = existing.firstOrNull { it.id == id }?.enabled ?: true,
                    installedAt = now,
                )
            }

            val updated = installed.count { new -> existing.any { it.id == new.id } }
            mutate { stored -> stored.filterNot { old -> installed.any { it.id == old.id } } + installed }
            SkillInstallResult.Installed(installed, updated)
        } catch (failure: SkillException) {
            SkillInstallResult.Failure(failure.message ?: "The skills could not be fetched.")
        } catch (failure: Exception) {
            SkillInstallResult.Failure(
                failure.message ?: "The skills could not be fetched. Check the connection.",
            )
        }
    }

    /** Re-reads a skill from where it came from, for a library that has moved on since. */
    suspend fun update(skillId: String): SkillInstallResult {
        val skill = current.value.firstOrNull { it.id == skillId }
            ?: return SkillInstallResult.Failure("That skill is no longer installed.")
        return install("${skill.origin.repository}/${skill.origin.path}")
    }

    suspend fun remove(skillId: String) = mutate { it.filterNot { skill -> skill.id == skillId } }

    /** Removes every skill that came from one repository, which is how a library is uninstalled. */
    suspend fun removeLibrary(repository: String) = mutate { stored ->
        stored.filterNot { it.origin.repository == repository }
    }

    suspend fun setEnabled(skillId: String, enabled: Boolean) = mutate { stored ->
        stored.map { if (it.id == skillId) it.copy(enabled = enabled) else it }
    }

    private suspend fun mutate(block: (List<Skill>) -> List<Skill>) {
        dataStore.edit { prefs ->
            val existing = prefs[Keys.SKILLS]?.let(::decode).orEmpty()
            prefs[Keys.SKILLS] = encode(block(existing).sortedBy { it.name })
        }
    }

    private fun idFor(origin: SkillOrigin): String = "${origin.repository}/${origin.path}"

    private fun encode(skills: List<Skill>): String = json.encodeToString(
        StoredSkills.serializer(),
        StoredSkills(
            skills.map { skill ->
                StoredSkill(
                    id = skill.id,
                    name = skill.name,
                    description = skill.description,
                    instructions = skill.instructions,
                    repository = skill.origin.repository,
                    path = skill.origin.path,
                    ref = skill.origin.ref,
                    enabled = skill.enabled,
                    installedAt = skill.installedAt,
                )
            },
        ),
    )

    private fun decode(raw: String): List<Skill> =
        runCatching { json.decodeFromString(StoredSkills.serializer(), raw) }
            .getOrNull()
            ?.skills
            ?.map { stored ->
                Skill(
                    id = stored.id,
                    name = stored.name,
                    description = stored.description,
                    instructions = stored.instructions,
                    origin = SkillOrigin(stored.repository, stored.path, stored.ref),
                    enabled = stored.enabled,
                    installedAt = stored.installedAt,
                )
            }
            .orEmpty()

    private object Keys {
        val SKILLS = stringPreferencesKey("installed_skills")
    }

    private companion object {
        /** Long enough for anything written to be read by a model with a real context window. */
        const val MAX_INSTRUCTION_CHARS = 32_000
    }

    @Serializable
    private data class StoredSkills(val skills: List<StoredSkill> = emptyList())

    @Serializable
    private data class StoredSkill(
        val id: String,
        val name: String,
        val description: String = "",
        val instructions: String = "",
        val repository: String = "",
        val path: String = "",
        val ref: String = "main",
        val enabled: Boolean = true,
        val installedAt: Long = 0L,
    )
}

/** The libraries behind the installed skills, for a screen that groups them by where they came from. */
fun List<Skill>.byLibrary(): Map<String, List<Skill>> =
    groupBy { it.origin.repository }.toSortedMap()
