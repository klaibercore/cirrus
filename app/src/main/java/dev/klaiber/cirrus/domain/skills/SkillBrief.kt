package dev.klaiber.cirrus.domain.skills

/**
 * The paragraph of the system message that makes skills exist for the model.
 *
 * A tool description is read when the model is choosing a tool; a skill has to be visible *before*
 * that, when it is deciding how to approach the question at all — "there is a skill for writing
 * these" is a fact about the task, not about a function. So the roster goes in the system message
 * and the bodies stay out of it.
 *
 * Pure and separate from the registry so it can be tested, because the cost of this line is paid on
 * every single turn and a bug in it is a bug in every conversation.
 */
fun skillsBrief(skills: List<Skill>, limit: Int = MAX_LISTED_SKILLS): String? {
    if (skills.isEmpty()) return null

    val listed = skills.take(limit)
    val hidden = skills.size - listed.size

    return buildString {
        append("Skills are installed. Each is a page of instructions for a particular kind of ")
        append("work; when one matches the task, call use_skill with its name and follow what it ")
        append("says before answering.\n")
        listed.forEach { skill -> append("- ").append(skill.brief()).append('\n') }
        if (hidden > 0) {
            append("- and ").append(hidden).append(" more — call list_skills to see them.")
        }
    }.trimEnd()
}

/** Beyond this the roster costs more context on every turn than the skills are worth. */
private const val MAX_LISTED_SKILLS = 24
