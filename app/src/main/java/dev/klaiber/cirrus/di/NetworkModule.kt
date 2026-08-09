package dev.klaiber.cirrus.di

import dev.klaiber.cirrus.BuildConfig
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
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
}
