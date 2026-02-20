package ai.edgeml.pairing

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Handles the complete deep-link-initiated pairing flow.
 *
 * This bridges [DeepLinkHandler] (URI parsing) with [PairingManager] (pairing logic)
 * to provide a single entry point for deep link pairing. It constructs a lightweight
 * API client targeting the server URL from the deep link, without requiring a full
 * [ai.edgeml.config.EdgeMLConfig].
 *
 * Pairing endpoints do not require authentication — the pairing code is the secret.
 *
 * ## Usage
 *
 * ```kotlin
 * val action = DeepLinkHandler.handleIntent(intent)
 * if (action is DeepLinkHandler.DeepLinkAction.Pair) {
 *     val result = DeepLinkPairing.executePairing(context, action)
 *     result.onSuccess { report ->
 *         // Pairing and benchmark complete
 *     }.onFailure { error ->
 *         // Handle error (PairingException)
 *     }
 * }
 * ```
 */
object DeepLinkPairing {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Execute the full pairing flow from a deep link action.
     *
     * Creates a lightweight API client targeting the server specified in the deep
     * link (or the default server), then runs the complete pairing sequence:
     * connect, wait for deployment, download model, benchmark, and report.
     *
     * @param context Android context for device info collection and file caching.
     * @param action The [DeepLinkHandler.DeepLinkAction.Pair] parsed from the deep link.
     * @param timeoutMs Maximum time to wait for deployment (default: 5 minutes).
     * @return [Result] wrapping a [BenchmarkReport] on success, or a [PairingException] on failure.
     */
    suspend fun executePairing(
        context: Context,
        action: DeepLinkHandler.DeepLinkAction.Pair,
        timeoutMs: Long = PairingManager.DEFAULT_TIMEOUT_MS,
    ): Result<BenchmarkReport> {
        Timber.i(
            "Starting deep link pairing: token=%s host=%s",
            action.token, action.host,
        )

        return try {
            val api = createPairingApi(action.host)
            val manager = PairingManager(api, context)
            val report = manager.pair(action.token, timeoutMs)
            Result.success(report)
        } catch (e: PairingException) {
            Timber.e(e, "Deep link pairing failed: %s", e.errorCode)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during deep link pairing")
            Result.failure(
                PairingException(
                    "Deep link pairing failed: ${e.message}",
                    PairingException.ErrorCode.UNKNOWN,
                    e,
                ),
            )
        }
    }

    /**
     * Create a lightweight [ai.edgeml.api.EdgeMLApi] for pairing endpoints.
     *
     * Pairing endpoints (`/api/v1/deploy/pair/...`) do not require authentication,
     * so this client omits the auth interceptor. It only includes a User-Agent header
     * and basic timeout configuration.
     *
     * @param serverUrl The base URL of the EdgeML server.
     * @return An [ai.edgeml.api.EdgeMLApi] instance configured for pairing-only calls.
     */
    internal fun createPairingApi(serverUrl: String): ai.edgeml.api.EdgeMLApi {
        val baseUrl = serverUrl.trimEnd('/') + "/"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        return retrofit.create(ai.edgeml.api.EdgeMLApi::class.java)
    }
}
