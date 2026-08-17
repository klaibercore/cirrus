package dev.klaiber.cirrus.data.remote.github

/**
 * Snapshot of the GitHub token that OkHttp needs synchronously.
 *
 * Mirrors [dev.klaiber.cirrus.data.remote.ApiCredentials]: the token is persisted encrypted
 * behind suspending reads, but an interceptor runs on a blocking thread and cannot suspend, so a
 * coroutine keeps this volatile copy in step.
 */
class GitHubCredentials {

    @Volatile
    var token: String? = null
        private set

    /**
     * Whether tools that change something on GitHub may run.
     *
     * Held here rather than read per call so the gate is one obvious flag: opening an issue or
     * posting a review is not something a model should be able to do because a prompt asked
     * nicely.
     */
    @Volatile
    var writesAllowed: Boolean = false
        private set

    /**
     * Where the API lives. Constant in production; overridden by tests to point at a mock
     * server, which is why it is here rather than being an injected constructor parameter.
     */
    @Volatile
    var apiBaseUrl: String = API_BASE_URL

    fun update(token: String?, writesAllowed: Boolean) {
        this.token = token?.takeIf { it.isNotBlank() }
        this.writesAllowed = writesAllowed
    }

    val isConfigured: Boolean get() = token != null

    companion object {
        const val API_BASE_URL = "https://api.github.com"
    }
}
