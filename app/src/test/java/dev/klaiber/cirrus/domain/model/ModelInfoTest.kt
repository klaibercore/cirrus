package dev.klaiber.cirrus.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInfoTest {

    private fun model(name: String, size: Long = 0L) = ModelInfo(
        name = name,
        sizeBytes = size,
        parameterSize = null,
        quantization = null,
        family = null,
        modifiedAt = null,
    )

    @Test
    fun `supportsThinking - known thinking models`() {
        assertTrue(model("qwen3:8b").supportsThinking)
        assertTrue(model("deepseek-r1:7b").supportsThinking)
        assertTrue(model("gpt-oss:20b").supportsThinking)
    }

    @Test
    fun `supportsThinking - non thinking models`() {
        assertFalse(model("llama3.2:3b").supportsThinking)
        assertFalse(model("mistral:7b").supportsThinking)
    }

    @Test
    fun `supportsVision - known vision models`() {
        assertTrue(model("llava:7b").supportsVision)
        assertTrue(model("llama3.2-vision:11b").supportsVision)
        assertTrue(model("qwen2.5vl:7b").supportsVision)
    }

    @Test
    fun `supportsVision - non vision models`() {
        assertFalse(model("qwen3:8b").supportsVision)
    }

    @Test
    fun `reported capabilities win over the name guess`() {
        // The name says "reasoning"; the server says vision and tools only.
        val reported = model("qwen3:8b").copy(
            reportedCapabilities = setOf(
                ModelCapability.COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOLS,
            ),
        )
        assertFalse(reported.supportsThinking)
        assertTrue(reported.supportsVision)
        assertTrue(reported.supportsTools)
        assertTrue(reported.hasVerifiedCapabilities)
    }

    @Test
    fun `capabilities fall back to the name guess until the server answers`() {
        val guessed = model("qwen3:8b")
        assertFalse(guessed.hasVerifiedCapabilities)
        assertTrue(guessed.supportsThinking)
        // Tools are never guessed from a name; only /api/show can confirm them.
        assertFalse(guessed.supportsTools)
    }

    @Test
    fun `badges drop completion and sort vision before reasoning`() {
        val reported = model("m").copy(
            reportedCapabilities = setOf(
                ModelCapability.THINKING,
                ModelCapability.COMPLETION,
                ModelCapability.VISION,
            ),
        )
        assertEquals(listOf(ModelCapability.VISION, ModelCapability.THINKING), reported.badges)
    }

    @Test
    fun `displayContextLength`() {
        assertNull(model("m").displayContextLength)
        assertNull(model("m").copy(contextLength = 0).displayContextLength)
        assertEquals("128K context", model("m").copy(contextLength = 131_072).displayContextLength)
        assertEquals("256K context", model("m").copy(contextLength = 262_144).displayContextLength)
        assertEquals("1M context", model("m").copy(contextLength = 1_048_576).displayContextLength)
        assertEquals("512 context", model("m").copy(contextLength = 512).displayContextLength)
    }

    @Test
    fun `baseName and tag split the identifier`() {
        assertEquals("qwen3", model("qwen3:8b").baseName)
        assertEquals("8b", model("qwen3:8b").tag)
        assertNull(model("qwen3").tag)
        // The -cloud suffix belongs to the tag, so it drops out of the headline too.
        assertEquals("qwen3-coder", model("qwen3-coder:480b-cloud").baseName)
    }

    @Test
    fun `isCloudHosted follows the remote host when the server reports one`() {
        assertTrue(model("gemma3:27b").copy(remoteHost = "https://ollama.com").isCloudHosted)
        assertFalse(model("gemma3:27b").isCloudHosted)
    }

    @Test
    fun `isCloudHosted and displayName`() {
        assertTrue(model("qwen3-cloud").isCloudHosted)
        assertFalse(model("qwen3:8b").isCloudHosted)
        assertEquals("qwen3", model("qwen3-cloud").displayName)
        assertEquals("qwen3:8b", model("qwen3:8b").displayName)
    }

    @Test
    fun `displaySize`() {
        assertNull(model("qwen3-cloud").displaySize)
        assertEquals("500 MB", model("m", 500_000_000).displaySize)
        assertEquals("2.0 GB", model("m", 2_000_000_000).displaySize)
        assertEquals("1.5 TB", model("m", 1_500_000_000_000).displaySize)
    }
}
