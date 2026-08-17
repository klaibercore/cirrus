package dev.klaiber.cirrus.domain.spotify

import com.sun.net.httpserver.HttpServer
import dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** What came back from Spotify, or why nothing did. */
sealed interface SpotifyRedirect {
    data class Code(val code: String, val state: String) : SpotifyRedirect

    /** Spotify itself refused — the user pressed Cancel, or the client id is wrong. */
    data class Denied(val reason: String) : SpotifyRedirect

    data class Failed(val reason: String) : SpotifyRedirect
}

/**
 * Catches the browser coming back from Spotify's consent screen.
 *
 * The Android build registers the `cirrus://` scheme and lets the OS deliver the redirect as an
 * intent. A desktop app has no equivalent it can claim without writing itself into the system's
 * handler table, so this is the loopback redirect the OAuth spec recommends for native apps: a
 * server on a fixed port, up only for the length of one sign-in, listening for exactly one request.
 *
 * The JDK's own `HttpServer` rather than a dependency — this serves one route, once, to a browser
 * that is already on the machine.
 */
class SpotifyRedirectListener {

    /**
     * Opens the browser at [authorizeUrl] and waits for the redirect.
     *
     * The listener is started *before* the browser, or a fast consent (an already-approved app
     * bounces straight back) can arrive at a port nothing is listening on yet. It is always shut
     * down: a port left bound would make the next sign-in fail with an error about the port rather
     * than about Spotify.
     */
    suspend fun awaitRedirect(authorizeUrl: String): SpotifyRedirect {
        val arrival = CompletableDeferred<SpotifyRedirect>()

        val server = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", SpotifyCredentials.REDIRECT_PORT), 0)
        } catch (failure: Exception) {
            return SpotifyRedirect.Failed(
                "Cirrus could not listen on port ${SpotifyCredentials.REDIRECT_PORT}, which is " +
                    "where Spotify sends you back to. Close whatever is using it and try again.",
            )
        }

        server.createContext(SpotifyCredentials.REDIRECT_PATH) { exchange ->
            val params = exchange.requestURI.rawQuery.orEmpty().splitQuery()
            val result = when {
                params["error"] != null -> SpotifyRedirect.Denied(
                    when (params["error"]) {
                        "access_denied" -> "You declined the Spotify sign-in."
                        else -> "Spotify refused the sign-in: ${params["error"]}."
                    },
                )

                params["code"] != null && params["state"] != null ->
                    SpotifyRedirect.Code(params.getValue("code"), params.getValue("state"))

                else -> SpotifyRedirect.Failed("Spotify sent Cirrus back without an authorization code.")
            }

            val body = closingPage(result).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            arrival.complete(result)
        }

        server.start()
        return try {
            if (!openBrowser(authorizeUrl)) {
                return SpotifyRedirect.Failed(
                    "Cirrus could not open a browser. Sign in at:\n$authorizeUrl",
                )
            }
            withTimeout(SIGN_IN_TIMEOUT_MS) { arrival.await() }
        } catch (timeout: TimeoutCancellationException) {
            SpotifyRedirect.Failed("The Spotify sign-in was not finished in time.")
        } finally {
            server.stop(0)
        }
    }

    private fun openBrowser(url: String): Boolean = runCatching {
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() } ?: return false
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
        desktop.browse(URI(url))
        true
    }.getOrElse { false }

    /**
     * What the browser tab is left showing.
     *
     * Plain and self-closing rather than nothing at all: a blank page after a consent screen reads
     * as a failure, and the user's next move would be to try the sign-in again.
     */
    private fun closingPage(result: SpotifyRedirect): String {
        val message = when (result) {
            is SpotifyRedirect.Code -> "Spotify is connected. You can close this tab and go back to Cirrus."
            is SpotifyRedirect.Denied -> result.reason
            is SpotifyRedirect.Failed -> result.reason
        }
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>Cirrus</title></head>
            <body style="font-family: system-ui, sans-serif; display: grid; place-items: center;
                         height: 100vh; margin: 0; background: #fff; color: #111;">
              <main style="max-width: 32rem; text-align: center;">
                <h1 style="font-size: 1.25rem; font-weight: 600;">Cirrus</h1>
                <p>$message</p>
              </main>
            </body></html>
        """.trimIndent()
    }

    /** Spotify percent-encodes the code, and a raw `+` in one would otherwise become a space. */
    private fun String.splitQuery(): Map<String, String> = split("&")
        .mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8)
            key to value
        }
        .toMap()

    private companion object {
        /** Long enough to find a password manager, short enough that a dead trip gives up. */
        const val SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
