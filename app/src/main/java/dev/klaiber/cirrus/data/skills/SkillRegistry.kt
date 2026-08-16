package dev.klaiber.cirrus.data.skills

import dev.klaiber.cirrus.di.GitHubHttp
import dev.klaiber.cirrus.domain.skills.SkillDocument
import dev.klaiber.cirrus.domain.skills.SkillOrigin
import dev.klaiber.cirrus.domain.skills.SkillReference
import dev.klaiber.cirrus.domain.skills.parseSkillDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A skill that could not be fetched, said in a sentence somebody can act on. */
class SkillException(message: String) : IOException(message)

/** One `SKILL.md` found in a repository, parsed and ready to install. */
data class DiscoveredSkill(
    val document: SkillDocument,
    val origin: SkillOrigin,
)

/**
 * Finds and downloads skills from a public GitHub repository.
 *
 * This is `npx skills add` for a phone, and it is not a metaphor: the CLI's registry *is* public
 * GitHub — any repository with a `SKILL.md` in it is a valid source — so reading the same files
 * over the same host's HTTP API installs exactly what the CLI would install. There is no npm
 * package to run and no way to run one: since API 29 Android refuses to execute a binary an app
 * downloaded into its own data directory, so there is no Node here and never will be. Three
 * requests do the job the CLI does with a clone.
 *
 * The token, when the user has configured one, comes along for the ride via [GitHubHttp]. Nothing
 * here needs it — every skill library worth installing is public — but unauthenticated GitHub
 * allows sixty requests an hour per address, which a shared network can exhaust without the user
 * doing anything at all. It is GitHub's own token going to GitHub's own hosts; no third party sees
 * it. Anonymous is the normal case and works.
 */
