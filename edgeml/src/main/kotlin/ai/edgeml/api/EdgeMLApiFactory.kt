package ai.edgeml.api

import ai.edgeml.config.EdgeMLConfig
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import timber.log.Timber
import java.io.IOException
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
            .addInterceptor(createRetryInterceptor(config.maxRetries, config.retryDelayMs))
            .apply {
                if (config.debugMode) {
                    addInterceptor(createLoggingInterceptor())
                }
            }
            .apply {
                buildCertificatePinner(config)?.let { certificatePinner(it) }
            }
            .retryOnConnectionFailure(true)
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

    /**
     * Retry interceptor with exponential backoff for transient HTTP errors.
     *
     * Retries on HTTP 429 (Too Many Requests), 500, 502, 503, 504 and
     * on network-level IOExceptions. Uses exponential backoff with jitter
     * starting from [baseDelayMs]. Respects Retry-After header on 429s.
     */
    internal fun createRetryInterceptor(
        maxRetries: Int,
        baseDelayMs: Long,
    ): Interceptor = Interceptor { chain ->
        val request = chain.request()
        var lastException: IOException? = null
        var lastResponse: Response? = null

        for (attempt in 0..maxRetries) {
            try {
                // Close the previous response body before retrying
                lastResponse?.close()
                lastResponse = null

                val response = chain.proceed(request)

                if (!isRetryableStatusCode(response.code) || attempt == maxRetries) {
                    return@Interceptor response
                }

                // Determine delay: prefer Retry-After header, fall back to exponential backoff
                val delayMs = getRetryDelay(response, attempt, baseDelayMs)
                Timber.d("Retryable HTTP ${response.code}, attempt ${attempt + 1}/$maxRetries, delay ${delayMs}ms")
                lastResponse = response
                Thread.sleep(delayMs)
            } catch (e: IOException) {
                lastException = e
                if (attempt == maxRetries) {
                    throw e
                }
                val delayMs = computeBackoffDelay(attempt, baseDelayMs)
                Timber.d("IOException on attempt ${attempt + 1}/$maxRetries, delay ${delayMs}ms: ${e.message}")
                Thread.sleep(delayMs)
            }
        }

        // Should not reach here, but just in case
        lastResponse?.let { return@Interceptor it }
        throw lastException ?: IOException("Retry exhausted with no response")
    }

    /**
     * Build a CertificatePinner from config. Returns null if no pins configured.
     */
    private fun buildCertificatePinner(config: EdgeMLConfig): CertificatePinner? {
        if (config.certificatePins.isEmpty() || config.pinnedHostname.isBlank()) {
            return null
        }
        val builder = CertificatePinner.Builder()
        for (pin in config.certificatePins) {
            builder.add(config.pinnedHostname, "sha256/$pin")
        }
        return builder.build()
    }

    private fun isRetryableStatusCode(code: Int): Boolean =
        code == 429 || code == 500 || code == 502 || code == 503 || code == 504

    private fun getRetryDelay(response: Response, attempt: Int, baseDelayMs: Long): Long {
        // Check for Retry-After header (seconds)
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        if (retryAfter != null && retryAfter > 0) {
            return retryAfter * 1000
        }
        return computeBackoffDelay(attempt, baseDelayMs)
    }

    private fun computeBackoffDelay(attempt: Int, baseDelayMs: Long): Long {
        // Exponential backoff: baseDelay * 2^attempt, capped at 30s, with jitter
        val exponential = baseDelayMs * (1L shl minOf(attempt, 5))
        val capped = minOf(exponential, 30_000L)
        val jitter = (capped * 0.1 * Math.random()).toLong()
        return capped + jitter
    }
}
