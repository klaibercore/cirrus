package dev.klaiber.cirrus.domain.model

import kotlinx.serialization.Serializable

/**
 * How much reasoning budget a model should spend before answering.
 *
 * Ollama accepts two shapes for `think`: a boolean (Qwen, DeepSeek) or a named effort level
 * (gpt-oss only understands the levels). [OFF] omits the field entirely so that models without
 * any thinking capability are not sent an argument they will reject.
 */
enum class ThinkMode(val label: String) {
    OFF("Off"),
    ON("On"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    MAX("Max"),
    ;

    /** The wire value for `ChatRequest.think`, or null when the field should be omitted. */
    fun wireValue(): Any? = when (this) {
        OFF -> null
        ON -> true
        LOW -> "low"
        MEDIUM -> "medium"
        HIGH -> "high"
        MAX -> "max"
    }
}

/**
 * Sampling and context configuration for a single request.
 *
 * Every field is nullable on purpose: a null means "do not send this option", which lets the
 * server apply the model's own default rather than a value this app invented.
 */
@Serializable
data class GenerationParams(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    val repeatPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val seed: Int? = null,
    val numCtx: Int? = null,
    val numPredict: Int? = null,
    val stop: List<String> = emptyList(),
    val thinkMode: ThinkMode = ThinkMode.OFF,
    /** A JSON schema (or the literal string "json") sent as `format` for structured output. */
    val responseFormat: String? = null,
    val keepAlive: String? = null,
) {
    /** True when any option deviates from the server defaults, used to badge the UI. */
    val hasOverrides: Boolean
        get() = temperature != null || topP != null || topK != null || minP != null ||
            repeatPenalty != null || presencePenalty != null || frequencyPenalty != null ||
            seed != null || numCtx != null || numPredict != null || stop.isNotEmpty() ||
            responseFormat != null || keepAlive != null

    companion object {
        val Default = GenerationParams()
    }
}
