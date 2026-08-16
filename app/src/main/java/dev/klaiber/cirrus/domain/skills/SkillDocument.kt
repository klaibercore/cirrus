package dev.klaiber.cirrus.domain.skills

/**
 * A parsed `SKILL.md`: the two frontmatter fields that matter, and everything under them.
 *
 * [hidden] carries `metadata.internal: true`, which the spec uses for skills a library ships for
 * its own use — helpers another skill calls, rather than something a user would choose. Installing
 * those alongside the real ones would put lines in the standing brief that mean nothing to anyone.
 */
data class SkillDocument(
    val name: String,
    val description: String,
    val body: String,
    val hidden: Boolean = false,
)

/**
 * Reads a `SKILL.md`.
 *
 * Hand-written, and deliberately a *subset* of YAML rather than a YAML library. Frontmatter here is
 * four or five scalar fields; pulling in a parser to read them would add a dependency whose whole
 * job is the ninety per cent of the format that never appears in these files. What does appear is
 * handled: quoted and unquoted scalars, the folded and literal block scalars (`>` and `|`) that
 * long descriptions are written with, comments, and one level of nesting for `metadata`.
 *
 * Tolerant on purpose. These files come from strangers' repositories, and the reasonable response
 * to an oddity in one field is to keep the rest, not to refuse the skill. Only two things are fatal:
 * no frontmatter at all, and no name to call the skill by.
 *
 * [fallbackName] is the skill's directory name, which the ecosystem treats as authoritative anyway
 * — it is what `npx skills add` names the installed folder — so a file that forgot its `name:` is
 * still perfectly usable.
 */
fun parseSkillDocument(raw: String, fallbackName: String = ""): SkillDocument? {
    val text = raw.removePrefix("﻿").replace("\r\n", "\n")
    val lines = text.split('\n')

    val opening = lines.indexOfFirst { it.isNotBlank() }
    if (opening < 0 || lines[opening].trim() != FRONTMATTER_FENCE) return null
    val closing = (opening + 1 until lines.size).firstOrNull { lines[it].trim() == FRONTMATTER_FENCE }
        ?: return null

    val fields = parseFrontmatter(lines.subList(opening + 1, closing))
    val name = fields["name"]?.let(::normalizeName)?.takeIf { it.isNotBlank() }
        ?: normalizeName(fallbackName).takeIf { it.isNotBlank() }
        ?: return null

    return SkillDocument(
        name = name,
        description = fields["description"].orEmpty().trim(),
        body = lines.subList(closing + 1, lines.size).joinToString("\n").trim(),
        hidden = fields["metadata.internal"]?.trim()?.equals("true", ignoreCase = true) == true,
    )
}

/**
 * Flattens the frontmatter to `key` → value, with nested keys joined by a dot.
 *
 * Indentation is only tracked one level deep because that is all the format uses: `metadata:` with
 * a couple of flags under it. Anything deeper collapses into its parent's key and is then simply
 * never looked up, which is the right amount of effort to spend on a field nothing here reads.
 */
private fun parseFrontmatter(lines: List<String>): Map<String, String> {
    val fields = mutableMapOf<String, String>()
    var parent: String? = null
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        index++

        if (line.isBlank() || line.trimStart().startsWith("#")) continue

        val indented = line.first().isWhitespace()
        val trimmed = line.trim()
        val separator = trimmed.indexOf(':')
        if (separator <= 0) continue

        val key = trimmed.substring(0, separator).trim().trim('"', '\'')
        val rest = trimmed.substring(separator + 1).trim()
        val qualified = if (indented && parent != null) "$parent.$key" else key
        if (!indented) parent = key

        when {
            // A block scalar: the value is the indented lines that follow, and `>` joins them into
            // a paragraph while `|` keeps the line breaks. Descriptions use both.
            rest == ">" || rest == "|" || rest == ">-" || rest == "|-" -> {
                val block = mutableListOf<String>()
                while (index < lines.size &&
                    (lines[index].isBlank() || lines[index].first().isWhitespace())
                ) {
                    block += lines[index].trim()
                    index++
                }
                fields[qualified] = if (rest.startsWith(">")) {
                    block.joinToString(" ").replace(SPACES, " ").trim()
                } else {
                    block.joinToString("\n").trim()
                }
            }

            rest.isEmpty() -> Unit // A parent key; its children are the following indented lines.

            else -> fields[qualified] = unquote(rest)
        }
    }
    return fields
}

/** Strips matching quotes and a trailing comment from an unquoted scalar. */
private fun unquote(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length >= 2 && trimmed.first() == trimmed.last() && trimmed.first() in "\"'") {
        return trimmed.substring(1, trimmed.length - 1)
    }
    // Only an unquoted value can carry a comment, and only when the # follows whitespace —
    // otherwise every description mentioning a "#tag" would lose half of itself.
    return trimmed.substringBefore(" #").trim()
}

/**
 * The name a skill is called by, which is also the name the model has to type to load it.
 *
 * Normalised to the ecosystem's own convention — lowercase, hyphens — because that is what the
 * directory names are, and a skill whose frontmatter says "Web Design Guidelines" while its folder
 * says `web-design-guidelines` must not end up being two different things to refer to.
 */
private fun normalizeName(raw: String): String = raw
    .trim()
    .lowercase()
    .replace(NAME_SEPARATORS, "-")
    .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    .trim('-')
    .take(MAX_NAME_CHARS)

private const val FRONTMATTER_FENCE = "---"
private const val MAX_NAME_CHARS = 64
private val NAME_SEPARATORS = Regex("[\\s/]+")
private val SPACES = Regex(" {2,}")
