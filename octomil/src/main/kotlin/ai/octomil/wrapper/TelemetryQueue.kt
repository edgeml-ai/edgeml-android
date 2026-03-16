package ai.octomil.wrapper

import ai.octomil.generated.SpanAttribute
import ai.octomil.generated.SpanName
import ai.octomil.generated.TelemetryEvent as ContractTelemetryEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import ai.octomil.BuildConfig
import ai.octomil.generated.OtlpResourceAttribute
import ai.octomil.api.dto.AnyValue
import ai.octomil.api.dto.ExportLogsServiceRequest
import ai.octomil.api.dto.InstrumentationScope
import ai.octomil.api.dto.KeyValue
import ai.octomil.api.dto.LogRecord
import ai.octomil.api.dto.OtlpResource
import ai.octomil.api.dto.ResourceLogs
import ai.octomil.api.dto.ScopeLogs
import ai.octomil.api.dto.TelemetryAttributes
import ai.octomil.api.dto.TelemetryV2BatchRequest
import ai.octomil.api.dto.TelemetryV2Event
import ai.octomil.api.dto.TelemetryV2Resource
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A single inference telemetry event.
 */
@Serializable
data class InferenceTelemetryEvent(
    /** Model identifier. */
    val modelId: String,
    /** Inference latency in milliseconds. */
    val latencyMs: Double,
    /** Unix timestamp in milliseconds when inference started. */
    val timestampMs: Long,
    /** Whether the inference completed successfully. */
    val success: Boolean,
    /** Error message if inference failed. Null on success. */
    val errorMessage: String? = null,
)

/**
 * Thread-safe queue that batches inference telemetry events and flushes them
 * to the server (or persists to disk on failure).
 *
 * Flush triggers:
 * 1. Timer-based: every [flushIntervalMs] milliseconds.
 * 2. Size-based: when the queue reaches [batchSize] events.
 *
 * Events that fail to send are persisted to [persistDir] as JSON files and
 * retried on the next flush cycle.
 *
 * Thread safety: uses [ConcurrentLinkedQueue] for lock-free enqueue, and a
 * [Mutex] around flush/persist to avoid concurrent disk or network writes.
 */
