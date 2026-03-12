package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OTLP/JSON Logs export envelope.
 *
 * Wire format follows the OpenTelemetry LogsService specification so
 * the server can ingest SDK telemetry via a standard OTLP collector.
 *
 * @see <a href="https://opentelemetry.io/docs/specs/otlp/#otlphttp-request">OTLP/HTTP spec</a>
 */
@Serializable
data class ExportLogsServiceRequest(
    @SerialName("resourceLogs")
    val resourceLogs: List<ResourceLogs>,
)

@Serializable
data class ResourceLogs(
    val resource: OtlpResource,
    @SerialName("scopeLogs")
    val scopeLogs: List<ScopeLogs>,
)

@Serializable
data class OtlpResource(
    val attributes: List<KeyValue>,
)

@Serializable
data class ScopeLogs(
    val scope: InstrumentationScope,
    @SerialName("logRecords")
    val logRecords: List<LogRecord>,
)

@Serializable
data class InstrumentationScope(
    val name: String,
    val version: String = "",
)

@Serializable
data class LogRecord(
    @SerialName("timeUnixNano")
    val timeUnixNano: String,
    val severityNumber: Int? = null,
    val body: AnyValue? = null,
    val attributes: List<KeyValue>? = null,
    val traceId: String? = null,
    val spanId: String? = null,
)

@Serializable
data class KeyValue(
    val key: String,
    val value: AnyValue,
)

/**
 * OTLP AnyValue — a typed wrapper for attribute values.
 *
 * Only the primitive variants (string, int, double, bool) are used by
 * the SDK today. Array and kvlist variants can be added if needed.
 */
@Serializable
sealed class AnyValue {
    @Serializable
    @SerialName("stringValue")
    data class StringValue(
        @SerialName("stringValue")
        val stringValue: String,
    ) : AnyValue()

    @Serializable
    @SerialName("intValue")
    data class IntValue(
        @SerialName("intValue")
        val intValue: Long,
    ) : AnyValue()

    @Serializable
    @SerialName("doubleValue")
    data class DoubleValue(
        @SerialName("doubleValue")
        val doubleValue: Double,
    ) : AnyValue()

    @Serializable
    @SerialName("boolValue")
    data class BoolValue(
        @SerialName("boolValue")
        val boolValue: Boolean,
    ) : AnyValue()
}
