package dev.klaiber.cirrus.domain.skills

/**
 * A skill: a page of instructions the model can pull in when it turns out to be relevant.
 *
 * The format is the one the agent-skills ecosystem settled on — a `SKILL.md` with `name` and
 * `description` in YAML frontmatter and the instructions underneath — which is what `npx skills`
 * installs and what Claude, Codex, Cursor and the rest read. Cirrus reads the same files from the
 * same public repositories on purpose: a skill written for one agent works here without being
 * ported, and a skill installed here is the same file the user has on their laptop.
 *
 * The split between [description] and [instructions] is the whole economy of the feature.
 * Descriptions are cheap and always in front of the model, in the standing brief; instructions run
 * to thousands of words and are fetched only when the model asks for them by name. Sending every
 * installed skill's body on every turn would cost more context than the skills are worth, and
 * sending none of it would mean the model never learns they exist.
 */
data class Skill(
    /** `owner/repo/path`. Stable, so re-installing a library updates its skills in place. */
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val origin: SkillOrigin,
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
) {
    /** The one line the standing brief spends on this skill. */
    fun brief(maxDescription: Int = BRIEF_DESCRIPTION_CHARS): String {
        val summary = description.replace(NEWLINES, " ").trim()
        val trimmed = if (summary.length <= maxDescription) {
            summary
        } else {
            summary.take(maxDescription).substringBeforeLast(' ') + "…"
        }
        return "$name — $trimmed"
    }

    private companion object {
        const val BRIEF_DESCRIPTION_CHARS = 220
        val NEWLINES = Regex("\\s*\\n\\s*")
    }
}

/** Where a skill came from, so it can be re-read, credited, and found again on the web. */
data class SkillOrigin(
    /** `owner/repo`. */
    val repository: String,
    /** Path to the SKILL.md within that repository. */
    val path: String,
    /** The branch or tag it was read from. */
    val ref: String,
) {
    /** The skill's own directory name, which is how the ecosystem refers to a skill. */
    val directory: String
        get() = path.removeSuffix("/SKILL.md").substringAfterLast('/').ifBlank { repository }

    val webUrl: String get() = "https://github.com/$repository/blob/$ref/$path"
}
