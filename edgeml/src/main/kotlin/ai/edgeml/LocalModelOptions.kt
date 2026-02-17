package ai.edgeml

import ai.edgeml.config.EdgeMLConfig

/**
 * Configuration options for local-first model loading.
 *
 * Controls hardware acceleration and threading without requiring any server
 * configuration. Use with [EdgeML.loadModel] to load models for purely local
 * inference.
 *
 * ```kotlin
 * val options = LocalModelOptions(
 *     enableGpu = true,
 *     numThreads = 4,
 * )
 * val model = EdgeML.loadModel(context, "classifier.tflite", options)
 * ```
 *
 * @property enableGpu Enable TensorFlow Lite GPU delegate for acceleration.
 * @property enableFloat16 Allow float16 precision on GPU (~2x throughput).
 * @property enableNnapi Enable NNAPI delegate (Android 8.1–14).
 * @property enableVendorNpu Use vendor NPU delegates (Qualcomm QNN, Samsung Eden, MediaTek NeuroPilot).
 * @property preferBigCores Pin threads to performance cores on ARM big.LITTLE SoCs.
 * @property numThreads Number of TFLite interpreter threads (overridden by [preferBigCores] when active).
 */
data class LocalModelOptions(
    val enableGpu: Boolean = true,
    val enableFloat16: Boolean = false,
    val enableNnapi: Boolean = false,
    val enableVendorNpu: Boolean = false,
    val preferBigCores: Boolean = true,
    val numThreads: Int = 4,
) {
    /**
     * Convert to an internal [EdgeMLConfig] for use with [ai.edgeml.training.TFLiteTrainer].
     *
     * Server-related fields are set to placeholder values that satisfy validation
     * but are never accessed on the inference path.
     */
    internal fun toInternalConfig(): EdgeMLConfig = EdgeMLConfig(
        serverUrl = "https://localhost",
        deviceAccessToken = "local",
        orgId = "local",
        modelId = "local",
        enableGpuAcceleration = enableGpu,
        enableFloat16Inference = enableFloat16,
        enableNnapi = enableNnapi,
        enableVendorNpu = enableVendorNpu,
        preferBigCores = preferBigCores,
        numThreads = numThreads,
        enableBackgroundSync = false,
        enableHeartbeat = false,
    )
}
