package dev.klaiber.cirrus.di

import javax.inject.Qualifier

/** A [kotlinx.coroutines.CoroutineScope] that lives as long as the process. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * The HTTP client for github.com.
 *
 * Deliberately separate from the Ollama client: that one attaches the Ollama API key to every
 * request it makes, and that key must never be sent to a third-party host.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitHubHttp
