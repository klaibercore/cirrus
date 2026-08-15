package dev.klaiber.cirrus.di

import dev.klaiber.cirrus.BuildConfig
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsCredentials
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(credentials: ApiCredentials): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", "Cirrus/${BuildConfig.VERSION_NAME} (Android)")
                credentials.apiKey?.let { key ->
                    builder.header("Authorization", "Bearer $key")
                }
                chain.proceed(builder.build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            // BODY would dump the whole generation and the key; headers only.
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("Authorization")
                        },
                    )
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // A long generation legitimately holds the socket open with sparse writes, so the
            // read and overall call timeouts are disabled and cancellation is driven by the UI.
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * A separate client for github.com.
     *
     * The Ollama client above attaches the Ollama API key to every request it makes. Reusing it
     * would send that key to GitHub, so the two never share a client — the type system enforces
     * it via the [GitHubHttp] qualifier.
     */
    @Provides
    @Singleton
    @GitHubHttp
    fun provideGitHubOkHttpClient(credentials: GitHubCredentials): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", "Cirrus/${BuildConfig.VERSION_NAME} (Android)")
                credentials.token?.let { token ->
                    builder.header("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("Authorization")
                        },
                    )
                }
            }
            // Unlike a generation, every GitHub call is a bounded request/response.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * A client for Spotify, for both the API and the accounts host.
     *
     * One client rather than two, with the interceptor deciding: the bearer token is attached only
     * to api.spotify.com. accounts.spotify.com must not receive it — the token endpoint
     * authenticates the PKCE verifier in the body, and an `Authorization` header there is at best
     * ignored and at worst the reason a refresh fails while it is trying to fix an expired token.
     */
    @Provides
    @Singleton
    @SpotifyHttp
    fun provideSpotifyOkHttpClient(credentials: SpotifyCredentials): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                    .header("User-Agent", "Cirrus/${BuildConfig.VERSION_NAME} (Android)")
                if (request.url.host.startsWith("api.")) {
                    credentials.accessToken?.let { token ->
                        builder.header("Authorization", "Bearer $token")
                    }
                }
                chain.proceed(builder.build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("Authorization")
                        },
                    )
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * A client for ElevenLabs.
     *
     * Separate again, and for the same reason: no other key may travel to it. Synthesis of a long
     * answer takes a while to come back, so the read timeout is generous — but it is bounded,
     * because unlike a generation there is nothing to watch while it hangs.
     */
    @Provides
    @Singleton
    @ElevenLabsHttp
    fun provideElevenLabsOkHttpClient(credentials: ElevenLabsCredentials): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", "Cirrus/${BuildConfig.VERSION_NAME} (Android)")
                credentials.apiKey?.let { key -> builder.header("xi-api-key", key) }
                chain.proceed(builder.build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("xi-api-key")
                        },
                    )
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * A client for MCP servers that attaches no credential of its own.
     *
     * Every other client here injects a credential holder and sets `Authorization` from it. This
     * one must not: an MCP server is a host the user picked, each carries its own token, and the
     * transport sets the header per request. An interceptor here would overwrite that.
     *
     * The read timeout is generous because the SSE transport parks on a long-lived event stream
     * waiting for a reply to come back on it, but it is not disabled — unlike a generation, an
     * MCP call that never answers is a hung tool call in the middle of a turn.
     */
    @Provides
    @Singleton
    @McpHttp
    fun provideMcpOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Cirrus/${BuildConfig.VERSION_NAME} (Android)")
                    .build()
                chain.proceed(request)
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("Authorization")
                        },
                    )
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}
