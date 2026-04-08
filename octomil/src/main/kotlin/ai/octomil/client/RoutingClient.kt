package ai.octomil.client

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// =============================================================================
// Models
// =============================================================================

/** Device capabilities sent to the routing API. */
@Serializable
data class RoutingDeviceCapabilities(
    val platform: String,
    val model: String,
    @SerialName("total_memory_mb") val totalMemoryMb: Long,
    @SerialName("gpu_available") val gpuAvailable: Boolean,
    @SerialName("npu_available") val npuAvailable: Boolean,
    @SerialName("supported_runtimes") val supportedRuntimes: List<String>,
)

/** Request body for POST /api/v1/route. */
@Serializable
internal data class RoutingRequest(
    @SerialName("model_id") val modelId: String,
    @SerialName("model_params") val modelParams: Int,
    @SerialName("model_size_mb") val modelSizeMb: Double,
    @SerialName("device_capabilities") val deviceCapabilities: RoutingDeviceCapabilities,
    val prefer: String? = null,
    @SerialName("deployment_id") val deploymentId: String? = null,
)

/** Response from POST /api/v1/route. */
@Serializable
data class RoutingDecision(
    val id: String,
    val target: String,
    val format: String,
    val engine: String,
    @SerialName("fallback_target") val fallbackTarget: RoutingFallbackTarget? = null,
)

/** Fallback target from routing response. */
@Serializable
data class RoutingFallbackTarget(
    val endpoint: String,
)

/** Request body for POST /api/v1/inference. */
@Serializable
private data class CloudInferenceRequest(
    @SerialName("model_id") val modelId: String,
    @SerialName("input_data") val inputData: JsonElement,
    val parameters: Map<String, JsonElement> = emptyMap(),
)

/** Response from POST /api/v1/inference. */
@Serializable
data class CloudInferenceResponse(
    val output: JsonElement,
    @SerialName("latency_ms") val latencyMs: Double,
    val provider: String,
)

/** Routing preference for execution target. */
enum class RoutingPreference(val value: String) {
    DEVICE("device"),
    CLOUD("cloud"),
    CHEAPEST("cheapest"),
    FASTEST("fastest"),
}

/** Configuration for the routing client. */
data class RoutingConfig(
    val serverUrl: String,
    val apiKey: String,
    val cacheTtlMs: Long = 300_000L,
    val prefer: RoutingPreference = RoutingPreference.FASTEST,
    /**
     * When `true`, [prefer] was explicitly set by the caller. When `false`
     * (the default), managed routing omits `prefer` from the request so the
     * server applies the deployment's own `routing_preference`.
     */
    val preferExplicit: Boolean = false,
    val modelParams: Int = 0,
    val modelSizeMb: Double = 0.0,
    /** Deployment identifier for managed routing. When set, the server
     * applies the deployment's routing_preference automatically. */
    val deploymentId: String? = null,
)

// =============================================================================
// RoutingClient
// =============================================================================

/**
 * Calls the Octomil routing API to decide whether inference should run
 * on-device or in the cloud. Caches decisions with a configurable TTL.
 *
 * Thread-safe: uses [ConcurrentHashMap] for cache and [OkHttpClient]
 * for HTTP (which is also thread-safe).
 */
class RoutingClient(private val config: RoutingConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Encoder that omits null fields — used for route requests so `prefer`
     *  and `deployment_id` are excluded when not set. */
    private val requestJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val decision: RoutingDecision,
        val expiresAt: Long,
    )

    private val serverUrl = config.serverUrl.trimEnd('/')

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Ask the routing API whether to run on-device or in the cloud.
     *
     * Returns a cached decision when available and not expired.
     * Returns `null` on any failure so the caller can fall back to local inference.
     */
    fun route(
        modelId: String,
        deviceCapabilities: RoutingDeviceCapabilities,
    ): RoutingDecision? {
        val cached = cache[modelId]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            return cached.decision
        }

        // When a deploymentId is present and prefer was not explicitly set,
        // omit prefer so the server applies the deployment's routing_preference.
        val preferValue: String? = if (config.deploymentId != null && !config.preferExplicit) {
            null
        } else {
            config.prefer.value
        }

        val body = RoutingRequest(
            modelId = modelId,
            modelParams = config.modelParams,
            modelSizeMb = config.modelSizeMb,
            deviceCapabilities = deviceCapabilities,
            prefer = preferValue,
            deploymentId = config.deploymentId,
        )

        val jsonBody = requestJson.encodeToString(RoutingRequest.serializer(), body)
        val request = Request.Builder()
            .url("$serverUrl/api/v1/route")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("User-Agent", "octomil-android/1.0")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Routing API returned ${response.code}, falling back to device")
                    return null
                }
                val responseBody = response.body?.string() ?: return null
                val decision = json.decodeFromString(RoutingDecision.serializer(), responseBody)
                cache[modelId] = CacheEntry(
                    decision = decision,
                    expiresAt = System.currentTimeMillis() + config.cacheTtlMs,
                )
                decision
            }
        } catch (e: Exception) {
            Timber.w(e, "Routing request failed, falling back to device")
            null
        }
    }

    /**
     * Run inference in the cloud via POST /api/v1/inference.
     *
     * @throws Exception on network or server error, so caller can fall back to local.
     */
    fun cloudInfer(
        modelId: String,
        inputData: JsonElement,
        parameters: Map<String, JsonElement> = emptyMap(),
    ): CloudInferenceResponse {
        val body = CloudInferenceRequest(
            modelId = modelId,
            inputData = inputData,
            parameters = parameters,
        )

        val jsonBody = json.encodeToString(CloudInferenceRequest.serializer(), body)
        val request = Request.Builder()
            .url("$serverUrl/api/v1/inference")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("User-Agent", "octomil-android/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OctomilException(
                    OctomilErrorCode.fromHttpStatus(response.code),
                    "Cloud inference failed: HTTP ${response.code}",
                )
            }
            val responseBody = response.body?.string()
                ?: throw OctomilException(OctomilErrorCode.SERVER_ERROR, "Cloud inference returned empty body")
            return json.decodeFromString(CloudInferenceResponse.serializer(), responseBody)
        }
    }

    /** Invalidate all cached routing decisions. */
    fun clearCache() {
        cache.clear()
    }

    /** Invalidate the cached routing decision for a specific model. */
    fun invalidate(modelId: String) {
        cache.remove(modelId)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
