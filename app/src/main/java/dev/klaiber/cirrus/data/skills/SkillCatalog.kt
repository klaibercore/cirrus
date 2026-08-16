package dev.klaiber.cirrus.data.skills

/**
 * A skill library somebody might plausibly want, offered as a starting point.
 *
 * The only knowledge Cirrus can usefully hold is the repository name. What is in it, whether it
 * still exists, and whether any of it is any good are all questions only the repository can answer
 * — so every entry goes through exactly the same fetch as a hand-typed one, and shows you what it
 * found before installing anything.
 */
data class SkillLibrary(
    val label: String,
    /** `owner/repo`, in the form `npx skills add` takes. */
    val repository: String,
    val summary: String,
)

/**
 * Well-known skill libraries.
 *
 * Deliberately short, for the reason the MCP catalogue is short: a long directory goes stale in
 * silence and implies an endorsement Cirrus is in no position to make. These are the sets most
 * people mean when they say "install some skills", and the field above them takes anything.
 */
val SKILL_CATALOG: List<SkillLibrary> = listOf(
    SkillLibrary(
        label = "Anthropic skills",
        repository = "anthropics/skills",
        summary = "The reference collection: documents, spreadsheets, slides, design and research.",
    ),
    SkillLibrary(
        label = "Vercel agent skills",
        repository = "vercel-labs/agent-skills",
        summary = "Web work — React and Next.js practice, design review, and writing guidelines.",
    ),
    SkillLibrary(
        label = "The skills CLI",
        repository = "vercel-labs/skills",
        summary = "find-skills, which teaches a model to go looking for more of these itself.",
    ),
)
