package dev.klaiber.cirrus.domain.model

/**
 * A model exposed by the configured Ollama host.
 *
 * Capabilities are inferred from the model name because `/api/tags` on the cloud host reports
 * only naming and size metadata. This keeps the picker useful without a per-model round trip.
 */
data class ModelInfo(
    val name: String,
    val sizeBytes: Long,
    val parameterSize: String?,
    val quantization: String?,
    val family: String?,
    val modifiedAt: String?,
) {
    val supportsThinking: Boolean get() = THINKING_PATTERNS.any { it in name.lowercase() }

    val supportsVision: Boolean get() = VISION_PATTERNS.any { it in name.lowercase() }

    /** Ollama's cloud-hosted models are suffixed `-cloud` or served without a local size tag. */
    val isCloudHosted: Boolean get() = name.endsWith("-cloud")

    val displayName: String get() = name.removeSuffix("-cloud")

    /** Human-readable size, or null for cloud models that report a zero/unknown size. */
    val displaySize: String?
        get() = when {
            sizeBytes <= 0L -> null
            sizeBytes >= 1_000_000_000_000L -> "%.1f TB".format(sizeBytes / 1_000_000_000_000.0)
            sizeBytes >= 1_000_000_000L -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            else -> "%.0f MB".format(sizeBytes / 1_000_000.0)
        }

    private companion object {
        val THINKING_PATTERNS = listOf(
            "gpt-oss", "qwen3", "qwen3.5", "deepseek-v3", "deepseek-v4", "deepseek-r1",
            "glm-4", "glm-5", "minimax", "kimi", "nemotron", "magistral",
        )
        val VISION_PATTERNS = listOf(
            "llava", "bakllava", "moondream", "llama3.2-vision", "llama4", "gemma3",
            "gemma4", "qwen2.5vl", "qwen3-vl", "minicpm-v", "mistral-small3", "pixtral",
        )
    }
}