@Singleton
class SkillRegistry @Inject constructor(
    @GitHubHttp private val httpClient: OkHttpClient,
    private val json: Json,
) {

    /**
     * Every skill [reference] points at.
     *
     * Discovery follows the layouts the ecosystem actually uses rather than a single blessed one: a
     * `SKILL.md` at the root (a repository that *is* one skill), anything under the container
     * directories the CLI searches, and — when neither turns anything up — any `SKILL.md` shallow
     * enough to have been meant as one. That last rule is what stops a perfectly good repository
     * with its own layout being reported as empty.
     */
    suspend fun discover(reference: SkillReference): List<DiscoveredSkill> =
        withContext(Dispatchers.IO) {
            val ref = reference.ref ?: defaultBranch(reference)
            val tree = tree(reference, ref)

            val paths = selectSkillPaths(tree, reference.path)
            if (paths.isEmpty()) {
                throw SkillException(
                    "No SKILL.md found in ${reference.repository}. A skill library keeps them " +
                        "under skills/, and a single-skill repository keeps one at the root.",
                )
            }

            paths.take(MAX_SKILLS).mapNotNull { path ->
                val raw = download(reference, ref, path) ?: return@mapNotNull null
                val document = parseSkillDocument(
                    raw = raw,
                    fallbackName = path.removeSuffix("/SKILL.md").substringAfterLast('/'),
                ) ?: return@mapNotNull null
                if (document.hidden) return@mapNotNull null

                DiscoveredSkill(
                    document = document,
                    origin = SkillOrigin(
                        repository = reference.repository,
                        path = path,
                        ref = ref,
                    ),
                )
            }.ifEmpty {
                throw SkillException(
                    "Found SKILL.md in ${reference.repository}, but none of them could be read.",
                )
            }
        }

    private suspend fun defaultBranch(reference: SkillReference): String {
        val body = get(
            "https://api.github.com/repos/${reference.owner}/${reference.repo}",
            describe = reference.repository,
        )
        return runCatching {
            json.decodeFromString(RepositoryDto.serializer(), body).defaultBranch
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "main"
    }

    private suspend fun tree(reference: SkillReference, ref: String): List<TreeEntryDto> {
        val url = "https://api.github.com/repos/${reference.owner}/${reference.repo}/git/trees/$ref"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("recursive", "1")
            .build()
            .toString()

        val body = get(url, describe = "${reference.repository}@$ref")
        val tree = runCatching { json.decodeFromString(TreeDto.serializer(), body) }.getOrNull()
            ?: throw SkillException("GitHub returned something unreadable for ${reference.label}.")
        return tree.tree
    }

    /**
     * Which of the repository's files are skills.
     *
     * Ordered, because the first rule that matches wins and the order is the specificity: an
     * explicit path is the user naming one skill and must not pull in its neighbours.
     */
    private fun selectSkillPaths(tree: List<TreeEntryDto>, requestedPath: String?): List<String> {
        val files = tree
            .filter { it.type == "blob" && it.path.substringAfterLast('/') == SKILL_FILE }
            .filter { it.size == null || it.size <= MAX_SKILL_BYTES }
            .map { it.path }
            .sorted()

        if (requestedPath != null) {
            val prefix = requestedPath.trim('/').removeSuffix("/$SKILL_FILE")
            return files.filter { it == "$prefix/$SKILL_FILE" || it.startsWith("$prefix/") }
        }

        val conventional = files.filter { path ->
            path == SKILL_FILE || CONTAINERS.any { path.startsWith("$it/") }
        }
        if (conventional.isNotEmpty()) return conventional

        return files.filter { it.count { character -> character == '/' } <= MAX_UNCONVENTIONAL_DEPTH }
    }

    /** A single file, from the raw host. A file that will not download is skipped, not fatal. */
    private fun download(reference: SkillReference, ref: String, path: String): String? {
        val url = "https://raw.githubusercontent.com/${reference.owner}/${reference.repo}/$ref/" +
            path.split('/').joinToString("/") { segment -> segment.encodePathSegment() }

        val request = Request.Builder().url(url).header("Accept", "text/plain").build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().take(MAX_SKILL_CHARS)
            }
        }.getOrNull()
    }

    private fun get(url: String, describe: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) return response.body.string()
            throw when {
                response.code == 404 -> SkillException(
                    "$describe could not be found on GitHub. Check the spelling, and note that " +
                        "Cirrus can only read public repositories unless you have added a token.",
                )

                response.code == 403 && response.header("X-RateLimit-Remaining") == "0" ->
                    SkillException(
                        "GitHub's rate limit for this network is used up. Wait an hour, or add a " +
                            "personal access token in Settings → GitHub and MCP.",
                    )

                else -> SkillException("GitHub returned ${response.code} for $describe.")
            }
        }
    }

    private companion object {
        const val SKILL_FILE = "SKILL.md"
        const val API_VERSION = "2022-11-28"

        /**
         * Where the CLI looks, and therefore where skills are written to be found.
         *
         * The agent-specific directories are here because a repository that ships skills for one
         * agent has them under that agent's folder, and there is nothing agent-specific about the
         * file inside it.
         */
        val CONTAINERS = listOf("skills", ".agents/skills", ".claude/skills", ".cursor/skills")

        /** A library, not a monorepo of them. Enough for the largest published set, twice over. */
        const val MAX_SKILLS = 40

        /** Beyond this a SKILL.md is not instructions, and the model could not read it anyway. */
        const val MAX_SKILL_BYTES = 256 * 1024
        const val MAX_SKILL_CHARS = 64_000

        /** `a/b/SKILL.md` — deeper than that without a container is not a skill layout. */
        const val MAX_UNCONVENTIONAL_DEPTH = 2
    }
}

/** Percent-encodes what a path segment may not carry, leaving what it may. */
private fun String.encodePathSegment(): String = buildString {
    this@encodePathSegment.forEach { character ->
        if (character.isLetterOrDigit() || character in "-._~") {
            append(character)
        } else {
            character.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                append('%').append("%02X".format(byte))
            }
        }
    }
}

@Serializable
private data class RepositoryDto(
    @SerialName("default_branch") val defaultBranch: String = "main",
)

@Serializable
private data class TreeDto(
    val tree: List<TreeEntryDto> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
private data class TreeEntryDto(
    val path: String = "",
    val type: String = "",
    val size: Long? = null,
)
