package dev.klaiber.cirrus.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationParamsTest {

    @Test
    fun `hasOverrides - false by default`() {
        assertFalse(GenerationParams.Default.hasOverrides)
    }

    @Test
    fun `hasOverrides - true when any option is set`() {
        assertTrue(GenerationParams(temperature = 0.7f).hasOverrides)
        assertTrue(GenerationParams(stop = listOf("END")).hasOverrides)
        assertTrue(GenerationParams(responseFormat = "json").hasOverrides)
        assertTrue(GenerationParams(keepAlive = "5m").hasOverrides)
    }

    @Test
    fun `thinkMode wire values`() {
        assertNull(ThinkMode.OFF.wireValue())
        assertEquals(true, ThinkMode.ON.wireValue())
        assertEquals("low", ThinkMode.LOW.wireValue())
        assertEquals("medium", ThinkMode.MEDIUM.wireValue())
        assertEquals("high", ThinkMode.HIGH.wireValue())
        assertEquals("max", ThinkMode.MAX.wireValue())
    }
}
