package dev.klaiber.cirrus.domain.memory

import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.dto.ChatRequestDto
import dev.klaiber.cirrus.data.remote.dto.MessageDto
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.Memory
import dev.klaiber.cirrus.domain.model.MemoryKind
import dev.klaiber.cirrus.domain.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The nightly pass over what has been remembered.
 *
 * Two jobs, in order. First it reads the threads that have happened since the last pass and writes
 * down anything durable the model did not think to save at the time — people remember to *use*
 * memory far less often than they should, and the interesting facts are usually said in passing.
 * Then it looks at the store as a whole and tidies it: merges the four sentences that all say the
 * same thing, and retires what has been overtaken.
 *
 * It runs at night for an unglamorous reason — it is several model calls over a lot of text, and
 * doing that while someone is waiting for an answer would make the app feel slow for a benefit
 * they would not notice until next week.
 *
 * Nothing here deletes. Retiring is archiving, and the memory screen can put anything back.
 */
@Singleton
class MemoryConsolidator @Inject constructor(
    private val client: OllamaClient,
    private val memories: MemoryRepository,
    private val conversations: ConversationRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val json: Json,
) {

    data class Report(
        val harvested: Int = 0,
        val merged: Int = 0,
        val retired: Int = 0,
        val failure: String? = null,
    ) {
        val changed: Int get() = harvested + merged + retired
    }

    suspend fun consolidate(): Report {
        val appSettings = settings.current.value
        val model = appSettings.defaultModel.takeIf { it.isNotBlank() }
            ?: models.models.value.firstOrNull()?.name
            ?: return Report(failure = "No model is configured.")

        val since = appSettings.lastConsolidationAt
        val harvested = runCatching { harvest(model, since) }.getOrElse { 0 }
        val tidied = runCatching { tidy(model) }.getOrElse { Report() }

        settings.setLastConsolidationAt(System.currentTimeMillis())
        return Report(
            harvested = harvested,
            merged = tidied.merged,
            retired = tidied.retired,
        )
    }

    /** Reads recent threads and writes down what will still matter next month. */
    private suspend fun harvest(model: String, since: Long): Int {
        val recent = conversations.recentlyUpdated(since, MAX_THREADS)
        if (recent.isEmpty()) return 0

        val digest = buildString {
            recent.forEach { conversation ->
                val messages = conversations.getMessages(conversation.id)
                    .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
                    .takeLast(MAX_MESSAGES_PER_THREAD)
                if (messages.isEmpty()) return@forEach
                append("## ").append(conversation.title).append('\n')
                messages.forEach { message ->
                    append(if (message.role == Role.USER) "User: " else "Assistant: ")
                    append(message.content.take(MAX_MESSAGE_CHARS).replace('\n', ' '))
                    append('\n')
                }
                append('\n')
            }
        }.take(MAX_DIGEST_CHARS)

        if (digest.isBlank()) return 0

        val existing = memories.active().joinToString("\n") { "- ${it.content}" }
        val reply = ask(
            model = model,
            system = HARVEST_SYSTEM,
            user = buildString {
                append("Already remembered:\n")
                append(existing.ifBlank { "(nothing yet)" })
                append("\n\nRecent conversations:\n")
                append(digest)
            },
        ) ?: return 0

        var saved = 0
        reply.arrayOf("memories").forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val content = item["content"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (content.length < MIN_MEMORY_CHARS) return@forEach
            val kind = MemoryKind.fromName(item["kind"]?.jsonPrimitive?.content)
            if (memories.remember(content, kind).wasNew) saved++
        }
        return saved
    }

    /** Merges memories that say the same thing, and retires what no longer holds. */
    private suspend fun tidy(model: String): Report {
        val active = memories.active().take(MAX_MEMORIES)
        if (active.size < MIN_TO_TIDY) return Report()

        val numbered = active.mapIndexed { index, memory -> "$index. ${memory.content}" }
            .joinToString("\n")

        val reply = ask(model, TIDY_SYSTEM, numbered) ?: return Report()

        var merged = 0
        var retired = 0

        reply.arrayOf("merge").forEach { element ->
            val group = element as? JsonObject ?: return@forEach
            val indices = group["indices"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
                ?.filter { it in active.indices }
                ?.distinct()
                .orEmpty()
            val replacement = group["content"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (indices.size < 2 || replacement.length < MIN_MEMORY_CHARS) return@forEach

            // Keep the oldest of the group: it carries the earliest createdAt, which is the honest
            // answer to "since when has this been true?".
            val survivors = indices.map { active[it] }.sortedBy { it.createdAt }
            val keep = survivors.first()
            memories.update(
                keep.copy(
                    content = replacement.take(Memory.MAX_CONTENT_CHARS),
                    pinned = survivors.any { it.pinned },
                    confidence = survivors.maxOf { it.confidence },
                ),
            )
            survivors.drop(1).forEach { memories.archive(it.id) }
            merged += survivors.size - 1
        }

        reply.arrayOf("retire").forEach { element ->
            val index = element.jsonPrimitive.content.toIntOrNull() ?: return@forEach
            val memory = active.getOrNull(index) ?: return@forEach
            // A pinned memory was pinned by a person; a model does not get to retire it.
            if (memory.pinned) return@forEach
            memories.archive(memory.id)
            retired++
        }

        return Report(merged = merged, retired = retired)
    }

    /** One bounded, non-streaming-shaped request, answered as JSON. */
    private suspend fun ask(model: String, system: String, user: String): JsonObject? = runCatching {
        val request = ChatRequestDto(
            model = model,
            stream = true,
            messages = listOf(
                MessageDto(role = Role.SYSTEM.wire, content = system),
                MessageDto(role = Role.USER.wire, content = user),
            ),
            // Consolidation is a judgement call, but a wandering one is expensive and unreadable.
            think = JsonPrimitive(false),
            format = JsonPrimitive("json"),
            options = buildJsonObject {
                put("temperature", JsonPrimitive(0.1))
                put("num_predict", JsonPrimitive(TOKEN_BUDGET))
            },
        )
        val builder = StringBuilder()
        client.streamChat(request).collect { chunk ->
            chunk.message?.content?.let(builder::append)
        }
        json.parseToJsonElement(builder.toString().trim()).jsonObject
    }.getOrNull()

    private fun JsonObject.arrayOf(key: String) =
        runCatching { this[key]?.jsonArray.orEmpty() }.getOrElse { emptyList() }

    private companion object {
        const val MAX_THREADS = 6
        const val MAX_MESSAGES_PER_THREAD = 12
        const val MAX_MESSAGE_CHARS = 600
        const val MAX_DIGEST_CHARS = 12_000
        const val MAX_MEMORIES = 80
        const val MIN_TO_TIDY = 4
        const val MIN_MEMORY_CHARS = 8
        const val TOKEN_BUDGET = 1_200

        val HARVEST_SYSTEM = """
            You maintain a long-term memory store for a personal assistant.
            Read the recent conversations and extract only facts that will still be true and
            useful in a month: preferences, decisions, ongoing projects, people, constraints,
            recurring routines.
            Never record: what was asked in a conversation, anything time-bound, anything the user
            said in passing about a one-off task, or anything already in the "Already remembered"
            list.
            Each memory must be one self-contained sentence that makes sense with no conversation
            around it. Prefer few and good over many.
            Reply as JSON: {"memories":[{"content":"...","kind":"fact|preference|project|person|routine"}]}
            An empty list is the correct answer when nothing durable was said.
        """.trimIndent()

        val TIDY_SYSTEM = """
            You are tidying a long-term memory store. You are given numbered memories.
            Find groups that say substantially the same thing and merge each group into one clear
            sentence. Find memories that are clearly superseded by a later one, or that are no
            longer plausible, and retire them.
            Be conservative: if two memories differ in any meaningful detail, leave them alone.
            Do not retire something merely because it is old.
            Reply as JSON:
            {"merge":[{"indices":[0,3],"content":"the merged sentence"}],"retire":[7]}
            Use empty lists when there is nothing to do.
        """.trimIndent()
    }
}
