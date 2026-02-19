package ai.edgeml.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pairing session returned from the server.
 *
 * Represents the state of a QR-code-initiated pairing between this device
 * and the EdgeML dashboard. The pairing code is the secret — no auth token
 * is required for pairing endpoints.
 */
@Serializable
data class PairingSession(
    @SerialName("id")
    val id: String,
    @SerialName("code")
    val code: String,
    @SerialName("model_name")
    val modelName: String,
    @SerialName("model_version")
    val modelVersion: String? = null,
    @SerialName("status")
    val status: PairingStatus,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("download_format")
    val downloadFormat: String? = null,
    @SerialName("download_size_bytes")
    val downloadSizeBytes: Long? = null,
    @SerialName("device_tier")
    val deviceTier: String? = null,
    @SerialName("quantization")
    val quantization: String? = null,
    @SerialName("executor")
    val executor: String? = null,
)

/**
 * Status of a pairing session.
 */
@Serializable
enum class PairingStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("connected")
    CONNECTED,

    @SerialName("deploying")
    DEPLOYING,

    @SerialName("done")
    DONE,

    @SerialName("expired")
    EXPIRED,

    @SerialName("cancelled")
    CANCELLED,
}

/**
 * Deployment information extracted from a pairing session once the model
 * is ready for download.
 */
@Serializable
data class DeploymentInfo(
    @SerialName("model_name")
    val modelName: String,
    @SerialName("model_version")
    val modelVersion: String,
    @SerialName("download_url")
    val downloadUrl: String,
    @SerialName("format")
    val format: String,
    @SerialName("quantization")
    val quantization: String? = null,
    @SerialName("executor")
    val executor: String? = null,
    @SerialName("size_bytes")
    val sizeBytes: Long? = null,
)

/**
 * Benchmark report sent to the server after running model benchmarks on-device.
 *
 * Captures device hardware specs alongside inference performance metrics so the
 * dashboard can display per-device benchmark results.
 */
@Serializable
data class BenchmarkReport(
    @SerialName("model_name")
    val modelName: String,
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("chip_family")
    val chipFamily: String,
    @SerialName("ram_gb")
    val ramGb: Double,
    @SerialName("os_version")
    val osVersion: String,
    @SerialName("ttft_ms")
    val ttftMs: Double,
    @SerialName("tpot_ms")
    val tpotMs: Double,
    @SerialName("tokens_per_second")
    val tokensPerSecond: Double,
    @SerialName("p50_latency_ms")
    val p50LatencyMs: Double,
    @SerialName("p95_latency_ms")
    val p95LatencyMs: Double,
    @SerialName("p99_latency_ms")
    val p99LatencyMs: Double,
    @SerialName("memory_peak_bytes")
    val memoryPeakBytes: Long,
    @SerialName("inference_count")
    val inferenceCount: Int,
    @SerialName("model_load_time_ms")
    val modelLoadTimeMs: Double,
    @SerialName("cold_inference_ms")
    val coldInferenceMs: Double,
    @SerialName("warm_inference_ms")
    val warmInferenceMs: Double,
    @SerialName("battery_level")
    val batteryLevel: Float? = null,
    @SerialName("thermal_state")
    val thermalState: String? = null,
)

/**
 * Request body for connecting a device to a pairing session.
 *
 * Sends device hardware metadata so the server can select the optimal model
 * variant (format, quantization, executor) for this device.
 */
@Serializable
data class DeviceConnectRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("chip_family")
    val chipFamily: String? = null,
    @SerialName("ram_gb")
    val ramGb: Double? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("npu_available")
    val npuAvailable: Boolean? = null,
    @SerialName("gpu_available")
    val gpuAvailable: Boolean? = null,
)
