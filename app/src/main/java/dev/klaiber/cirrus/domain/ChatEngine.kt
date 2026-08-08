package dev.klaiber.cirrus.domain

import android.util.Base64
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.dto.ChatRequestDto
import dev.klaiber.cirrus.data.remote.dto.MessageDto
import dev.klaiber.cirrus.data.remote.dto.ToolCallDto
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.GenerationStats
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.ThinkMode
import dev.klaiber.cirrus.domain.model.ToolInvocation
import dev.klaiber.cirrus.domain.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Incremental results of one assistant turn, including any tool round trips it needs. */
sealed interface TurnEvent {
    /** The exact JSON body about to be sent; surfaced by the developer-mode inspector. */
    data class RequestPrepared(val requestJson: String) : TurnEvent

    data class ThinkingDelta(val text: String) : TurnEvent

    data class ContentDelta(val text: String) : TurnEvent

    data class ToolStarted(val invocation: ToolInvocation) : TurnEvent

    data class ToolFinished(val invocation: ToolInvocation) : TurnEvent

    data class Finished(val stats: GenerationStats) : TurnEvent
}

/**
 * Runs a single assistant turn: builds the wire request, streams the reply, and services any
 * tool calls the model makes before looping back for the final answer.
 *
 * Deliberately free of persistence and UI concerns so the whole turn protocol can be tested
 * against a mock server.
 */
@Singleton
class ChatEngine @Inject constructor(
    private val client: OllamaClient,
    private val toolRegistry: ToolRegistry,
    private val json: Json,
) {

    fun respond(
        conversation: Conversation,
        history: List<ChatMessage>,
        settings: AppSettings,
    ): Flow<TurnEvent> = flow {
        val wireMessages = buildWireMessages(conversation, history, settings).toMutableList()
        val turnStartedAt = System.currentTimeMillis()
        var timeToFirstToken: Long? = null
        var toolIteration = 0

        while (true) {
            val request = buildRequest(conversation, wireMessages, settings)
            emit(TurnEvent.RequestPrepared(client.encodeRequest(request)))

            val content = StringBuilder()
            val thinking = StringBuilder()
            val pendingToolCalls = mutableListOf<ToolCallDto>()
            var stats = GenerationStats()

            client.streamChat(request).collect { chunk ->
                chunk.message?.let { message ->
                    message.thinking?.takeIf { it.isNotEmpty() }?.let { delta ->
                        if (timeToFirstToken == null) {
                            timeToFirstToken = System.currentTimeMillis() - turnStartedAt
                        }
                        thinking.append(delta)
                        emit(TurnEvent.ThinkingDelta(delta))
                    }
                    message.content.takeIf { it.isNotEmpty() }?.let { delta ->
                        if (timeToFirstToken == null) {
                            timeToFirstToken = System.currentTimeMillis() - turnStartedAt
                        }
                        content.append(delta)
                        emit(TurnEvent.ContentDelta(delta))
                    }
                    message.toolCalls?.let(pendingToolCalls::addAll)
                }
                if (chunk.done) {
                    stats = GenerationStats(
                        totalDurationNs = chunk.totalDuration,
                        loadDurationNs = chunk.loadDuration,
                        promptEvalCount = chunk.promptEvalCount,
                        promptEvalDurationNs = chunk.promptEvalDuration,
                        evalCount = chunk.evalCount,
                        evalDurationNs = chunk.evalDuration,
                        doneReason = chunk.doneReason,
                        timeToFirstTokenMs = timeToFirstToken,
                    )
                }
            }

            val shouldRunTools = pendingToolCalls.isNotEmpty() &&
                conversation.toolsEnabled &&
                toolIteration < settings.maxToolIterations
            if (!shouldRunTools) {
                emit(TurnEvent.Finished(stats))
                return@flow
            }

            // Replay the model's own tool-call message, then answer each call in order.
            wireMessages += MessageDto(
                role = Role.ASSISTANT.wire,
                content = content.toString(),
                toolCalls = pendingToolCalls.toList(),
            )

            for (call in pendingToolCalls) {
                val invocation = ToolInvocation(
                    id = UUID.randomUUID().toString(),
                    name = call.function.name,
                    argumentsJson = call.function.arguments.toString(),
                )
                emit(TurnEvent.ToolStarted(invocation))

                val startedAt = System.currentTimeMillis()
                val tool = toolRegistry.find(call.function.name)
                val outcome = if (tool == null) {
                    invocation.copy(
                        errorMessage = "Unknown tool: ${call.function.name}",
                        durationMs = 0L,
                    )
                } else {
                    runCatching { tool.execute(call.function.arguments) }.fold(
                        onSuccess = { result ->
                            invocation.copy(
                                resultJson = result,
                                durationMs = System.currentTimeMillis() - startedAt,
                            )
                        },
                        onFailure = { error ->
                            invocation.copy(
                                errorMessage = error.message ?: "Tool failed.",
                                durationMs = System.currentTimeMillis() - startedAt,
                            )
                        },
                    )
                }
                emit(TurnEvent.ToolFinished(outcome))

                wireMessages += MessageDto(
                    role = Role.TOOL.wire,
                    content = outcome.resultJson
                        ?: """{"error":${JsonPrimitive(outcome.errorMessage ?: "failed")}}""",
                    toolName = outcome.name,
                )
            }
            toolIteration++
        }
    }

    /**
     * Asks the model for a short thread title. Returns null on any failure, since a missing
     * title is cosmetic and must never surface as an error in the chat.
     */
    suspend fun suggestTitle(model: String, userText: String, assistantText: String): String? =
        runCatching {
            val request = ChatRequestDto(
                model = model,
                stream = true,
                messages = listOf(
                    MessageDto(
                        role = Role.SYSTEM.wire,
                        content = "You write short chat titles. Reply with a title of at most six " +
                            "words. No quotes, no punctuation at the end, no preamble.",
                    ),
                    MessageDto(
                        role = Role.USER.wire,
                        content = buildString {
                            append("User: ")
                            append(userText.take(TITLE_SOURCE_CHARS))
                            if (assistantText.isNotBlank()) {
                                append("\n\nAssistant: ")
                                append(assistantText.take(TITLE_SOURCE_CHARS))
                            }
                        },
                    ),
                ),
                // Thinking models would otherwise spend their budget on a six-word title.
                think = JsonPrimitive(false),
                options = buildJsonObject {
                    put("temperature", JsonPrimitive(0.2))
                    put("num_predict", JsonPrimitive(TITLE_TOKEN_BUDGET))
                },
            )
            val builder = StringBuilder()
            client.streamChat(request).collect { chunk ->
                chunk.message?.content?.let(builder::append)
            }
            builder.toString()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.trim('"', '\'', '.', ' ')
                ?.take(MAX_TITLE_CHARS)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun buildRequest(
        conversation: Conversation,
        messages: List<MessageDto>,
        settings: AppSettings,
    ): ChatRequestDto {
        val params = conversation.params
        return ChatRequestDto(
            model = conversation.model,
            messages = messages,
            stream = true,
            think = params.thinkMode.toJsonElement(),
            tools = if (conversation.toolsEnabled) toolRegistry.definitions else null,
            format = params.responseFormat?.let(::parseFormat),
            options = buildOptions(params),
            keepAlive = params.keepAlive,
        )
    }

    /** Translates history into wire messages, applying the system prompt and context window. */
    private suspend fun buildWireMessages(
        conversation: Conversation,
        history: List<ChatMessage>,
        settings: AppSettings,
    ): List<MessageDto> {
        val relevant = history.filter { it.role != Role.SYSTEM && it.errorMessage == null }
        val windowed = if (settings.contextMessageLimit > 0) {
            relevant.takeLast(settings.contextMessageLimit)
        } else {
            relevant
        }

        return buildList {
            conversation.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                add(MessageDto(role = Role.SYSTEM.wire, content = prompt))
            }
            windowed.forEach { message ->
                add(
                    MessageDto(
                        role = message.role.wire,
                        content = composeContent(message),
                        images = encodeImages(message.attachments).takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }
    }

    /** Inlines extracted document text so non-vision models still see attached files. */
    private fun composeContent(message: ChatMessage): String {
        val documents = message.attachments.filter { it.kind == Attachment.Kind.DOCUMENT }
        if (documents.isEmpty()) return message.content

        return buildString {
            documents.forEach { document ->
                val text = document.extractedText.orEmpty()
                if (text.isNotBlank()) {
                    append("<attachment name=\"").append(document.displayName).append("\">\n")
                    append(text.take(MAX_DOCUMENT_CHARS))
                    append("\n</attachment>\n\n")
                }
            }
            append(message.content)
        }
    }

    private suspend fun encodeImages(attachments: List<Attachment>): List<String> =
        withContext(Dispatchers.IO) {
            attachments
                .filter { it.kind == Attachment.Kind.IMAGE }
                .mapNotNull { attachment ->
                    runCatching {
                        val bytes = File(attachment.localPath).readBytes()
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }.getOrNull()
                }
        }

    private fun buildOptions(params: GenerationParams): JsonObject? {
        val entries = buildMap<String, JsonElement> {
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.topK?.let { put("top_k", JsonPrimitive(it)) }
            params.minP?.let { put("min_p", JsonPrimitive(it)) }
            params.repeatPenalty?.let { put("repeat_penalty", JsonPrimitive(it)) }
            params.presencePenalty?.let { put("presence_penalty", JsonPrimitive(it)) }
            params.frequencyPenalty?.let { put("frequency_penalty", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            params.numCtx?.let { put("num_ctx", JsonPrimitive(it)) }
            params.numPredict?.let { put("num_predict", JsonPrimitive(it)) }
            if (params.stop.isNotEmpty()) {
                put(
                    "stop",
                    kotlinx.serialization.json.JsonArray(params.stop.map(::JsonPrimitive)),
                )
            }
        }
        return entries.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

    /** `format` is either the literal "json" or a full JSON schema object. */
    private fun parseFormat(raw: String): JsonElement? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("json", ignoreCase = true)) return JsonPrimitive("json")
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    }

    private fun ThinkMode.toJsonElement(): JsonElement? = when (val value = wireValue()) {
        null -> null
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> null
    }

    private companion object {
        const val MAX_DOCUMENT_CHARS = 100_000
        const val TITLE_SOURCE_CHARS = 500
        const val TITLE_TOKEN_BUDGET = 24
        const val MAX_TITLE_CHARS = 60
    }
}
