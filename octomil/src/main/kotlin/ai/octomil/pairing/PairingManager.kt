package ai.octomil.pairing

import ai.octomil.api.OctomilApi
import ai.octomil.wrapper.TelemetryQueue
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages the device pairing flow for QR-code-initiated deployments.
 *
 * The pairing flow connects a physical device to the Octomil dashboard so a model
 * can be deployed and benchmarked on real hardware. The full sequence is:
 *
 * 1. **Connect** — Device scans QR code, sends hardware metadata to the server.
 * 2. **Wait** — Poll for deployment; the server selects the optimal model variant.
 * 3. **Download** — Fetch the model binary from the pre-signed URL.
 * 4. **Benchmark** — Run inference benchmarks on-device.
 * 5. **Report** — Submit benchmark results back to the server.
 *
 * ## Usage
 *
 * ```kotlin
 * val pairingManager = PairingManager(api, context)
 *
 * // Full flow in one call:
 * val report = pairingManager.pair("ABC123")
 *
 * // Or step by step:
 * val session = pairingManager.connect("ABC123")
 * val deployment = pairingManager.waitForDeployment("ABC123")
 * val report = pairingManager.executeDeployment(deployment)
 * ```
 */
/**
 * Result of executing a deployment: benchmark report plus the persisted model file.
 */
data class DeploymentResult(
    val report: BenchmarkReport,
    /** Path to the model file persisted on disk for inference. Null if persistence failed. */
    val modelFilePath: String?,
)

