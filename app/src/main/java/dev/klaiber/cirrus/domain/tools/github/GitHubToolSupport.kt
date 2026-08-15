package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubException
import dev.klaiber.cirrus.domain.tools.CirrusTool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Shared plumbing for the GitHub tools.
 *
 * Every tool returns a compact JSON string that is fed straight back to the model as a
 * `role: "tool"` message, so results are truncated hard and errors come back as data rather than
 * exceptions — a model that is told "not found: owner/repo" can correct itself, whereas a thrown
 * exception ends the turn.
 */
abstract class GitHubTool : CirrusTool {

    /** True for anything that changes state on GitHub. Read by the one write gate in ToolRegistry. */
    override val writes: Boolean get() = false

    final override suspend fun execute(arguments: JsonObject): String = try {
        run(arguments)
    } catch (github: GitHubException) {
        errorJson(github.message ?: "GitHub request failed.")
    } catch (io: java.io.IOException) {
        errorJson("Could not reach GitHub: ${io.message}")
    }

    protected abstract suspend fun run(arguments: JsonObject): String
}

internal fun errorJson(message: String): String =
    buildJsonObject { put("error", message) }.toString()

/** Builds the OpenAI-style function schema every tool has to declare. */
internal fun functionSchema(
    name: String,
    description: String,
    required: List<String> = emptyList(),
    properties: JsonObjectBuilder.() -> Unit,
): JsonElement = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", name)
        put("description", description)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties", properties)
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }
}

internal fun JsonObjectBuilder.stringParam(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

internal fun JsonObjectBuilder.intParam(name: String, description: String) {
    putJsonObject(name) {
        put("type", "integer")
        put("description", description)
    }
}

internal fun JsonObjectBuilder.enumParam(
    name: String,
    description: String,
    values: List<String>,
) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
}

internal fun JsonObjectBuilder.arrayParam(name: String, description: String) {
    putJsonObject(name) {
        put("type", "array")
        put("description", description)
    }
}

/** `jsonPrimitive.content` throws on JSON null; this returns null instead. */
internal fun JsonObject.string(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject.int(key: String): Int? =
    this[key]?.takeIf { it !is JsonNull }?.let {
        // Models quote numbers and pad them; trim before parsing.
        runCatching { it.jsonPrimitive.content.trim().toInt() }.getOrNull()
    }

/** A repository named the way people write it: `owner/name`. */
internal data class RepoRef(val owner: String, val name: String) {
    override fun toString(): String = "$owner/$name"
}

/**
 * Parses the `repo` argument.
 *
 * Models reliably produce "owner/repo" because that is how repositories are written everywhere;
 * asking for two separate arguments invites one of them to go missing.
 */
internal fun JsonObject.repoRef(key: String = "repo"): Result<RepoRef> {
    val raw = string(key)
        ?: return Result.failure(IllegalArgumentException("missing required argument: $key"))
    // Models paste the URL they were given rather than reformatting it.
    val parts = raw.trim()
        .removePrefix("https://github.com/")
        .removePrefix("http://github.com/")
        .removeSuffix(".git")
        .split('/')
        .filter { it.isNotBlank() }
    return if (parts.size != 2) {
        Result.failure(
            IllegalArgumentException("$key must look like \"owner/name\", got \"$raw\""),
        )
    } else {
        Result.success(RepoRef(parts[0], parts[1]))
    }
}

/** Keeps one tool result from swallowing the context window. */
internal fun String.clip(max: Int): String =
    if (length <= max) this else take(max) + "\n… [truncated, ${length - max} more characters]"
