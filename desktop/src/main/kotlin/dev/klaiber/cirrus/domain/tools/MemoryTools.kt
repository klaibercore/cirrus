package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.domain.model.Memory
import dev.klaiber.cirrus.domain.model.MemoryKind
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Cross-session memory, as three tools the model drives itself.
 *
 * On demand rather than automatic, which is the whole design. Summarising every conversation into
 * the store would fill it with the transient — what was asked on Tuesday — and quietly send all of
 * it back on every unrelated turn. A model that has to decide "this is worth keeping" and later
 * "this is worth looking up" writes far less and far better, and the user can read every line of
 * what it kept.
 */
class RememberTool(
    private val memories: MemoryRepository,
) : CirrusTool {

    override val name: String = "remember"

    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put(
                "description",
                "Save a durable fact about the user or their work so it is available in future " +
                    "conversations. Use it for things that will still be true next month: " +
                    "preferences, decisions, ongoing projects, people, how they like to work. " +
                    "Do NOT use it for the contents of this conversation, for anything " +
                    "time-bound, or for something the user asked you to forget. Write one " +
                    "self-contained sentence — it will be read without any of this conversation " +
                    "around it. Saying something close to an existing memory updates that memory " +
                    "rather than adding a second copy.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "The fact, as one self-contained sentence.")
                    }
                    putJsonObject("kind") {
                        put("type", "string")
                        put(
                            "description",
                            "One of: fact, preference, project, person, routine.",
                        )
                    }
                    putJsonObject("pinned") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Only for something that should be in front of you on every single " +
                                "turn. Default false; pinning everything defeats the purpose.",
                        )
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("content")) })
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): String = memoryTool {
        val content = arguments.string("content")
            ?: return@memoryTool """{"error":"missing required argument: content"}"""
        val kind = MemoryKind.fromName(arguments.string("kind"))
        val pinned = (arguments["pinned"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

        val result = memories.remember(content, kind, pinned = pinned)
        buildJsonObject {
            put("saved", result.memory != null)
            put("created_new", result.wasNew)
            result.memory?.let {
                put("id", it.id)
                put("content", it.content)
                put("kind", it.kind.name.lowercase())
            }
        }.toString()
    }
}

/** Looks something up in what has been remembered. */
class RecallTool(
    private val memories: MemoryRepository,
) : CirrusTool {

    override val name: String = "recall"

    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put(
                "description",
                "Search everything remembered about the user from previous conversations. Call " +
                    "this before answering anything that depends on who they are, what they are " +
                    "working on, or how they like things done — and whenever they refer to " +
                    "something as though you should already know it. An empty result means " +
                    "nothing has been remembered on that subject; say so rather than guessing.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "What to look for, in a few words.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "How many memories to return (1-15). Default 6.")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): String = memoryTool {
        val query = arguments.string("query").orEmpty()
        val limit = (arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 15)
            ?: DEFAULT_LIMIT

        val hits = memories.recall(query, limit)
        buildJsonObject {
            put("query", query)
            put("count", hits.size)
            put("memories", JsonArray(hits.map(::describe)))
        }.toString()
    }

    private companion object {
        const val DEFAULT_LIMIT = 6
    }
}

/** Retires a memory. Archived rather than deleted, so the user can put it back. */
class ForgetTool(
    private val memories: MemoryRepository,
) : CirrusTool {

    override val name: String = "forget"

    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put(
                "description",
                "Retire a memory that is wrong or out of date, by its id from a recall result. " +
                    "Use this when the user corrects something you remembered or asks you to " +
                    "forget it. The memory is archived rather than destroyed, and the user can " +
                    "restore it from the memory screen.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "The memory id, as returned by recall.")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("id")) })
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): String = memoryTool {
        val id = arguments.string("id")
            ?: return@memoryTool """{"error":"missing required argument: id"}"""
        val existing = memories.byId(id)
            ?: return@memoryTool """{"error":"no memory with that id"}"""

        memories.archive(id)
        buildJsonObject {
            put("forgotten", true)
            put("content", existing.content)
        }.toString()
    }
}

/**
 * Nothing a memory tool does may throw.
 *
 * The model is mid-turn; an exception here ends the turn with a stack trace instead of letting it
 * recover. A failure comes back as data the model can read and work around.
 */
private suspend fun memoryTool(block: suspend () -> String): String = try {
    block()
} catch (error: Throwable) {
    errorJson(error.message ?: "The memory store could not be reached.")
}

/** The shape a memory takes when it is handed to the model. */
private fun describe(memory: Memory): JsonObject = buildJsonObject {
    put("id", memory.id)
    put("content", memory.content)
    put("kind", memory.kind.name.lowercase())
    if (memory.pinned) put("pinned", true)
}
