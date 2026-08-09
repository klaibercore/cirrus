package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Shared plumbing for the GitHub tools: schema construction, argument reading, and turning a
 * failure into something the model can read and act on.
 *
 * A tool must never throw. The model is mid-turn when it calls one, and an exception would end
 * the turn with a stack trace instead of letting the model recover — retrying with a corrected
 * argument, or telling the user their token is missing.
 */

/** Characters of free text a listing should carry per item before it becomes noise. */
internal const val SHORT_TEXT = 200

/** A repository argument, already split into its two halves. */
internal data class RepoTarget(val owner: String, val repo: String) {
    val fullName: String get() = "$owner/$repo"
}

/** Builds the OpenAI-style function schema Ollama expects in `ChatRequest.tools`. */
internal fun functionSchema(
    name: String,
    description: String,
    properties: SchemaBuilder.() -> Unit,
): JsonElement {
    val builder = SchemaBuilder().apply(properties)
    return buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    builder.properties.forEach { (key, value) -> put(key, value) }
                }
                put(
                    "required",
                    buildJsonArray { builder.required.forEach { add(JsonPrimitive(it)) } },
                )
            }
        }
    }
}

internal class SchemaBuilder {
    val properties = linkedMapOf<String, JsonElement>()
    val required = mutableListOf<String>()

    fun stringProperty(name: String, description: String, required: Boolean = false) =
        property(name, "string", description, required)

    fun integerProperty(name: String, description: String, required: Boolean = false) =
        property(name, "integer", description, required)

    fun booleanProperty(name: String, description: String, required: Boolean = false) =
        property(name, "boolean", description, required)

    fun arrayProperty(name: String, description: String, required: Boolean = false) =
        property(name, "array", description, required)

    private fun property(name: String, type: String, description: String, required: Boolean) {
        properties[name] = buildJsonObject {
            put("type", type)
            put("description", description)
        }
        if (required) this.required += name
    }
}

/**
 * Runs a tool body, converting any failure into a JSON error the model can read.
 *
 * The messages are deliberately actionable: "add a token in Settings" tells the model to stop
 * and say so, where a bare 401 would invite it to retry forever.
 */
internal suspend fun runTool(block: suspend () -> String): String = try {
    block()
} catch (github: GitHubException) {
    errorJson(github.message ?: "GitHub request failed.")
} catch (io: IOException) {
    errorJson("Could not reach GitHub: ${io.message ?: "network error"}.")
} catch (unexpected: Exception) {
    errorJson(unexpected.message ?: unexpected::class.simpleName ?: "Tool failed.")
}

internal fun errorJson(message: String): String =
    buildJsonObject { put("error", message) }.toString()

internal fun missingArgument(name: String): String =
    errorJson("missing required argument: $name")

/** Accepts `owner/name`, the form every GitHub URL and CLI uses. */
internal fun JsonObject.repoOrNull(): RepoTarget? {
    val raw = stringOrNull("repo") ?: return null
    val parts = raw.trim().removePrefix("https://github.com/").trim('/').split('/')
    if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    return RepoTarget(owner = parts[0], repo = parts[1].removeSuffix(".git"))
}

internal fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return runCatching { element.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Models routinely send numbers as strings, so parse rather than insist on the JSON type. */
internal fun JsonObject.intOrNull(key: String): Int? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return runCatching { element.jsonPrimitive.content.trim().toInt() }.getOrNull()
}

internal fun JsonObject.booleanOrNull(key: String): Boolean? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return runCatching { element.jsonPrimitive.content.trim().toBooleanStrict() }.getOrNull()
}
