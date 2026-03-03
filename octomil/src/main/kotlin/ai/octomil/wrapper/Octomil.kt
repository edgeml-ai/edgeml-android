package ai.octomil.wrapper

import ai.octomil.BuildConfig
import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.TelemetryV2BatchRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.tensorflow.lite.Interpreter
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Main entry point for wrapping a TFLite [Interpreter] with Octomil telemetry,
 * input validation, and OTA model updates.
 *
 * Usage requires a single import:
 * ```kotlin
 * import ai.octomil.wrapper.Octomil
 * ```
 *
 * Then change one line at model load -- zero changes at call sites:
 * ```kotlin
 * // Before
 * val interpreter = Interpreter(modelFile)
 * interpreter.run(input, output)
 *
 * // After
 * val interpreter = Octomil.wrap(Interpreter(modelFile), modelId = "classifier")
 * interpreter.predict(input, output)  // cross-SDK consistent API
 * ```
 *
 * This object lives in the `ai.octomil.wrapper` package to stay decoupled from
 * the full federated-learning SDK entry point at [ai.octomil.Octomil].
 */
object Octomil {

    private const val TAG = "Octomil.wrap"

    /**
     * Wrap a TFLite [Interpreter] with Octomil instrumentation.
     *
     * @param interpreter An already-constructed TFLite Interpreter.
     * @param modelId Logical model identifier (used in telemetry and OTA checks).
     * @param config Wrapper configuration. Defaults to [OctomilWrapperConfig.default].
     * @param persistDir Directory for persisting unsent telemetry events across
     *   process restarts. Null disables disk persistence (events are dropped on
     *   flush failure). Typically pass `context.filesDir` or `context.cacheDir`.
     * @param dispatcher Coroutine dispatcher for background telemetry and OTA work.
     * @return An [OctomilWrappedInterpreter] with the `predict` API for cross-SDK consistency.
     */
    fun wrap(
        interpreter: Interpreter,
        modelId: String,
        config: OctomilWrapperConfig = OctomilWrapperConfig.default(),
        persistDir: File? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): OctomilWrappedInterpreter {
        val sender = buildSender(config)

        val telemetryDir = if (persistDir != null) {
            File(persistDir, "octomil_telemetry")
        } else {
            null
        }

        val telemetryQueue = TelemetryQueue(
            batchSize = config.telemetryBatchSize,
            flushIntervalMs = config.telemetryFlushIntervalMs,
            persistDir = telemetryDir,
            sender = sender,
            dispatcher = dispatcher,
            orgId = config.orgId,
            deviceId = config.deviceId,
        )

        if (config.telemetryEnabled) {
            telemetryQueue.start()
        }

        val wrapped = OctomilWrappedInterpreter(
            interpreter = interpreter,
            modelId = modelId,
            config = config,
            telemetryQueue = telemetryQueue,
        )

        // Async: fetch model contract for input validation
        if (config.validateInputs && config.serverUrl != null && config.apiKey != null) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            scope.launch {
                fetchModelContract(wrapped, modelId, config)
            }
        }

        // Async: check for OTA model updates (non-blocking)
        if (config.otaUpdatesEnabled && config.serverUrl != null && config.apiKey != null) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            scope.launch {
                checkForOtaUpdate(modelId, config)
            }
        }

        Timber.i(
            "%s: Wrapped interpreter for model '%s' (telemetry=%b, validation=%b, ota=%b)",
            TAG,
            modelId,
            config.telemetryEnabled,
            config.validateInputs,
            config.otaUpdatesEnabled,
        )

        return wrapped
    }

    /**
     * Build a [TelemetrySender] from the config, or null if telemetry is
     * disabled or no server is configured.
     */
    private fun buildSender(config: OctomilWrapperConfig): TelemetrySender? {
        if (!config.telemetryEnabled) return null
        val serverUrl = config.serverUrl ?: return null
        val apiKey = config.apiKey ?: return null

        val api = buildTelemetryApi(serverUrl, apiKey)

        return TelemetrySender { batch: TelemetryV2BatchRequest ->
            Timber.d("%s: Sending v2 telemetry batch (%d events) to %s", TAG, batch.events.size, serverUrl)

            val response = api.sendTelemetryV2(batch)
            if (!response.isSuccessful) {
                throw IOException(
                    "Telemetry v2 batch upload failed: HTTP ${response.code()} ${response.message()}",
                )
            }
            Timber.d(
                "%s: Telemetry v2 batch accepted: %d events",
                TAG,
                batch.events.size,
            )
        }
    }

    /**
     * Build a minimal [OctomilApi] Retrofit instance for telemetry POSTs.
     * Uses the wrapper's serverUrl and apiKey rather than a full [ai.octomil.config.OctomilConfig].
     */
    internal fun buildTelemetryApi(serverUrl: String, apiKey: String): OctomilApi {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            })
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl("$serverUrl/")
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(OctomilApi::class.java)
    }

    /**
     * Fetch the model contract from the server and attach it to the wrapper
     * for input validation. Failures are silently ignored -- validation is
     * best-effort.
     */
    private suspend fun fetchModelContract(
        wrapped: OctomilWrappedInterpreter,
        modelId: String,
        @Suppress("UNUSED_PARAMETER") config: OctomilWrapperConfig,
    ) {
        try {
            // PLANNED(v2): Fetch model contract from server via
            // GET $serverUrl/api/v1/models/$modelId/contract instead of inferring
            // from the interpreter's input tensor shape. Server contract includes
            // dtype, named inputs, and value range constraints.
            // For now, infer contract from the interpreter's input tensor.
            val inputTensor = wrapped.getInputTensor(0)
            val shape = inputTensor.shape()
            val elementCount = shape.fold(1) { acc, dim -> acc * dim }

            wrapped.serverContract = ServerModelContract(
                expectedInputSize = elementCount,
                inputDescription = shape.contentToString(),
            )
            Timber.d(
                "%s: Inferred contract for '%s': %d elements %s",
                TAG,
                modelId,
                elementCount,
                shape.contentToString(),
            )
        } catch (e: Exception) {
            Timber.d(e, "%s: Could not fetch/infer model contract for '%s'", TAG, modelId)
        }
    }

    /**
     * Check for OTA model updates. Non-blocking, best-effort.
     *
     * PLANNED(v2): Implement OTA update check by calling
     * GET $serverUrl/api/v1/models/$modelId/updates?current_version=<local_version>,
     * downloading the new TFLite artifact, and hot-swapping the interpreter.
     */
    private suspend fun checkForOtaUpdate(
        modelId: String,
        @Suppress("UNUSED_PARAMETER") config: OctomilWrapperConfig,
    ) {
        try {
            // GET $serverUrl/api/v1/models/$modelId/updates?current_version=local
            Timber.d("%s: OTA update check for '%s' (not yet implemented)", TAG, modelId)
        } catch (e: Exception) {
            Timber.d(e, "%s: OTA check failed for '%s'", TAG, modelId)
        }
    }
}
