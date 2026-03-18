package ai.octomil.pairing

import ai.octomil.Octomil
import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Handles the complete deep-link-initiated pairing flow.
 *
 * This bridges [DeepLinkHandler] (URI parsing) with [PairingManager] (pairing logic)
 * to provide a single entry point for deep link pairing. It constructs a lightweight
 * API client targeting the server URL from the deep link, without requiring a full
 * [ai.octomil.config.OctomilConfig].
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
     * connect, wait for deployment, download model, and persist. After download,
     * the model is deployed for inference which triggers benchmarking and
     * benchmark submission to the server.
     *
     * @param context Android context for device info collection and file caching.
     * @param action The [DeepLinkHandler.DeepLinkAction.Pair] parsed from the deep link.
     * @param timeoutMs Maximum time to wait for deployment (default: 5 minutes).
     * @return [Result] wrapping a [DeploymentResult] on success, or a [PairingException] on failure.
     */
    suspend fun executePairing(
        context: Context,
        action: DeepLinkHandler.DeepLinkAction.Pair,
        timeoutMs: Long = PairingManager.DEFAULT_TIMEOUT_MS,
    ): Result<DeploymentResult> {
        Timber.i(
            "Starting deep link pairing: token=%s host=%s",
            action.token, action.host,
        )

        return try {
            val api = createPairingApi(action.host)
            val manager = PairingManager(api, context)
            val result = manager.pair(action.token, timeoutMs)

            // Deploy model for inference — benchmarks run and are submitted here
            val modelPath = result.modelFilePath
            if (modelPath != null) {
                try {
                    Octomil.deploy(
                        context = context,
                        modelFile = File(modelPath),
                        name = result.modelName,
                        pairingCode = action.token,
                    )
                } catch (e: Exception) {
                    // Non-fatal: deploy/benchmark failure should not break the pairing result
                    Timber.w(e, "Deep link pairing: deploy+benchmark failed (non-fatal)")
                }
            }

            Result.success(result)
        } catch (e: PairingException) {
            Timber.e(e, "Deep link pairing failed: %s", e.pairingErrorCode)
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
     * Create a lightweight [ai.octomil.api.OctomilApi] for pairing endpoints.
     *
     * Pairing endpoints (`/api/v1/deploy/pair/...`) do not require authentication,
     * so this client omits the auth interceptor. It only includes a User-Agent header
     * and basic timeout configuration.
     *
     * @param serverUrl The base URL of the Octomil server.
     * @return An [ai.octomil.api.OctomilApi] instance configured for pairing-only calls.
     */
    internal fun createPairingApi(serverUrl: String): ai.octomil.api.OctomilApi {
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

        return retrofit.create(ai.octomil.api.OctomilApi::class.java)
    }
}
