package dev.klaiber.cirrus.domain.model

/**
 * The facets the model list can be narrowed by, in the order they appear as chips.
 *
 * Lives in the domain rather than beside the picker so the rule for "which models satisfy this
 * facet" can be tested without rendering a sheet.
 */
enum class ModelFilter(val label: String) {
    ALL("All"),
    VISION("Vision"),
    THINKING("Reasoning"),
    TOOLS("Tools"),
    AUDIO("Audio"),
    CLOUD("Cloud"),
    LOCAL("Local"),
    ;

    fun matches(model: ModelInfo): Boolean = when (this) {
        ALL -> true
        VISION -> model.supportsVision
        THINKING -> model.supportsThinking
        TOOLS -> model.supportsTools
        AUDIO -> model.supportsAudio
        CLOUD -> model.isCloudHosted
        LOCAL -> !model.isCloudHosted
    }

    companion object {
        /** Narrows a catalogue to one facet. */
        fun apply(models: List<ModelInfo>, filter: ModelFilter): List<ModelInfo> =
            models.filter(filter::matches)

        /**
         * The facets worth offering for a given catalogue.
         *
         * A facet nobody can satisfy is a dead end, so only those that would return something
         * are shown — [ALL] always is.
         */
        fun available(models: List<ModelInfo>): List<ModelFilter> =
            entries.filter { it == ALL || models.any(it::matches) }
    }
}
