package dev.klaiber.cirrus.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire model for `POST /api/chat`.
 *
 * `think` and `format` are [JsonElement] because Ollama accepts more than one shape for each:
 * `think` is a boolean or an effort string, and `format` is the string "json" or a JSON schema.
 */
@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<MessageDto>,
    val stream: Boolean = true,
    val think: JsonElement? = null,
    val tools: List<JsonElement>? = null,
    val format: JsonElement? = null,
    val options: JsonObject? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
)

@Serializable
data class MessageDto(
    val role: String,
    val content: String = "",
    val thinking: String? = null,
    /** Base64-encoded image payloads, without a data-URI prefix. */
    val images: List<String>? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_name") val toolName: String? = null,
)

@Serializable
data class ToolCallDto(
    val function: ToolCallFunctionDto,
)

@Serializable
data class ToolCallFunctionDto(
    val name: String,
    /** Ollama emits arguments as a JSON object, unlike OpenAI's stringified form. */
    val arguments: JsonObject = JsonObject(emptyMap()),
)

/**
 * One NDJSON line from a streaming chat response. Non-final chunks carry deltas in
 * [message]; the final chunk sets `done` and adds the timing counters.
 */
@Serializable
data class ChatChunkDto(
    val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val message: MessageDto? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDuration: Long? = null,
    /** Present when the server reports a failure mid-stream instead of an HTTP error. */
    val error: String? = null,
)

@Serializable
data class TagsResponseDto(
    val models: List<TagModelDto> = emptyList(),
)

@Serializable
data class TagModelDto(
    val name: String,
    val model: String? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val size: Long = 0L,
    val digest: String? = null,
    val details: ModelDetailsDto? = null,
)

@Serializable
data class ModelDetailsDto(
    val family: String? = null,
    val families: List<String>? = null,
    val format: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
)

@Serializable
data class ShowRequestDto(
    val model: String,
)

/**
 * Wire model for `POST /api/show`, trimmed to the fields the picker needs.
 *
 * [modelInfo] is a loose map because its keys are architecture-prefixed
 * (`qwen3.context_length`, `llama.context_length`, ...) and vary per model.
 */
@Serializable
data class ShowResponseDto(
    val capabilities: List<String> = emptyList(),
    val details: ModelDetailsDto? = null,
    @SerialName("model_info") val modelInfo: JsonObject? = null,
    @SerialName("remote_model") val remoteModel: String? = null,
    @SerialName("remote_host") val remoteHost: String? = null,
)

@Serializable
data class WebSearchRequestDto(
    val query: String,
    @SerialName("max_results") val maxResults: Int? = null,
)

@Serializable
data class WebSearchResponseDto(
    val results: List<WebSearchResultDto> = emptyList(),
)

@Serializable
data class WebSearchResultDto(
    val title: String = "",
    val url: String = "",
    val content: String = "",
)

@Serializable
data class WebFetchRequestDto(
    val url: String,
)

@Serializable
data class WebFetchResponseDto(
    val title: String? = null,
    val content: String? = null,
    val links: List<String> = emptyList(),
)

/** Ollama's error envelope, returned as `{"error": "..."}` alongside a non-2xx status. */
@Serializable
data class ErrorResponseDto(
    val error: String? = null,
)
