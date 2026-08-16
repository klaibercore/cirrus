package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.data.remote.OllamaException

/**
 * The one place a failure becomes a sentence.
 *
 * Both the chat screen and the turn running behind it have to explain the same failures, and a
 * turn now outlives the screen that started it — so the wording cannot live in the ViewModel.
 * Every branch names what the user can do about it; "unknown error" helps nobody.
 */
fun Throwable.userMessage(): String = when (this) {
    is OllamaException.MissingApiKey -> "Add your Ollama API key in Settings to start chatting."
    is OllamaException.Unauthorized -> "The API key was rejected. Check it in Settings."
    is OllamaException.RateLimited -> retryAfterSeconds
        ?.let { "Rate limited. Try again in ${it}s." }
        ?: "Rate limited. Try again shortly."
    is OllamaException.ModelNotFound -> "\"$model\" is not available on this host."
    is OllamaException.Truncated ->
        "The reply was cut off before it finished. Regenerate it to pick up the rest."
    is OllamaException.Network -> "Network error: $message"
    else -> message ?: "Something went wrong."
}