class PairingManager(
    private val api: OctomilApi,
    private val context: Context,
    private val benchmarkRunner: BenchmarkRunner = BenchmarkRunner(context),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    private val modelCacheDir = File(context.cacheDir, "octomil_pairing")
    private val modelPersistDir = File(context.filesDir, "octomil_models")

    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    init {
        modelCacheDir.mkdirs()
        modelPersistDir.mkdirs()
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Connect to a pairing session using the code from a QR scan.
     *
     * Sends device hardware metadata to the server so it can select the optimal
     * model variant for this device.
     *
     * @param code Pairing code from QR scan.
     * @return The updated pairing session.
     * @throws PairingException if the connection fails.
     */
    suspend fun connect(code: String): PairingSession {
        Timber.i("Connecting to pairing session: %s", code)

        val deviceInfo = DeviceCapabilities.collect(context)
        val response = api.connectToPairing(code, deviceInfo)

        if (!response.isSuccessful) {
            val errorCode = response.code()
            throw PairingException(
                "Failed to connect to pairing session: HTTP $errorCode",
                PairingException.ErrorCode.fromHttpStatus(errorCode),
            )
        }

        val session = response.body()
            ?: throw PairingException(
                "Empty response from pairing connect",
                PairingException.ErrorCode.SERVER_ERROR,
            )

        Timber.i(
            "Connected to pairing session: id=%s model=%s status=%s",
            session.id, session.modelName, session.status,
        )

        try {
            TelemetryQueue.shared?.reportFunnelEvent(
                stage = "app_pair",
                success = true,
                deviceId = deviceInfo.deviceId,
                platform = "android",
            )
        } catch (_: Exception) {
            // Never break pairing flow
        }

        return session
    }

    /**
     * Poll for deployment readiness.
     *
     * Blocks until the server transitions the pairing session to a state where
     * the model is ready for download, or until the timeout expires.
     *
     * @param code Pairing code.
     * @param timeoutMs Maximum time to wait in milliseconds (default: 5 minutes).
     * @return Deployment information with download URL.
     * @throws PairingException on timeout, expiry, or cancellation.
     */
    suspend fun waitForDeployment(
        code: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): DeploymentInfo {
        Timber.d("Waiting for deployment on pairing code: %s (timeout=%dms)", code, timeoutMs)

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastStatus: PairingStatus? = null

        while (System.currentTimeMillis() < deadline) {
            val response = api.getPairingSession(code)

            if (!response.isSuccessful) {
                throw PairingException(
                    "Failed to poll pairing session: HTTP ${response.code()}",
                    PairingException.ErrorCode.fromHttpStatus(response.code()),
                )
            }

            val session = response.body()
                ?: throw PairingException(
                    "Empty response when polling pairing session",
                    PairingException.ErrorCode.SERVER_ERROR,
                )

            if (session.status != lastStatus) {
                Timber.d("Pairing status changed: %s -> %s", lastStatus, session.status)
                lastStatus = session.status
            }

            when (session.status) {
                PairingStatus.DEPLOYING, PairingStatus.DONE -> {
                    // Model is ready
                    val downloadUrl = session.downloadUrl
                        ?: throw PairingException(
                            "Session is ${session.status} but download URL is missing",
                            PairingException.ErrorCode.SERVER_ERROR,
                        )

                    return DeploymentInfo(
                        modelName = session.modelName,
                        modelVersion = session.modelVersion ?: "unknown",
                        downloadUrl = downloadUrl,
                        format = session.downloadFormat ?: "tensorflow_lite",
                        quantization = session.quantization,
                        executor = session.executor,
                        sizeBytes = session.downloadSizeBytes,
                    )
                }

                PairingStatus.EXPIRED -> {
                    throw PairingException(
                        "Pairing session expired",
                        PairingException.ErrorCode.EXPIRED,
                    )
                }

                PairingStatus.CANCELLED -> {
                    throw PairingException(
                        "Pairing session was cancelled",
                        PairingException.ErrorCode.CANCELLED,
                    )
                }

                PairingStatus.PENDING, PairingStatus.CONNECTED -> {
                    // Still waiting, continue polling
                }
            }

            try {
                delay(pollIntervalMs)
            } catch (e: CancellationException) {
                throw PairingException(
                    "Pairing was cancelled",
                    PairingException.ErrorCode.CANCELLED,
                    e,
                )
            }
        }

        throw PairingException(
            "Timed out waiting for deployment after ${timeoutMs}ms",
            PairingException.ErrorCode.TIMEOUT,
        )
    }

    /**
     * Download the model, run benchmarks, and report results.
     *
     * @param deployment Deployment information from [waitForDeployment].
     * @return Benchmark report with device performance metrics.
     * @throws PairingException on download or benchmark failure.
     */
    suspend fun executeDeployment(deployment: DeploymentInfo): DeploymentResult {
        Timber.i(
            "Executing deployment: model=%s version=%s format=%s",
            deployment.modelName, deployment.modelVersion, deployment.format,
        )

        // Download model
        val modelFile = downloadModel(deployment)

        try {
            // Run benchmarks
            val deviceInfo = DeviceCapabilities.collect(context)
            val report = benchmarkRunner.run(modelFile, deployment.modelName, deviceInfo)

            Timber.i(
                "Benchmark complete: p50=%.1fms p95=%.1fms tps=%.1f",
                report.p50LatencyMs, report.p95LatencyMs, report.tokensPerSecond,
            )

            // Persist model to stable location for inference
            val persistedFile = persistModel(modelFile, deployment)

            return DeploymentResult(report = report, modelFilePath = persistedFile?.absolutePath)
        } catch (e: Exception) {
            // Clean up on failure
            if (modelFile.exists()) modelFile.delete()
            throw e
        }
    }

    /**
     * Full pairing flow: connect, wait for deployment, download, benchmark, report.
     *
     * This is the recommended single-call API for the complete pairing sequence.
     *
     * @param code Pairing code from QR scan.
     * @param timeoutMs Maximum time to wait for deployment (default: 5 minutes).
     * @return Benchmark report submitted to the server.
     * @throws PairingException on any failure in the flow.
     */
    suspend fun pair(
        code: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): DeploymentResult {
        Timber.i("Starting full pairing flow for code: %s", code)

        // Step 1: Connect
        connect(code)

        // Step 2: Wait for deployment
        val deployment = waitForDeployment(code, timeoutMs)

        // Step 3: Download + benchmark + persist
        val result = executeDeployment(deployment)

        // Step 4: Report results
        submitBenchmark(code, result.report)

        Timber.i("Pairing flow complete for code: %s", code)
        return result
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Submit benchmark results to the server.
     */
    internal suspend fun submitBenchmark(code: String, report: BenchmarkReport) {
        Timber.d("Submitting benchmark for pairing code: %s", code)

        val response = api.submitBenchmark(code, report)
        if (!response.isSuccessful) {
            Timber.w("Failed to submit benchmark: HTTP %d", response.code())
            // Non-fatal: the benchmark ran successfully even if reporting failed
        } else {
            Timber.i("Benchmark submitted successfully")
        }
    }

    /**
     * Download model from pre-signed URL to a temporary file.
     */
    internal fun downloadModel(deployment: DeploymentInfo): File {
        val extension = when {
            deployment.format.contains("tflite", ignoreCase = true) ||
                deployment.format.contains("tensorflow_lite", ignoreCase = true) -> ".tflite"
            deployment.format.contains("onnx", ignoreCase = true) -> ".onnx"
            else -> ".bin"
        }

        val modelFile = File(
            modelCacheDir,
            "pairing_${deployment.modelName}_${deployment.modelVersion}$extension",
        )

        Timber.d(
            "Downloading model from %s (expected size: %s bytes)",
            deployment.downloadUrl,
            deployment.sizeBytes ?: "unknown",
        )

        val request = Request.Builder()
            .url(deployment.downloadUrl)
            .build()

        val response = downloadClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw PairingException(
                "Model download failed: HTTP ${response.code}",
                PairingException.ErrorCode.DOWNLOAD_FAILED,
            )
        }

        val body = response.body
            ?: throw PairingException(
                "Empty response body during model download",
                PairingException.ErrorCode.DOWNLOAD_FAILED,
            )

        try {
            FileOutputStream(modelFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
        } catch (e: Exception) {
            modelFile.delete()
            throw PairingException(
                "Failed to save model file: ${e.message}",
                PairingException.ErrorCode.DOWNLOAD_FAILED,
                e,
            )
        }

        Timber.i("Model downloaded: %s (%d bytes)", modelFile.absolutePath, modelFile.length())
        return modelFile
    }

    /**
     * Move the downloaded model to a stable directory for on-device inference.
     *
     * Persists to: `filesDir/octomil_models/{modelName}/{version}/model.{ext}`
     */
    private fun persistModel(modelFile: File, deployment: DeploymentInfo): File? {
        return try {
            val dir = File(modelPersistDir, "${deployment.modelName}/${deployment.modelVersion}")
            dir.mkdirs()
            val target = File(dir, modelFile.name)
            if (target.exists()) target.delete()
            if (modelFile.renameTo(target)) {
                Timber.i("Model persisted: %s", target.absolutePath)
                target
            } else {
                // renameTo can fail across filesystems; fall back to copy
                modelFile.copyTo(target, overwrite = true)
                modelFile.delete()
                Timber.i("Model persisted (copy): %s", target.absolutePath)
                target
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist model file, cleaning up")
            modelFile.delete()
            null
        }
    }

    companion object {
        /** Default poll interval: 2 seconds. */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 2_000L

        /** Default timeout: 5 minutes. */
        const val DEFAULT_TIMEOUT_MS: Long = 300_000L

        /**
         * Look up a persisted model file by name and version.
         *
         * @return The model [File] if it exists on disk, or null.
         */
        fun getModelFile(context: Context, modelName: String, modelVersion: String): File? {
            val dir = File(context.filesDir, "octomil_models/$modelName/$modelVersion")
            if (!dir.exists()) return null
            return dir.listFiles()?.firstOrNull { it.isFile }
        }
    }
}

/**
 * Exception thrown when a pairing operation fails.
 *
 * Extends [ai.octomil.errors.OctomilException] with the pairing error code mapped
 * to the canonical [ai.octomil.errors.OctomilErrorCode].
 */
class PairingException(
    message: String,
    val pairingErrorCode: ErrorCode = ErrorCode.UNKNOWN,
    cause: Throwable? = null,
) : ai.octomil.errors.OctomilException(pairingErrorCode.toOctomilErrorCode(), message, cause) {

    enum class ErrorCode {
        /** Network or HTTP error. */
        NETWORK_ERROR,

        /** Pairing session not found. */
        NOT_FOUND,

        /** Pairing session expired. */
        EXPIRED,

        /** Pairing session was cancelled. */
        CANCELLED,

        /** Authentication or authorization error. */
        UNAUTHORIZED,

        /** Server returned an error. */
        SERVER_ERROR,

        /** Timed out waiting for deployment. */
        TIMEOUT,

        /** Model download failed. */
        DOWNLOAD_FAILED,

        /** Benchmark execution failed. */
        BENCHMARK_FAILED,

        /** Unknown error. */
        UNKNOWN;

        fun toOctomilErrorCode(): ai.octomil.errors.OctomilErrorCode = when (this) {
            NETWORK_ERROR -> ai.octomil.errors.OctomilErrorCode.NETWORK_UNAVAILABLE
            NOT_FOUND -> ai.octomil.errors.OctomilErrorCode.MODEL_NOT_FOUND
            EXPIRED -> ai.octomil.errors.OctomilErrorCode.REQUEST_TIMEOUT
            CANCELLED -> ai.octomil.errors.OctomilErrorCode.CANCELLED
            UNAUTHORIZED -> ai.octomil.errors.OctomilErrorCode.AUTHENTICATION_FAILED
            SERVER_ERROR -> ai.octomil.errors.OctomilErrorCode.SERVER_ERROR
            TIMEOUT -> ai.octomil.errors.OctomilErrorCode.REQUEST_TIMEOUT
            DOWNLOAD_FAILED -> ai.octomil.errors.OctomilErrorCode.DOWNLOAD_FAILED
            BENCHMARK_FAILED -> ai.octomil.errors.OctomilErrorCode.UNKNOWN
            UNKNOWN -> ai.octomil.errors.OctomilErrorCode.UNKNOWN
        }

        companion object {
            fun fromHttpStatus(code: Int): ErrorCode = when (code) {
                401, 403 -> UNAUTHORIZED
                404 -> NOT_FOUND
                408 -> TIMEOUT
                410 -> EXPIRED
                in 500..599 -> SERVER_ERROR
                else -> NETWORK_ERROR
            }
        }
    }
}
