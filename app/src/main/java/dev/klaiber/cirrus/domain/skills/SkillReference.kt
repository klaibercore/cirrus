package dev.klaiber.cirrus.domain.skills

/**
 * Where to look for skills, as parsed from whatever the user typed.
 *
 * The forms are `npx skills add`'s own, minus the ones a phone cannot honour. `owner/repo` is the
 * shorthand everybody uses; the full GitHub URL is what you get from the address bar; and the
 * `/tree/<ref>/<path>` form is how a single skill inside a large library is named, which matters
 * because `anthropics/skills` holds dozens and somebody may want one.
 *
 * Git URLs that are not GitHub, and local paths, are deliberately not accepted. Both mean cloning,
 * and Android will not execute a binary an app downloaded into its own data directory — so there is
 * no git here to clone with. GitHub is reachable as an HTTP API, which is why it is the one Cirrus
 * supports; see [dev.klaiber.cirrus.data.skills.SkillRegistry].
 */
data class SkillReference(
    val owner: String,
    val repo: String,
    /** Branch or tag, or null to use the repository's default branch. */
    val ref: String? = null,
    /** A directory or file within the repository, or null to search the whole of it. */
    val path: String? = null,
) {
    val repository: String get() = "$owner/$repo"

    /** How the reference reads back to the user, in the shorthand they most likely typed. */
    val label: String
        get() = buildString {
            append(repository)
            path?.let { append('/').append(it.removeSuffix("/SKILL.md")) }
            ref?.let { append('@').append(it) }
        }
}

/**
 * Reads a skill source, or null when it is not one Cirrus can fetch.
 *
 * Returning null rather than throwing because "that is not a GitHub repository" is a sentence for
 * the person typing, and the caller is the only thing that knows how to show it to them.
 */
fun parseSkillReference(input: String): SkillReference? {
    var text = input.trim()
    if (text.isEmpty()) return null

    // git@github.com:owner/repo.git — the SSH form, which people paste out of habit.
    text = text.removePrefix("git@github.com:")
    // Everything after the scheme, if there was one. `substringAfter` with the string itself as the
    // fallback is what makes the scheme optional rather than requiring two paths through here.
    text = text.substringAfter("://", text).removeSuffix(".git").trim('/')
    if (text.isEmpty()) return null

    // A first segment with a dot in it is a host, not an owner — GitHub owners are letters, digits
    // and hyphens. This is the line that turns a GitLab address into an error message rather than
    // into a request to github.com for a repository called "someone" owned by "gitlab.com".
    val leading = text.substringBefore('/')
    if ('.' in leading) {
        if (!leading.equals("github.com", true) && !leading.equals("www.github.com", true)) {
            return null
        }
        text = text.substringAfter('/', "")
        if (text.isEmpty()) return null
    }

    // owner/repo@ref, but only when the @ is in the last segment: an owner cannot contain one.
    var pinned: String? = null
    val at = text.lastIndexOf('@')
    if (at > 0 && '/' !in text.substring(at)) {
        pinned = text.substring(at + 1).takeIf { it.isNotBlank() }
        text = text.substring(0, at)
    }

    val segments = text.split('/').filter { it.isNotBlank() }
    if (segments.size < 2) return null
    val owner = segments[0]
    val repo = segments[1]
    if (!owner.isRepositorySegment() || !repo.isRepositorySegment()) return null

    // .../tree/<ref>/<path>, .../blob/<ref>/<path>. Anything else after owner/repo is taken as a
    // path on the default branch, which is what somebody typing "owner/repo/skills/x" means.
    val rest = segments.drop(2)
    return when {
        rest.isEmpty() -> SkillReference(owner, repo, pinned, null)

        rest[0] in TREE_MARKERS && rest.size >= 2 -> SkillReference(
            owner = owner,
            repo = repo,
            ref = rest[1],
            path = rest.drop(2).joinToString("/").takeIf { it.isNotEmpty() },
        )

        rest[0] in TREE_MARKERS -> SkillReference(owner, repo, pinned, null)

        else -> SkillReference(owner, repo, pinned, rest.joinToString("/"))
    }
}

/**
 * GitHub's own rule for owner and repository names, which is also a decent injection guard.
 *
 * The "at least one letter or digit" clause is not pedantry: without it `./local-skills` parses as
 * an owner called "." — a local path quietly becoming a request to github.com.
 */
private fun String.isRepositorySegment(): Boolean =
    isNotEmpty() &&
        length <= 100 &&
        any { it.isLetterOrDigit() } &&
        all { it.isLetterOrDigit() || it in "-._" }

private val TREE_MARKERS = setOf("tree", "blob")
