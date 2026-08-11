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

/**
 * The HTTP client for MCP servers.
 *
 * Attaches no credential of its own. An MCP server is an arbitrary third-party host chosen by the
 * user, and each one carries its own token, so the transport sets `Authorization` per request.
 * Reusing the GitHub client would be actively wrong: its interceptor *replaces* that header with
 * the user's GitHub PAT, which would both break per-server auth and hand the PAT to every MCP
 * server attached.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class McpHttp
