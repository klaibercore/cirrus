package dev.klaiber.cirrus.domain

import android.util.Base64
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.OllamaException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
        /**
         * What is already known about the user, from memories pinned in the store. Passed in
         * rather than fetched: this class stays free of persistence so the turn protocol can be
         * tested against a mock server with nothing behind it.
         */
        memoryBrief: String? = null,
    ): Flow<TurnEvent> = flow {
        val wireMessages = buildWireMessages(conversation, history, settings, memoryBrief)
            .toMutableList()
        val clock = TurnClock()
        var toolRounds = 0
        var wrapUpUsed = false

        while (true) {
            // Once the budget is spent the tools are withheld rather than the turn abandoned, so
            // the model's last word is an answer instead of a tool call nobody ran. The
            // conversation's switch governs the tools that reach outside the phone; memory and
            // notifications are offered either way.
            val offerTools = toolRounds < settings.maxToolIterations
            val request = buildRequest(conversation, wireMessages, settings, offerTools)
            emit(TurnEvent.RequestPrepared(client.encodeRequest(request)))

            val round = streamRound(request, clock)

            if (round.toolCalls.isEmpty()) {
                emit(TurnEvent.Finished(round.stats))
                return@flow
            }

            // Replay the model's own tool-call message, then answer each call in order.
            wireMessages += MessageDto(
                role = Role.ASSISTANT.wire,
                content = round.content,
                toolCalls = round.toolCalls,
            )

            if (!offerTools) {
                // The budget is spent and the model asked for another round anyway. One more
                // pass — with the calls answered by an explanation rather than a result — turns a
                // turn that stopped mid-plan into one that says what it found. Only one, though:
                // a model that keeps asking would otherwise loop forever.
                if (wrapUpUsed) {
                    emit(TurnEvent.Finished(round.stats))
                    return@flow
                }
                wrapUpUsed = true
                for (call in round.toolCalls) {
                    val declined = newInvocation(call).copy(
                        errorMessage = toolBudgetMessage(settings.maxToolIterations),
                        durationMs = 0L,
                    )
                    emit(TurnEvent.ToolStarted(declined.copy(errorMessage = null)))
                    emit(TurnEvent.ToolFinished(declined))
                    wireMessages += toolResultMessage(declined)
                }
                continue
            }

            for (call in round.toolCalls) {
                val invocation = newInvocation(call)
                emit(TurnEvent.ToolStarted(invocation))

                val startedAt = System.currentTimeMillis()
                val tool = toolRegistry.find(call.function.name, conversation.toolsEnabled)
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
                wireMessages += toolResultMessage(outcome)
            }
            toolRounds++
        }
    }

    /** What one request/response round of a turn produced. */
    private data class Round(
        val content: String,
        val toolCalls: List<ToolCallDto>,
        val stats: GenerationStats,
    )

    /** Time-to-first-token is measured across the whole turn, not per round. */
    private class TurnClock {
        val startedAt: Long = System.currentTimeMillis()
        var timeToFirstToken: Long? = null

        fun markFirstToken() {
            if (timeToFirstToken == null) timeToFirstToken = System.currentTimeMillis() - startedAt
        }
    }

    /**
     * Streams one round, re-issuing it if the connection dies before producing anything.
     *
     * A retry is only safe while the round is still silent: once deltas are on screen, starting
     * over would repeat them, so a stream cut short after that is surfaced as the error it is.
     */
    private suspend fun FlowCollector<TurnEvent>.streamRound(
        request: ChatRequestDto,
        clock: TurnClock,
    ): Round {
        var attempt = 0
        while (true) {
            val content = StringBuilder()
            val toolCalls = mutableListOf<ToolCallDto>()
            var stats = GenerationStats()
            var produced = false

            try {
                client.streamChat(request).collect { chunk ->
                    chunk.message?.let { message ->
                        message.thinking?.takeIf { it.isNotEmpty() }?.let { delta ->
                            clock.markFirstToken()
                            produced = true
                            emit(TurnEvent.ThinkingDelta(delta))
                        }
                        message.content.takeIf { it.isNotEmpty() }?.let { delta ->
                            clock.markFirstToken()
                            produced = true
                            content.append(delta)
                            emit(TurnEvent.ContentDelta(delta))
                        }
                        // Tool calls are not emitted until the round completes, so a round that
                        // only collected these is still safe to restart.
                        message.toolCalls?.let(toolCalls::addAll)
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
                            timeToFirstTokenMs = clock.timeToFirstToken,
                        )
                    }
                }
                return Round(content.toString(), toolCalls.toList(), stats)
            } catch (truncated: OllamaException.Truncated) {
                if (produced || attempt >= MAX_SILENT_RETRIES) throw truncated
                attempt++
                delay(RETRY_BACKOFF_MS * attempt)
            }
        }
    }

    private fun newInvocation(call: ToolCallDto) = ToolInvocation(
        id = UUID.randomUUID().toString(),
        name = call.function.name,
        argumentsJson = call.function.arguments.toString(),
    )

    private fun toolResultMessage(invocation: ToolInvocation) = MessageDto(
        role = Role.TOOL.wire,
        content = invocation.resultJson
            ?: """{"error":${JsonPrimitive(invocation.errorMessage ?: "failed")}}""",
        toolName = invocation.name,
    )

    /** Addressed to the model: it is the one that has to decide what to do about it. */
    private fun toolBudgetMessage(limit: Int): String =
        "Tool budget spent: this turn already used its $limit tool rounds, so no tool was run. " +
            "Answer now with what you have, and say plainly what is still unfinished."

    /**
     * Asks the model for a short thread title. Returns null on any failure, since a missing
     * title is cosmetic and must never surface as an error in the chat.
     *
     * [transcript] is a digest of the conversation so far, so a thread that has wandered away
     * from its opening question can be renamed to match what it actually became.
     *
     * [supportsThinking] shapes the request, not just the prompt. Ollama turns thinking *on* by
     * default for any model that has the capability, and a model that reasons first spends a
     * title-sized budget entirely on the reasoning: the content channel then arrives empty and
     * the thread is left called "New chat". Models without the capability are sent no `think`
     * field at all, since the only value they accept is the one that does nothing.
     */
    suspend fun suggestTitle(
        model: String,
        transcript: String,
        supportsThinking: Boolean = false,
    ): String? =
        runCatching {
            // Some reasoning models ignore `think: false` and narrate anyway; give them enough
            // room to get past it and still reach the title.
            val budget = if (supportsThinking) THINKING_TITLE_TOKEN_BUDGET else TITLE_TOKEN_BUDGET
            val request = ChatRequestDto(
                model = model,
                stream = true,
                messages = listOf(
                    MessageDto(
                        role = Role.SYSTEM.wire,
                        content = "You write short chat titles. Read the conversation and reply " +
                            "with a title of at most six words describing what it is about. " +
                            "Reply with the title alone: no quotes, no markdown, no reasoning, " +
                            "no punctuation at the end, no preamble.",
                    ),
                    MessageDto(
                        role = Role.USER.wire,
                        content = transcript.take(TITLE_SOURCE_CHARS),
                    ),
                ),
                // Only meaningful — and only accepted — where the model can actually think.
                think = if (supportsThinking) JsonPrimitive(false) else null,
                options = buildJsonObject {
                    put("temperature", JsonPrimitive(0.2))
                    put("num_predict", JsonPrimitive(budget))
                },
            )
            val builder = StringBuilder()
            client.streamChat(request).collect { chunk ->
                chunk.message?.content?.let(builder::append)
            }
            extractTitle(builder.toString())
        }.getOrNull()

    /**
     * Pulls a usable title out of whatever the model produced.
     *
     * Reasoning lands in its own field only when the server parsed it; a model whose template
     * emits raw `<think>` tags into the content would otherwise have its thread titled
     * "&lt;think&gt;". Anything still inside an unterminated tag is reasoning that ran out of
     * budget, so it is discarded rather than mistaken for an answer.
     */
    internal fun extractTitle(raw: String): String? {
        val closed = raw.replace(THINK_BLOCK, " ")
        // Whatever follows an unclosed opener is reasoning the budget cut short, not an answer.
        return THINK_OPEN.split(closed, limit = 2).first()
            .lineSequence()
            .map(::tidyTitleLine)
            .firstOrNull { it.isNotEmpty() }
            ?.take(MAX_TITLE_CHARS)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** Strips the wrapping a model reaches for anyway: quotes, markdown, a "Title:" label. */
    private fun tidyTitleLine(line: String): String {
        val unwrapped = line.trim().trim(*TITLE_TRIM_CHARS)
        return TITLE_LABEL.replace(unwrapped, "").trim(*TITLE_TRIM_CHARS).trim()
    }

    private fun buildRequest(
        conversation: Conversation,
        messages: List<MessageDto>,
        settings: AppSettings,
        offerTools: Boolean = conversation.toolsEnabled,
    ): ChatRequestDto {
        val params = conversation.params
        return ChatRequestDto(
            model = conversation.model,
            messages = messages,
            stream = true,
            think = params.thinkMode.toJsonElement(),
            tools = if (offerTools) {
                toolRegistry.definitions(externalTools = conversation.toolsEnabled)
                    .takeIf { it.isNotEmpty() }
            } else {
                null
            },
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
        memoryBrief: String?,
    ): List<MessageDto> {
        val relevant = history.filter { it.role != Role.SYSTEM && it.errorMessage == null }
        val windowed = if (settings.contextMessageLimit > 0) {
            relevant.takeLast(settings.contextMessageLimit)
        } else {
            relevant
        }

        // One system message rather than two: plenty of chat templates only honour the first, and
        // a memory brief that silently vanishes is worse than one that was never sent.
        val system = listOfNotNull(
            conversation.systemPrompt?.takeIf { it.isNotBlank() },
            memoryBrief?.takeIf { it.isNotBlank() },
        ).joinToString("\n\n")

        return buildList {
            if (system.isNotBlank()) {
                add(MessageDto(role = Role.SYSTEM.wire, content = system))
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
        /** How many times a round that produced nothing may be re-issued after a dropped stream. */
        const val MAX_SILENT_RETRIES = 2
        const val RETRY_BACKOFF_MS = 500L

        const val MAX_DOCUMENT_CHARS = 100_000
        const val TITLE_SOURCE_CHARS = 2_000
        const val TITLE_TOKEN_BUDGET = 24

        /** Room for a reasoning model to narrate first and still land on a title. */
        const val THINKING_TITLE_TOKEN_BUDGET = 320
        const val MAX_TITLE_CHARS = 60

        val THINK_BLOCK = Regex("<think(?:ing)?>.*?</think(?:ing)?>", RegexOption.DOT_MATCHES_ALL)
        val THINK_OPEN = Regex("<think(?:ing)?>")
        val TITLE_LABEL = Regex("^title\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
        val TITLE_TRIM_CHARS = charArrayOf('"', '\'', '*', '#', '`', '-', '.', '>', ' ', '\t')
    }
}
