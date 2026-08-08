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
