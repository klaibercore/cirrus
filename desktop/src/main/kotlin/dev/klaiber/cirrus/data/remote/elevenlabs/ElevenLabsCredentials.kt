package dev.klaiber.cirrus.data.remote.elevenlabs

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the ElevenLabs key that OkHttp needs synchronously.
 *
 * Mirrors [dev.klaiber.cirrus.data.remote.github.GitHubCredentials]: the key is persisted behind
 * a suspending read, and an interceptor cannot suspend.
 */
@Singleton
class ElevenLabsCredentials @Inject constructor() {

    @Volatile
    var apiKey: String? = null
        private set

    /** Overridden by tests to point the client at a mock server. */
    @Volatile
    var apiBaseUrl: String = API_BASE_URL

    fun update(apiKey: String?) {
        this.apiKey = apiKey?.takeIf { it.isNotBlank() }
    }

    val isConfigured: Boolean get() = apiKey != null

    companion object {
        const val API_BASE_URL = "https://api.elevenlabs.io"
    }
}
