package dev.klaiber.cirrus.domain.model

/**
 * A model exposed by the configured Ollama host.
 *
 * `/api/tags` reports only naming and size metadata, so capabilities arrive later from
 * `/api/show` ([reportedCapabilities]). Until then — or on a host that has no such route — they
 * are inferred from the model name, which keeps the picker useful without blocking on a round
 * trip per model.
 */
data class ModelInfo(
    val name: String,
    val sizeBytes: Long,
    val parameterSize: String?,
    val quantization: String?,
    val family: String?,
    val modifiedAt: String?,
    /** Straight from `/api/show`. Null means "not answered yet", not "no capabilities". */
    val reportedCapabilities: Set<ModelCapability>? = null,
    val contextLength: Int? = null,
    /** Set for models the host proxies to Ollama's cloud rather than running locally. */
    val remoteHost: String? = null,
) {
    /** Server-reported capabilities when we have them, name-derived guesses otherwise. */
    val capabilities: Set<ModelCapability>
        get() = reportedCapabilities ?: inferredCapabilities

    /** True once the server has told us what this model can do, rather than us guessing. */
    val hasVerifiedCapabilities: Boolean get() = reportedCapabilities != null

    val supportsThinking: Boolean get() = ModelCapability.THINKING in capabilities

    val supportsVision: Boolean get() = ModelCapability.VISION in capabilities

    val supportsTools: Boolean get() = ModelCapability.TOOLS in capabilities

    val supportsAudio: Boolean get() = ModelCapability.AUDIO in capabilities

    /** Ollama's cloud-hosted models are suffixed `-cloud`; `/api/show` also names the host. */
    val isCloudHosted: Boolean get() = remoteHost != null || name.endsWith("-cloud")

    val displayName: String get() = name.removeSuffix("-cloud")

    /** The `:tag` part, shown under the name so long identifiers stay readable. */
    val tag: String? get() = name.substringAfter(':', "").takeIf { it.isNotEmpty() }

    /** Name without its tag, for the card headline. */
    val baseName: String get() = displayName.substringBefore(':')

    /**
     * Capabilities worth putting on a card. `completion` is true of everything conversational,
     * so showing it would only add noise.
     */
    val badges: List<ModelCapability>
        get() = capabilities
            .filterNot { it == ModelCapability.COMPLETION }
            .sortedBy { BADGE_ORDER.indexOf(it).takeIf { index -> index >= 0 } ?: BADGE_ORDER.size }

    /**
     * Parameter count as people quote it, e.g. "8.2B".
     */
    val displayParameterSize: String?
        get() {
            val raw = parameterSize?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val count = raw.toLongOrNull() ?: return raw
            return when {
                count <= 0L -> null
                count >= 1_000_000_000_000L -> trimZero(count / 1_000_000_000_000.0) + "T"
                count >= 1_000_000_000L -> trimZero(count / 1_000_000_000.0) + "B"
                count >= 1_000_000L -> trimZero(count / 1_000_000.0) + "M"
                else -> count.toString()
            }
        }

    /** Human-readable size, or null for cloud models that report a zero/unknown size. */
    val displaySize: String?
        get() = when {
            sizeBytes <= 0L -> null
            sizeBytes >= 1_000_000_000_000L -> "%.1f TB".format(sizeBytes / 1_000_000_000_000.0)
            sizeBytes >= 1_000_000_000L -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            else -> "%.0f MB".format(sizeBytes / 1_000_000.0)
        }

    /** Context window in the units people actually quote, e.g. 262144 -> "256K context". */
    val displayContextLength: String?
        get() = contextLength?.takeIf { it > 0 }?.let { tokens ->
            when {
                tokens >= 1_048_576 -> "${tokens / 1_048_576}M context"
                tokens >= 1024 -> "${tokens / 1024}K context"
                else -> "$tokens context"
            }
        }

    private val inferredCapabilities: Set<ModelCapability>
        get() = buildSet {
            add(ModelCapability.COMPLETION)
            if (mayThink(name)) add(ModelCapability.THINKING)
            if (VISION_PATTERNS.any { it in name.lowercase() }) add(ModelCapability.VISION)
        }

    /**
     * One decimal place, but "1B" rather than "1.0B".
     */
    private fun trimZero(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.1f", value).removeSuffix(".0")

    companion object {
        /**
         * Whether a model is likely to reason before answering, judged from its name alone.
         */
        fun mayThink(name: String): Boolean =
            name.lowercase().let { lowercase -> THINKING_PATTERNS.any { it in lowercase } }

        private val BADGE_ORDER = listOf(
            ModelCapability.VISION,
            ModelCapability.THINKING,
            ModelCapability.TOOLS,
            ModelCapability.AUDIO,
            ModelCapability.IMAGE,
            ModelCapability.INSERT,
            ModelCapability.EMBEDDING,
        )
        private val THINKING_PATTERNS = listOf(
            "gpt-oss", "qwen3", "qwen3.5", "deepseek-v3", "deepseek-v4", "deepseek-r1",
            "glm-4", "glm-5", "minimax", "kimi", "nemotron", "magistral",
        )
        private val VISION_PATTERNS = listOf(
            "llava", "bakllava", "moondream", "llama3.2-vision", "llama4", "gemma3",
            "gemma4", "qwen2.5vl", "qwen3-vl", "minicpm-v", "mistral-small3", "pixtral",
        )
    }
}
