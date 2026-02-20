package ai.edgeml.wrapper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File

/**
 * Main entry point for wrapping a TFLite [Interpreter] with EdgeML telemetry,
 * input validation, and OTA model updates.
 *
 * Usage requires a single import:
 * ```kotlin
 * import ai.edgeml.wrapper.EdgeML
 * ```
 *
 * Then change one line at model load -- zero changes at call sites:
 * ```kotlin
 * // Before
 * val interpreter = Interpreter(modelFile)
 * interpreter.run(input, output)
 *
 * // After
 * val interpreter = EdgeML.wrap(Interpreter(modelFile), modelId = "classifier")
 * interpreter.run(input, output)  // identical API
 * ```
 *
 * This object lives in the `ai.edgeml.wrapper` package to stay decoupled from
 * the full federated-learning SDK entry point at [ai.edgeml.EdgeML].
 */
object EdgeML {

    private const val TAG = "EdgeML.wrap"

    /**
     * Wrap a TFLite [Interpreter] with EdgeML instrumentation.
     *
     * @param interpreter An already-constructed TFLite Interpreter.
     * @param modelId Logical model identifier (used in telemetry and OTA checks).
     * @param config Wrapper configuration. Defaults to [EdgeMLWrapperConfig.default].
     * @param persistDir Directory for persisting unsent telemetry events across
     *   process restarts. Null disables disk persistence (events are dropped on
     *   flush failure). Typically pass `context.filesDir` or `context.cacheDir`.
     * @param dispatcher Coroutine dispatcher for background telemetry and OTA work.
     * @return An [EdgeMLWrappedInterpreter] with the same `run` API as [Interpreter].
     */
    fun wrap(
        interpreter: Interpreter,
        modelId: String,
        config: EdgeMLWrapperConfig = EdgeMLWrapperConfig.default(),
        persistDir: File? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): EdgeMLWrappedInterpreter {
        val sender = buildSender(config)

        val telemetryDir = if (persistDir != null) {
            File(persistDir, "edgeml_telemetry")
        } else {
            null
        }

        val telemetryQueue = TelemetryQueue(
            batchSize = config.telemetryBatchSize,
            flushIntervalMs = config.telemetryFlushIntervalMs,
            persistDir = telemetryDir,
            sender = sender,
            dispatcher = dispatcher,
        )

        if (config.telemetryEnabled) {
            telemetryQueue.start()
        }

        val wrapped = EdgeMLWrappedInterpreter(
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
    private fun buildSender(config: EdgeMLWrapperConfig): TelemetrySender? {
        if (!config.telemetryEnabled) return null
        val serverUrl = config.serverUrl ?: return null
        val apiKey = config.apiKey ?: return null

        // TODO: implement real HTTP sender using OkHttp/Retrofit
        // For now, return a sender that logs events (production implementation
        // would POST to $serverUrl/api/v1/inference/telemetry with bearer $apiKey)
        return TelemetrySender { events ->
            Timber.d(
                "%s: Would send %d telemetry events to %s",
                TAG,
                events.size,
                serverUrl,
            )
        }
    }

    /**
     * Fetch the model contract from the server and attach it to the wrapper
     * for input validation. Failures are silently ignored -- validation is
     * best-effort.
     */
    private suspend fun fetchModelContract(
        wrapped: EdgeMLWrappedInterpreter,
        modelId: String,
        @Suppress("UNUSED_PARAMETER") config: EdgeMLWrapperConfig,
    ) {
        try {
            // TODO: implement real contract fetch from server
            // GET $serverUrl/api/v1/models/$modelId/contract
            // For now, try to infer contract from the interpreter's input tensor
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
     * TODO: implement real OTA check against server
     */
    private suspend fun checkForOtaUpdate(
        modelId: String,
        @Suppress("UNUSED_PARAMETER") config: EdgeMLWrapperConfig,
    ) {
        try {
            // GET $serverUrl/api/v1/models/$modelId/updates?current_version=local
            Timber.d("%s: OTA update check for '%s' (not yet implemented)", TAG, modelId)
        } catch (e: Exception) {
            Timber.d(e, "%s: OTA check failed for '%s'", TAG, modelId)
        }
    }
}
