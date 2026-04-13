package ai.octomil.runtime.planner

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the server-side runtime planner API.
 *
 * Communicates with:
 * - `POST /api/v2/runtime/plan` -- fetch runtime plan
 * - `POST /api/v2/runtime/benchmarks` -- upload benchmark telemetry
 *
 * All calls are best-effort: failures are logged and return null/false.
 * Inference is never blocked on a planner failure.
 *
 * Thread-safe: stateless HTTP calls with no mutable shared state.
 */
class RuntimePlannerClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val serverUrl = baseUrl.trimEnd('/')

    // =========================================================================
    // Plan Fetch
    // =========================================================================

    /**
     * Fetch a runtime plan from the server.
     *
     * @param request The plan request containing model, capability, and device profile.
     * @return The server plan response, or null on any failure.
     */
    fun fetchPlan(request: RuntimePlanRequest): RuntimePlanResponse? {
        val url = "$serverUrl$PLAN_PATH"
        val body = try {
            json.encodeToString(RuntimePlanRequest.serializer(), request)
        } catch (e: Exception) {
            Timber.d(e, "Failed to serialize plan request")
            return null
        }

        val httpRequest = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply { addAuthHeaders(this) }
            .header("User-Agent", userAgent())
            .build()

        return try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("Plan fetch returned HTTP %d from %s", response.code, url)
                    return null
                }
                val responseBody = response.body.string().ifEmpty { return null }
                json.decodeFromString(RuntimePlanResponse.serializer(), responseBody)
            }
        } catch (e: Exception) {
            Timber.d(e, "Plan fetch failed from %s", url)
            null
        }
    }

    // =========================================================================
    // Benchmark Upload
    // =========================================================================

    /**
     * Upload privacy-safe benchmark telemetry to the server.
     *
     * @param payload The benchmark payload. Must not contain prompts, responses,
     *                file paths, or user data.
     * @return True if upload succeeded, false otherwise.
     */
    fun uploadBenchmark(payload: BenchmarkTelemetryPayload): Boolean {
        val url = "$serverUrl$BENCHMARK_PATH"
        val body = try {
            json.encodeToString(BenchmarkTelemetryPayload.serializer(), payload)
        } catch (e: Exception) {
            Timber.d(e, "Failed to serialize benchmark payload")
            return false
        }

        val httpRequest = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply { addAuthHeaders(this) }
            .header("User-Agent", userAgent())
            .build()

        return try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("Benchmark upload returned HTTP %d", response.code)
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Timber.d(e, "Benchmark upload failed")
            false
        }
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private fun addAuthHeaders(builder: Request.Builder) {
        if (apiKey != null) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        builder.header("Accept", "application/json")
        builder.header("Content-Type", "application/json")
    }

    companion object {
        internal const val DEFAULT_BASE_URL = "https://api.octomil.com"
        internal const val PLAN_PATH = "/api/v2/runtime/plan"
        internal const val BENCHMARK_PATH = "/api/v2/runtime/benchmarks"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        internal fun userAgent(): String {
            val sdkVersion = try {
                ai.octomil.BuildConfig.OCTOMIL_VERSION
            } catch (_: Exception) {
                "unknown"
            }
            return "Octomil-Android-SDK/$sdkVersion (Android ${android.os.Build.VERSION.SDK_INT})"
        }
    }
}
