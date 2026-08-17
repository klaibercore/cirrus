package dev.klaiber.cirrus.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCredentialsTest {

    @Test
    fun `normalizeBaseUrl - trims trailing slashes`() {
        assertEquals("https://ollama.com", ApiCredentials.normalizeBaseUrl("https://ollama.com/"))
    }

    @Test
    fun `normalizeBaseUrl - removes trailing api segment`() {
        assertEquals("https://ollama.com", ApiCredentials.normalizeBaseUrl("https://ollama.com/api"))
        assertEquals("https://ollama.com", ApiCredentials.normalizeBaseUrl("https://ollama.com/api/"))
    }

    @Test
    fun `normalizeBaseUrl - adds scheme when missing`() {
        assertEquals("https://ollama.com", ApiCredentials.normalizeBaseUrl("ollama.com"))
        assertEquals("http://localhost:11434", ApiCredentials.normalizeBaseUrl("http://localhost:11434"))
    }

    @Test
    fun `normalizeBaseUrl - empty falls back to default`() {
        assertEquals("https://ollama.com", ApiCredentials.normalizeBaseUrl(""))
        assertEquals("https://ollama.com", ApiCredentials.normalizeBaseUrl("   "))
    }

    @Test
    fun `isCloudHost`() {
        val credentials = ApiCredentials()
        credentials.update(null, "https://ollama.com")
        assertTrue(credentials.isCloudHost())
        credentials.update(null, "http://localhost:11434")
        assertFalse(credentials.isCloudHost())
    }

    @Test
    fun `isConfigured - cloud host needs a key`() {
        val credentials = ApiCredentials()
        credentials.update(null, "https://ollama.com")
        assertFalse(credentials.isConfigured())
        credentials.update("sk-test", "https://ollama.com")
        assertTrue(credentials.isConfigured())
    }

    @Test
    fun `isConfigured - local host needs no key`() {
        val credentials = ApiCredentials()
        credentials.update(null, "http://localhost:11434")
        assertTrue(credentials.isConfigured())
    }

    @Test
    fun `update - blank key is treated as absent`() {
        val credentials = ApiCredentials()
        credentials.update("   ", "https://ollama.com")
        assertFalse(credentials.isConfigured())
    }
}
