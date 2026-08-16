package dev.klaiber.cirrus.data.remote

import dev.klaiber.cirrus.domain.model.ModelCapability
import dev.klaiber.cirrus.domain.model.ModelInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `/api/show` mapping against recorded response shapes.
 *
 * The capability-aware picker is the feature most exposed to Ollama changing its wire format, so
 * these read real bodies from `src/test/resources/api_show_responses/` rather than constructing
 * DTOs — a rename on the wire has to fail here.
 */
class ModelCapabilityDetectorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
        coerceInputValues = true
    }
    private val detector = ModelCapabilityDetector(json)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("api_show_responses/$name")) {
            "missing fixture: $name"
        }.bufferedReader().use { it.readText() }

    private fun detect(name: String): DetectedCapabilities =
        detector.detect(fixture(name)).getOrThrow()

    @Test
    fun `reports tools and thinking for a model that declares them`() {
        val detected = detect("qwen3_tools_thinking.json")

        assertTrue(ModelCapability.TOOLS in detected.capabilities)
        assertTrue(ModelCapability.THINKING in detected.capabilities)
        assertTrue(ModelCapability.VISION !in detected.capabilities)
        assertEquals(40960, detected.contextLength)
        assertEquals("qwen3", detected.family)
        assertEquals("8.2B", detected.parameterSize)
        assertEquals("Q4_K_M", detected.quantization)
    }

    @Test
    fun `reports vision for a vision model`() {
        val detected = detect("llama32_vision.json")

        assertTrue(ModelCapability.VISION in detected.capabilities)
        assertTrue(ModelCapability.TOOLS !in detected.capabilities)
        assertEquals(131072, detected.contextLength)
    }

    @Test
    fun `a text-only model reports completion and nothing else`() {
        val detected = detect("text_only.json")

        assertEquals(setOf(ModelCapability.COMPLETION), detected.capabilities)
        // `completion` is true of everything conversational, so it is never badged.
        assertEquals(emptyList<ModelCapability>(), modelWith(detected).badges)
    }

    @Test
    fun `context length is read from the architecture-prefixed key`() {
        val detected = detect("qwen2_32k.json")

        assertEquals(32768, detected.contextLength)
        assertEquals("32K context", modelWith(detected).displayContextLength)
    }

    @Test
    fun `a missing model_info block leaves the context length unknown`() {
        assertNull(detect("text_only.json").contextLength)
    }

    @Test
    fun `a cloud model is recognised and unknown capabilities are dropped`() {
        val detected = detect("gpt_oss_cloud.json")

        assertEquals("https://ollama.com", detected.remoteHost)
        assertTrue(modelWith(detected).isCloudHosted)
        // "telepathy" is not a capability this build knows; it must not reach the UI raw.
        assertEquals(
            setOf(ModelCapability.COMPLETION, ModelCapability.TOOLS, ModelCapability.THINKING),
            detected.capabilities,
        )
    }

    @Test
    fun `empty and null detail fields are tolerated`() {
        val detected = detect("embedding_minimal.json")

        assertEquals(setOf(ModelCapability.EMBEDDING), detected.capabilities)
        // Blank strings are normalised away so the card does not render an empty chip.
        assertNull(detected.family)
        assertNull(detected.parameterSize)
        assertNull(detected.quantization)
        assertNull(detected.contextLength)
        assertNull(detected.remoteHost)
    }

    @Test
    fun `an error body is reported as rejected rather than as a model with no capabilities`() {
        val failure = detector.detect(fixture("error_not_found.json")).exceptionOrNull()

        assertTrue(failure is CapabilityDetectionError.Rejected)
        assertEquals("model 'nope:latest' not found", (failure as CapabilityDetectionError.Rejected).detail)
    }

    @Test
    fun `malformed json fails instead of throwing`() {
        val failure = detector.detect("{\"capabilities\": [\"tools\"").exceptionOrNull()

        assertTrue(failure is CapabilityDetectionError.Malformed)
    }

    @Test
    fun `a non-object body fails`() {
        assertTrue(detector.detect("[1, 2, 3]").exceptionOrNull() is CapabilityDetectionError.Malformed)
    }

    @Test
    fun `an empty body fails`() {
        assertTrue(detector.detect("   ").exceptionOrNull() is CapabilityDetectionError.Malformed)
    }

    @Test
    fun `an html error page fails rather than parsing as a model`() {
        // A reverse proxy in front of Ollama answers 200 with HTML more often than you would like.
        val failure = detector.detect("<html><body>502</body></html>").exceptionOrNull()

        assertTrue(failure is CapabilityDetectionError.Malformed)
    }

    /** Wraps detection output in the domain object the picker actually renders. */
    private fun modelWith(detected: DetectedCapabilities): ModelInfo = ModelInfo(
        name = if (detected.remoteHost != null) "gpt-oss:120b-cloud" else "test-model",
        sizeBytes = 1_000_000_000L,
        parameterSize = detected.parameterSize,
        quantization = detected.quantization,
        family = detected.family,
        modifiedAt = null,
        reportedCapabilities = detected.capabilities,
        contextLength = detected.contextLength,
        remoteHost = detected.remoteHost,
    )
}
