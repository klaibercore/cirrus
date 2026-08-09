package dev.klaiber.cirrus.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's facets, over a catalogue mixing verified and unverified capabilities. */
class ModelFilterTest {

    private fun model(
        name: String,
        capabilities: Set<ModelCapability>? = null,
        remoteHost: String? = null,
    ) = ModelInfo(
        name = name,
        sizeBytes = 1_000_000_000L,
        parameterSize = null,
        quantization = null,
        family = null,
        modifiedAt = null,
        reportedCapabilities = capabilities,
        remoteHost = remoteHost,
    )

    private val visionModel = model("llava:13b", setOf(ModelCapability.VISION))
    private val toolsModel = model("qwen3:8b", setOf(ModelCapability.COMPLETION, ModelCapability.TOOLS))
    private val thinkingModel = model("deepseek-r1:7b", setOf(ModelCapability.THINKING))
    private val plainModel = model("llama3.2:3b", setOf(ModelCapability.COMPLETION))
    private val cloudModel = model(
        name = "gpt-oss:120b-cloud",
        capabilities = setOf(ModelCapability.TOOLS),
        remoteHost = "https://ollama.com",
    )

    private val catalogue = listOf(visionModel, toolsModel, thinkingModel, plainModel, cloudModel)

    @Test
    fun `ALL keeps everything`() {
        assertEquals(catalogue, ModelFilter.apply(catalogue, ModelFilter.ALL))
    }

    @Test
    fun `VISION keeps only models that see`() {
        assertEquals(listOf(visionModel), ModelFilter.apply(catalogue, ModelFilter.VISION))
    }

    @Test
    fun `TOOLS keeps only models that call tools`() {
        assertEquals(listOf(toolsModel, cloudModel), ModelFilter.apply(catalogue, ModelFilter.TOOLS))
    }

    @Test
    fun `THINKING keeps only reasoning models`() {
        assertEquals(listOf(thinkingModel), ModelFilter.apply(catalogue, ModelFilter.THINKING))
    }

    @Test
    fun `CLOUD and LOCAL partition the catalogue`() {
        val cloud = ModelFilter.apply(catalogue, ModelFilter.CLOUD)
        val local = ModelFilter.apply(catalogue, ModelFilter.LOCAL)

        assertEquals(listOf(cloudModel), cloud)
        assertEquals(catalogue.size, cloud.size + local.size)
        assertTrue(local.none { it.isCloudHosted })
    }

    @Test
    fun `a facet nobody satisfies is not offered`() {
        val available = ModelFilter.available(listOf(plainModel))

        assertEquals(listOf(ModelFilter.ALL, ModelFilter.LOCAL), available)
    }

    @Test
    fun `ALL is offered even for an empty catalogue`() {
        assertEquals(listOf(ModelFilter.ALL), ModelFilter.available(emptyList()))
    }

    @Test
    fun `filters fall back to name-derived guesses before the server has answered`() {
        // reportedCapabilities is null here: /api/show has not come back yet, but the picker is
        // already on screen and the vision facet still has to do something sensible.
        val unverified = model("llava:13b", capabilities = null)

        assertTrue(ModelFilter.VISION.matches(unverified))
        assertTrue(!unverified.hasVerifiedCapabilities)
    }
}
