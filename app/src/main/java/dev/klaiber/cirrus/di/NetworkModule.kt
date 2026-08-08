package dev.klaiber.cirrus.di

import dev.klaiber.cirrus.BuildConfig
import dev.klaiber.cirrus.data.remote.ApiCredentials
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
}
