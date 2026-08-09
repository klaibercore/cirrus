package dev.klaiber.cirrus.data.remote

import dev.klaiber.cirrus.data.remote.dto.ShowResponseDto
import dev.klaiber.cirrus.domain.model.ModelCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** The facts `/api/show` yields about one model, reduced to what the picker renders. */
data class DetectedCapabilities(
    val capabilities: Set<ModelCapability>,
    val contextLength: Int?,
    val family: String?,
    val parameterSize: String?,
    val quantization: String?,
    val remoteHost: String?,
)

/**
 * Why detection failed, so a caller can tell "this host has no such route" from "this one model
 * answered with something we could not read" and decide whether to keep asking about the rest.
 */
sealed class CapabilityDetectionError(message: String) : Exception(message) {
    /** The body was not JSON, or not a JSON object. */
    class Malformed(detail: String) :
        CapabilityDetectionError("`/api/show` returned something that is not JSON: $detail")

    /** The host answered `{"error": "..."}` rather than a manifest. */
    class Rejected(val detail: String) : CapabilityDetectionError("`/api/show` refused: $detail")
}

/**
 * Turns an `/api/show` body into [DetectedCapabilities].
 *
 * Split out of [dev.klaiber.cirrus.data.repository.ModelRepository] because this mapping is the
 * one piece of the capability-aware picker most exposed to Ollama changing its wire shape, and it
 * is worth pinning against fixtures rather than only exercising through a repository.
 *
 * Parsing is deliberately defensive at every step. `capabilities` may name a capability this
 * build has never heard of, `model_info` keys are architecture-prefixed and so cannot be looked
 * up by a fixed name, and every block in the response is optional on some host or another. A
 * field that cannot be read degrades to null instead of failing the whole detection, because a
 * missing context length is worth far less than a model card that does not render at all.
 *
 * Fixtures in `app/src/test/resources/api_show_responses/` mirror the shape Ollama 0.32.x
 * returns from `POST /api/show`; see the README there for how each was derived.
 */
@Singleton
class ModelCapabilityDetector @Inject constructor(
    private val json: Json,
) {

    /** Parses a raw response body. Never throws — the failure is returned. */
    fun detect(body: String): Result<DetectedCapabilities> {
        if (body.isBlank()) {
            return Result.failure(CapabilityDetectionError.Malformed("empty body"))
        }

        // An error body deserializes cleanly into a DTO of all-default fields, which would look
        // like a model that can do nothing at all. Check for it before trusting the parse.
        val element = runCatching { json.parseToJsonElement(body) }.getOrElse { failure ->
            return Result.failure(
                CapabilityDetectionError.Malformed(failure.message ?: "unparseable"),
            )
        }
        val obj = element as? kotlinx.serialization.json.JsonObject
            ?: return Result.failure(CapabilityDetectionError.Malformed("body was not an object"))

        (obj["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { detail ->
            return Result.failure(CapabilityDetectionError.Rejected(detail))
        }

        val dto = runCatching { json.decodeFromString(ShowResponseDto.serializer(), body) }
            .getOrElse { failure ->
                return Result.failure(
                    CapabilityDetectionError.Malformed(failure.message ?: "unexpected shape"),
                )
            }
        return Result.success(detect(dto))
    }

    /** Maps an already-deserialized manifest. */
    fun detect(dto: ShowResponseDto): DetectedCapabilities = DetectedCapabilities(
        // Unknown wire values are dropped rather than shown raw, so a newer server degrades to
        // "not displayed" instead of putting a bare identifier on a card.
        capabilities = dto.capabilities.mapNotNull(ModelCapability::fromWire).toSet(),
        contextLength = dto.contextLength(),
        family = dto.details?.family?.takeIf { it.isNotBlank() },
        parameterSize = dto.details?.parameterSize?.takeIf { it.isNotBlank() },
        quantization = dto.details?.quantizationLevel?.takeIf { it.isNotBlank() },
        remoteHost = dto.remoteHost?.takeIf { it.isNotBlank() },
    )

    /** `model_info` keys are architecture-prefixed, e.g. `qwen3.context_length`. */
    private fun ShowResponseDto.contextLength(): Int? = modelInfo
        ?.entries
        ?.firstOrNull { (key, _) -> key.endsWith(CONTEXT_LENGTH_SUFFIX) }
        ?.value
        ?.let { it as? JsonPrimitive }
        ?.intOrNull

    private companion object {
        const val CONTEXT_LENGTH_SUFFIX = ".context_length"
    }
}
