package ai.edgeml.api

import ai.edgeml.config.EdgeMLConfig
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Factory for creating EdgeML API instances.
 *
 * Handles OkHttp client configuration, authentication interceptors,
 * and JSON serialization setup.
 */
object EdgeMLApiFactory {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

    /**
     * Create a configured EdgeML API instance.
     *
     * @param config EdgeML configuration containing server URL and auth settings
     * @return Configured EdgeMLApi instance
     */
    fun create(config: EdgeMLConfig): EdgeMLApi {
        val okHttpClient = createOkHttpClient(config)
        val retrofit = createRetrofit(config, okHttpClient)
        return retrofit.create(EdgeMLApi::class.java)
    }

    /**
     * Create OkHttp client with interceptors and timeouts.
     */
    private fun createOkHttpClient(config: EdgeMLConfig): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(config.connectionTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(createAuthInterceptor(config))
            .addInterceptor(createUserAgentInterceptor())
            .apply {
                if (config.debugMode) {
                    addInterceptor(createLoggingInterceptor())
                }
            }.retryOnConnectionFailure(true)
            .build()

    /**
     * Create Retrofit instance with JSON converter.
     */
    private fun createRetrofit(
        config: EdgeMLConfig,
        okHttpClient: OkHttpClient,
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit
            .Builder()
            .baseUrl(config.serverUrl + "/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    /**
     * Interceptor to add device access token authentication header.
     */
    private fun createAuthInterceptor(config: EdgeMLConfig): Interceptor =
        Interceptor { chain ->
            val request =
                chain
                    .request()
                    .newBuilder()
                    .addHeader("Authorization", "Bearer ${config.deviceAccessToken}")
                    .addHeader("X-Org-Id", config.orgId)
                    .build()
            chain.proceed(request)
        }

    /**
     * Interceptor to add User-Agent header.
     */
    private fun createUserAgentInterceptor(): Interceptor =
        Interceptor { chain ->
            val request =
                chain
                    .request()
                    .newBuilder()
                    .addHeader(
                        "User-Agent",
                        "EdgeML-Android-SDK/${ai.edgeml.BuildConfig.EDGEML_VERSION} " +
                            "(Android ${android.os.Build.VERSION.SDK_INT})",
                    ).build()
            chain.proceed(request)
        }

    /**
     * Logging interceptor for debug mode.
     */
    private fun createLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor { message ->
            Timber.tag("EdgeML-HTTP").d(message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
}
