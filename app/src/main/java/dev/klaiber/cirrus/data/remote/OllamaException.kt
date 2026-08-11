package dev.klaiber.cirrus.data.remote

import java.io.IOException

/**
 * Failures the UI needs to distinguish. Anything the user can act on (missing key, rate limit,
 * wrong model) gets its own type so the chat screen can offer the right recovery action.
 */
sealed class OllamaException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    /** No API key is configured yet; the user must complete onboarding. */
    class MissingApiKey : OllamaException("No API key configured.")

    /** The server rejected the key (401/403). */
    class Unauthorized(detail: String?) : OllamaException(
        detail ?: "API key was rejected. Check it in Settings.",
    )

    /** Pro-plan quota or per-minute limit exhausted (429). */
    class RateLimited(detail: String?, val retryAfterSeconds: Long?) : OllamaException(
        detail ?: "Rate limit reached. Try again shortly.",
    )

    /** The requested model does not exist on this host (404). */
    class ModelNotFound(val model: String, detail: String?) : OllamaException(
        detail ?: "Model \"$model\" is not available on this host.",
    )

    /** Any other non-2xx response. */
    class ServerError(val code: Int, detail: String?) : OllamaException(
        detail ?: "Server returned HTTP $code.",
    )

    /** DNS/TLS/socket problems, including an unreachable local Ollama instance. */
    class Network(cause: Throwable) : OllamaException(
        cause.message ?: "Network error.",
        cause,
    )

    /** A stream line or response body that did not parse as expected. */
    class Malformed(detail: String, cause: Throwable? = null) : OllamaException(detail, cause)

    /**
     * The stream ended without the terminal chunk, so the answer is incomplete.
     *
     * Distinct from [Network] because it is recoverable in a way a dead socket is not: a round
     * that has produced nothing yet can simply be re-issued.
     */
    class Truncated : OllamaException("The response was cut off before it finished.")
}