class TelemetryQueue internal constructor(
    private val batchSize: Int,
    private val flushIntervalMs: Long,
    private val persistDir: File?,
    private val sender: TelemetrySender?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val orgId: String? = null,
    private val deviceId: String? = null,
) : Closeable {
    private val inferenceQueue = ConcurrentLinkedQueue<InferenceTelemetryEvent>()
    private val v2Queue = ConcurrentLinkedQueue<TelemetryV2Event>()
    private val flushMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var flushJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        private const val TAG = "TelemetryQueue"
        private const val PERSIST_DIR_NAME = "octomil_telemetry"
        private const val MAX_PERSISTED_FILES = 100

        /**
         * Shared instance used for funnel event reporting from classes that don't hold
         * a direct TelemetryQueue reference (PairingManager, ModelManager, BenchmarkRunner).
         * Set automatically when the first TelemetryQueue is started with a sender.
         */
        @Volatile
        var shared: TelemetryQueue? = null
            private set
    }

    /**
     * Start the periodic flush timer. Call once after construction.
     */
    fun start() {
        if (flushIntervalMs > 0) {
            flushJob = scope.launch {
                while (isActive) {
                    delay(flushIntervalMs)
                    flush()
                }
            }
        }
        // Attempt to load and resend any persisted events from a previous session
        scope.launch { loadPersistedEvents() }

        if (sender != null && shared == null) {
            shared = this
        }
    }

    /**
     * Enqueue an inference telemetry event. Non-blocking. If the combined queue
     * size reaches [batchSize], a flush is triggered asynchronously.
     */
    fun enqueue(event: InferenceTelemetryEvent) {
        inferenceQueue.add(event)
        if (totalPendingCount >= batchSize) {
            scope.launch { flush() }
        }
    }

    /**
     * Enqueue a generic v2 telemetry event. Non-blocking. Enables training,
     * experiment, deploy, and inference.started events to flow through the
     * same pipeline.
     */
    fun enqueueV2Event(event: TelemetryV2Event) {
        v2Queue.add(event)
        if (totalPendingCount >= batchSize) {
            scope.launch { flush() }
        }
    }

    /**
     * The number of inference events currently buffered (not yet flushed).
     */
    val pendingCount: Int get() = inferenceQueue.size

    /**
     * The number of generic v2 events currently buffered.
     */
    val pendingV2Count: Int get() = v2Queue.size

    /**
     * Total number of events buffered across both queues.
     */
    private val totalPendingCount: Int get() = inferenceQueue.size + v2Queue.size

    /**
     * Flush all buffered events. Sends to the server if a [TelemetrySender] is
     * configured; otherwise persists to disk.
     */
    suspend fun flush() {
        flushMutex.withLock {
            val inferenceEvents = drainInferenceQueue()
            val genericEvents = drainV2Queue()
            if (inferenceEvents.isEmpty() && genericEvents.isEmpty()) return

            val totalCount = inferenceEvents.size + genericEvents.size
            Timber.d("Flushing %d telemetry events", totalCount)

            if (sender != null) {
                try {
                    val otlpRequest = buildOtlpRequest(inferenceEvents, genericEvents)
                    sender.sendBatch(otlpRequest)
                    Timber.d("Telemetry flush succeeded: %d events", totalCount)
                } catch (e: Exception) {
                    Timber.w(e, "Telemetry flush failed, persisting %d events to disk", totalCount)
                    persistV2Events(buildMergedEvents(inferenceEvents, genericEvents))
                }
            } else {
                // No sender configured -- persist locally for later retrieval
                persistV2Events(buildMergedEvents(inferenceEvents, genericEvents))
            }
        }
    }

    /**
     * Build a v2 OTLP batch request from inference events and generic v2 events,
     * merging them into a single batch.
     *
     * @deprecated Use [buildOtlpRequest] for the canonical OTLP/JSON envelope.
     */
    internal fun buildMergedBatch(
        inferenceEvents: List<InferenceTelemetryEvent>,
        genericEvents: List<TelemetryV2Event>,
    ): TelemetryV2BatchRequest {
        val resource = buildResource()
        val allEvents = buildMergedEvents(inferenceEvents, genericEvents)
        return TelemetryV2BatchRequest(resource = resource, events = allEvents)
    }

    /**
     * Build an OTLP/JSON [ExportLogsServiceRequest] from inference events
     * and generic v2 events.
     */
    internal fun buildOtlpRequest(
        inferenceEvents: List<InferenceTelemetryEvent>,
        genericEvents: List<TelemetryV2Event>,
    ): ExportLogsServiceRequest {
        val v2Events = buildMergedEvents(inferenceEvents, genericEvents)
        return wrapInOtlp(v2Events)
    }

    /**
     * Build an OTLP/JSON [ExportLogsServiceRequest] for a single funnel event.
     */
    internal fun buildOtlpFunnelRequest(event: FunnelEvent): ExportLogsServiceRequest {
        val legacy = buildV2FunnelBatch(event)
        return wrapInOtlp(legacy.events, legacy.resource)
    }

    /**
     * Convert a list of v2 events into the OTLP/JSON envelope.
     */
    private fun wrapInOtlp(
        events: List<TelemetryV2Event>,
        v2Resource: TelemetryV2Resource? = null,
    ): ExportLogsServiceRequest {
        val res = v2Resource ?: buildResource()
        val resourceAttrs = listOf(
            KeyValue("service.name", AnyValue.StringValue("octomil-android-sdk")),
            KeyValue("service.version", AnyValue.StringValue(res.sdkVersion)),
            KeyValue("telemetry.sdk.name", AnyValue.StringValue(res.sdk)),
            KeyValue("telemetry.sdk.language", AnyValue.StringValue(res.platform)),
        ) + listOfNotNull(
            res.deviceId?.let { KeyValue("device.id", AnyValue.StringValue(it)) },
            res.deviceId?.let { KeyValue(OtlpResourceAttribute.OCTOMIL_DEVICE_ID, AnyValue.StringValue(it)) },
            res.orgId?.let { KeyValue("org.id", AnyValue.StringValue(it)) },
        )

        val logRecords = events.map { ev ->
            val attrs = ev.attributes.map { (k, v) ->
                val anyValue: AnyValue = when {
                    v.isString -> AnyValue.StringValue(v.content)
                    v.content.toBooleanStrictOrNull() != null ->
                        AnyValue.BoolValue(v.content.toBooleanStrict())
                    v.content.toLongOrNull() != null ->
                        AnyValue.IntValue(v.content.toLong())
                    v.content.toDoubleOrNull() != null ->
                        AnyValue.DoubleValue(v.content.toDouble())
                    else -> AnyValue.StringValue(v.content)
                }
                KeyValue(k, anyValue)
            } + listOfNotNull(
                ev.traceId?.let { KeyValue("trace_id", AnyValue.StringValue(it)) },
                ev.spanId?.let { KeyValue("span_id", AnyValue.StringValue(it)) },
            )

            LogRecord(
                timeUnixNano = toNanos(ev.timestamp),
                body = AnyValue.StringValue(ev.name),
                attributes = attrs.ifEmpty { null },
                traceId = ev.traceId,
                spanId = ev.spanId,
            )
        }

        return ExportLogsServiceRequest(
            resourceLogs = listOf(
                ResourceLogs(
                    resource = OtlpResource(attributes = resourceAttrs),
                    scopeLogs = listOf(
                        ScopeLogs(
                            scope = InstrumentationScope(
                                name = "ai.octomil.android",
                                version = BuildConfig.OCTOMIL_VERSION,
                            ),
                            logRecords = logRecords,
                        ),
                    ),
                ),
            ),
        )
    }

    /**
     * Convert ISO-8601 timestamp to nanoseconds since epoch (as string).
     */
    private fun toNanos(isoTimestamp: String): String {
        return try {
            val millis = isoFormatter.parse(isoTimestamp)?.time ?: 0L
            "${millis * 1_000_000}"
        } catch (_: Exception) {
            "0"
        }
    }

    /**
     * Build merged list of v2 events from inference events and generic v2 events.
     */
    private fun buildMergedEvents(
        inferenceEvents: List<InferenceTelemetryEvent>,
        genericEvents: List<TelemetryV2Event>,
    ): List<TelemetryV2Event> {
        val converted = inferenceEvents.map { event ->
            val name = if (event.success) ContractTelemetryEvent.INFERENCE_COMPLETED else ContractTelemetryEvent.INFERENCE_FAILED
            val attrs = mutableMapOf<String, JsonPrimitive>(
                "model.id" to JsonPrimitive(event.modelId),
                "inference.duration_ms" to JsonPrimitive(event.latencyMs),
                "inference.modality" to JsonPrimitive("on_device"),
                "device.compute_unit" to JsonPrimitive("cpu"),
                "model.format" to JsonPrimitive("tflite"),
                "inference.success" to JsonPrimitive(event.success),
                SpanAttribute.LOCALITY to JsonPrimitive("on_device"),
            )
            if (event.errorMessage != null) {
                attrs["error.message"] = JsonPrimitive(event.errorMessage)
            }

            TelemetryV2Event(
                name = name,
                timestamp = isoFormatter.format(Date(event.timestampMs)),
                attributes = attrs,
            )
        }
        return converted + genericEvents
    }

    /**
     * Build a v2 OTLP batch request from a list of inference telemetry events.
     */
    internal fun buildV2Batch(events: List<InferenceTelemetryEvent>): TelemetryV2BatchRequest {
        return buildMergedBatch(events, emptyList())
    }

    /**
     * Build a v2 OTLP batch request for a single funnel event.
     */
    internal fun buildV2FunnelBatch(event: FunnelEvent): TelemetryV2BatchRequest {
        val resource = TelemetryV2Resource(
            sdk = "android",
            sdkVersion = event.sdkVersion ?: BuildConfig.OCTOMIL_VERSION,
            deviceId = event.deviceId ?: deviceId,
            platform = "android",
            orgId = orgId,
        )

        val attrs = mutableMapOf<String, JsonPrimitive>()
        attrs["funnel.success"] = JsonPrimitive(event.success)
        attrs["funnel.source"] = JsonPrimitive(event.source)
        event.modelId?.let { attrs["model.id"] = JsonPrimitive(it) }
        event.rolloutId?.let { attrs["funnel.rollout_id"] = JsonPrimitive(it) }
        event.sessionId?.let { attrs["funnel.session_id"] = JsonPrimitive(it) }
        event.failureReason?.let { attrs["error.message"] = JsonPrimitive(it) }
        event.failureCategory?.let { attrs["error.category"] = JsonPrimitive(it) }
        event.durationMs?.let { attrs["funnel.duration_ms"] = JsonPrimitive(it) }
        event.metadata?.forEach { (k, v) -> attrs["funnel.metadata.$k"] = JsonPrimitive(v) }

        val v2Event = TelemetryV2Event(
            name = "funnel.${event.stage}",
            timestamp = isoFormatter.format(Date()),
            attributes = attrs,
        )

        return TelemetryV2BatchRequest(resource = resource, events = listOf(v2Event))
    }

    /**
     * Build the resource block for v2 batches.
     */
    private fun buildResource(): TelemetryV2Resource {
        return TelemetryV2Resource(
            sdk = "android",
            sdkVersion = BuildConfig.OCTOMIL_VERSION,
            deviceId = deviceId,
            platform = "android",
            orgId = orgId,
        )
    }

    /**
     * Format current time as ISO 8601.
     */
    internal fun formatTimestamp(epochMs: Long = System.currentTimeMillis()): String {
        return isoFormatter.format(Date(epochMs))
    }

    /**
     * Drain all events from the inference queue into a list.
     */
    private fun drainInferenceQueue(): List<InferenceTelemetryEvent> {
        val events = mutableListOf<InferenceTelemetryEvent>()
        while (true) {
            val event = inferenceQueue.poll() ?: break
            events.add(event)
        }
        return events
    }

    /**
     * Drain all events from the generic v2 queue into a list.
     */
    private fun drainV2Queue(): List<TelemetryV2Event> {
        val events = mutableListOf<TelemetryV2Event>()
        while (true) {
            val event = v2Queue.poll() ?: break
            events.add(event)
        }
        return events
    }

    /**
     * Persist v2 events to disk so they survive process restarts.
     */
    private fun persistV2Events(events: List<TelemetryV2Event>) {
        val dir = persistDir ?: return
        try {
            dir.mkdirs()
            // Enforce maximum persisted file count to prevent unbounded disk usage
            val existing = dir.listFiles()?.size ?: 0
            if (existing >= MAX_PERSISTED_FILES) {
                val oldest = dir.listFiles()?.minByOrNull { it.lastModified() }
                oldest?.delete()
            }
            val fileName = "telemetry_${System.currentTimeMillis()}.json"
            val file = File(dir, fileName)
            file.writeText(json.encodeToString(events))
            Timber.d("Persisted %d events to %s", events.size, file.name)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist telemetry events")
        }
    }

    /**
     * Load previously persisted events and attempt to send them.
     *
     * Events are now stored as `List<TelemetryV2Event>`. Files persisted in the
     * old `List<InferenceTelemetryEvent>` format are discarded on parse failure.
     */
    private suspend fun loadPersistedEvents() {
        val dir = persistDir ?: return
        if (!dir.exists()) return
        val sender = this.sender ?: return

        val files = dir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }
            ?: return

        for (file in files) {
            try {
                val events: List<TelemetryV2Event> = json.decodeFromString(file.readText())
                val otlpRequest = wrapInOtlp(events)
                sender.sendBatch(otlpRequest)
                file.delete()
                Timber.d("Resent %d persisted events from %s", events.size, file.name)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load/resend persisted events from %s, discarding", file.name)
                // Old format or corrupt -- discard gracefully
                file.delete()
            }
        }
    }

    /**
     * Stop the flush timer and flush remaining events.
     */
    override fun close() {
        flushJob?.cancel()
        // Best-effort synchronous drain: persist any remaining events
        val remainingInference = drainInferenceQueue()
        val remainingV2 = drainV2Queue()
        if (remainingInference.isNotEmpty() || remainingV2.isNotEmpty()) {
            persistV2Events(buildMergedEvents(remainingInference, remainingV2))
        }
        scope.cancel()
        if (shared === this) {
            shared = null
        }
    }

    // =========================================================================
    // Named convenience methods — cross-SDK convention: report{EventName}
    // =========================================================================

    // ---- Deploy ----

    /**
     * Report that a model deploy has started.
     */
    fun reportDeployStarted(modelId: String, version: String) {
        enqueueV2Event(
            TelemetryV2Event(
                name = ContractTelemetryEvent.DEPLOY_STARTED,
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "model.version" to version,
                ),
            ),
        )
    }

    /**
     * Report that a model deploy completed successfully.
     */
    fun reportDeployCompleted(modelId: String, version: String, durationMs: Double) {
        enqueueV2Event(
            TelemetryV2Event(
                name = ContractTelemetryEvent.DEPLOY_COMPLETED,
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "model.version" to version,
                    "deploy.duration_ms" to durationMs,
                ),
            ),
        )
    }

    /**
     * Report that a model deploy failed.
     */
    fun reportDeployFailed(modelId: String, errorType: String, errorMessage: String) {
        enqueueV2Event(
            TelemetryV2Event(
                name = "deploy.failed",
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "error.message" to errorMessage,
                    "error.code" to errorType,
                ),
            ),
        )
    }

    // ---- Training ----

    /**
     * Report that on-device training has started.
     */
    fun reportTrainingStarted(modelId: String, version: String = "", roundId: String = "") {
        enqueueV2Event(
            TelemetryV2Event(
                name = "training.started",
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "model.version" to version,
                    "training.round_id" to roundId,
                ),
            ),
        )
    }

    /**
     * Report that on-device training completed successfully.
     */
    fun reportTrainingCompleted(
        modelId: String,
        version: String = "",
        durationMs: Double,
        loss: Double? = null,
        accuracy: Double? = null,
        sampleCount: Int? = null,
        roundId: String = "",
    ) {
        enqueueV2Event(
            TelemetryV2Event(
                name = "training.completed",
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "model.version" to version,
                    "training.duration_ms" to durationMs,
                    "training.loss" to loss,
                    "training.accuracy" to accuracy,
                    "training.sample_count" to sampleCount,
                    "training.round_id" to roundId,
                ),
            ),
        )
    }

    /**
     * Report that on-device training failed.
     */
    fun reportTrainingFailed(modelId: String, errorMessage: String) {
        enqueueV2Event(
            TelemetryV2Event(
                name = "training.failed",
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "error.message" to errorMessage,
                ),
            ),
        )
    }

    /**
     * Report a weight upload after training.
     */
    fun reportWeightUpload(
        modelId: String,
        roundId: String,
        sampleCount: Int? = null,
        secureAggregation: Boolean = false,
    ) {
        enqueueV2Event(
            TelemetryV2Event(
                name = "training.weight_upload",
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "training.round_id" to roundId,
                    "training.secure_aggregation" to secureAggregation,
                    "training.sample_count" to sampleCount,
                ),
            ),
        )
    }

    // ---- Experiment ----

    /**
     * Report that a device was assigned to an experiment variant.
     */
    fun reportExperimentAssigned(modelId: String, experimentId: String, variant: String = "") {
        enqueueV2Event(
            TelemetryV2Event(
                name = "experiment.assigned",
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "experiment.id" to experimentId,
                    "experiment.source" to variant,
                ),
            ),
        )
    }

    /**
     * Report an experiment metric observation.
     */
    fun reportExperimentMetric(experimentId: String, metricName: String, metricValue: Double) {
        enqueueV2Event(
            TelemetryV2Event(
                name = "experiment.metric",
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "experiment.id" to experimentId,
                    "experiment.metric_name" to metricName,
                    "experiment.metric_value" to metricValue,
                ),
            ),
        )
    }

    // ---- Inference ----

    /**
     * Report that an inference has started.
     */
    fun reportInferenceStarted(modelId: String, locality: String = "on_device") {
        enqueueV2Event(
            TelemetryV2Event(
                name = ContractTelemetryEvent.INFERENCE_STARTED,
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "inference.modality" to "on_device",
                    "device.compute_unit" to "cpu",
                    "model.format" to "tflite",
                    SpanAttribute.LOCALITY to locality,
                ),
            ),
        )
    }

    /**
     * Report that a streaming inference chunk was produced.
     *
     * Emits an `inference.chunk_produced` event per the SDK Facade Contract.
     */
    fun reportInferenceChunkProduced(modelId: String, chunkIndex: Int) {
        enqueueV2Event(
            TelemetryV2Event(
                name = ContractTelemetryEvent.INFERENCE_CHUNK_PRODUCED,
                timestamp = formatTimestamp(),
                attributes = TelemetryAttributes.of(
                    "model.id" to modelId,
                    "inference.chunk_index" to chunkIndex,
                ),
            ),
        )
    }

    /**
     * Report a funnel analytics event. Fire-and-forget via coroutine scope.
     */
    fun reportFunnelEvent(
        stage: String,
        success: Boolean = true,
        deviceId: String? = null,
        modelId: String? = null,
        rolloutId: String? = null,
        sessionId: String? = null,
        failureReason: String? = null,
        failureCategory: String? = null,
        durationMs: Int? = null,
        platform: String? = "android",
        metadata: Map<String, String>? = null,
    ) {
        scope.launch {
            try {
                val event = FunnelEvent(
                    stage = stage,
                    success = success,
                    deviceId = deviceId,
                    modelId = modelId,
                    rolloutId = rolloutId,
                    sessionId = sessionId,
                    failureReason = failureReason,
                    failureCategory = failureCategory,
                    durationMs = durationMs,
                    sdkVersion = BuildConfig.OCTOMIL_VERSION,
                    platform = platform,
                    metadata = metadata,
                )
                val otlpRequest = buildOtlpFunnelRequest(event)
                sender?.sendBatch(otlpRequest)
            } catch (e: Exception) {
                Timber.d(e, "Funnel event reporting failed")
            }
        }
    }
}

/**
 * A single funnel analytics event.
 */
@Serializable
data class FunnelEvent(
    val stage: String,
    val success: Boolean = true,
    val source: String = "sdk_android",
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("rollout_id") val rolloutId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("failure_reason") val failureReason: String? = null,
    @SerialName("failure_category") val failureCategory: String? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    @SerialName("sdk_version") val sdkVersion: String? = null,
    val platform: String? = "android",
    val metadata: Map<String, String>? = null,
)

/**
 * Abstraction for sending telemetry to the server via the OTLP/JSON envelope.
 *
 * Implementations should throw on failure so the queue can fall back to
 * disk persistence.
 */
fun interface TelemetrySender {
    /**
     * Send an OTLP/JSON logs request to POST /api/v2/telemetry/events.
     *
     * @throws Exception if the send fails (events will be persisted to disk).
     */
    suspend fun sendBatch(batch: ExportLogsServiceRequest)
}
