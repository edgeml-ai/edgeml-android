package ai.edgeml.runtime

/**
 * Decides compute delegate and thread configuration based on device state.
 *
 * Pure function — no side effects, no I/O. Takes a [DeviceStateMonitor.DeviceState]
 * snapshot and returns a [ComputeRecommendation] that the interpreter layer uses
 * to configure the TFLite delegate and threading.
 *
 * ## Decision priority (highest to lowest)
 * 1. **Critical thermal** → CPU-only, throttled, minimal threads
 * 2. **Serious thermal** → GPU (skip NNAPI/NPU), reduced threads
 * 3. **Battery Saver enabled** → CPU-only, minimal threads, single inference
 * 4. **Battery < 10%** → CPU-only, reduced batch, minimal threads
 * 5. **Battery < 20% and not charging** → CPU-only, moderate threads
 * 6. **Nominal** → NNAPI (best perf) or GPU, full thread count
 */
object RuntimeAdapter {

    /**
     * Recommendation for how to configure the inference interpreter.
     */
    data class ComputeRecommendation(
        /** Preferred delegate: "nnapi", "gpu", or "xnnpack". */
        val preferredDelegate: String,
        /** Whether inference should be throttled (add delay between runs). */
        val shouldThrottle: Boolean,
        /** Whether batch sizes should be reduced. */
        val reduceBatchSize: Boolean,
        /** Maximum number of concurrent inference calls. */
        val maxConcurrentInferences: Int,
        /** Number of TFLite interpreter threads. */
        val numThreads: Int,
        /** Human-readable explanation for logging/debugging. */
        val reason: String,
    )

    /**
     * Produce a compute recommendation from the current device state.
     *
     * @param state Current device state snapshot.
     * @return Recommendation for delegate, threading, and throttling.
     */
    fun recommend(state: DeviceStateMonitor.DeviceState): ComputeRecommendation {
        // 1. Critical thermal — absolute priority, protect the device
        if (state.thermalState == DeviceStateMonitor.ThermalState.CRITICAL) {
            return ComputeRecommendation(
                preferredDelegate = "xnnpack",
                shouldThrottle = true,
                reduceBatchSize = true,
                maxConcurrentInferences = 1,
                numThreads = 2,
                reason = "Critical thermal state: CPU-only, throttled",
            )
        }

        // 2. Serious thermal — avoid NNAPI/NPU, use GPU which has better thermal behaviour
        if (state.thermalState == DeviceStateMonitor.ThermalState.SERIOUS) {
            return ComputeRecommendation(
                preferredDelegate = "gpu",
                shouldThrottle = false,
                reduceBatchSize = false,
                maxConcurrentInferences = 2,
                numThreads = 2,
                reason = "Serious thermal state: GPU only, reduced threads",
            )
        }

        // 3. Battery Saver mode — user explicitly wants low power consumption
        if (state.isLowPowerMode) {
            return ComputeRecommendation(
                preferredDelegate = "xnnpack",
                shouldThrottle = false,
                reduceBatchSize = false,
                maxConcurrentInferences = 1,
                numThreads = 2,
                reason = "Battery Saver enabled: CPU-only, minimal concurrency",
            )
        }

        // 4. Battery critically low (< 10%)
        if (state.batteryLevel < 10) {
            return ComputeRecommendation(
                preferredDelegate = "xnnpack",
                shouldThrottle = false,
                reduceBatchSize = true,
                maxConcurrentInferences = 1,
                numThreads = 2,
                reason = "Battery critically low (${state.batteryLevel}%): CPU-only, reduced batch",
            )
        }

        // 5. Battery low (< 20%) and not charging
        if (state.batteryLevel < 20 && !state.isCharging) {
            return ComputeRecommendation(
                preferredDelegate = "xnnpack",
                shouldThrottle = false,
                reduceBatchSize = false,
                maxConcurrentInferences = 2,
                numThreads = 4,
                reason = "Battery low (${state.batteryLevel}%), not charging: CPU-only",
            )
        }

        // 6. Nominal — use best available accelerator
        val coreCount = Runtime.getRuntime().availableProcessors()
        return ComputeRecommendation(
            preferredDelegate = "nnapi",
            shouldThrottle = false,
            reduceBatchSize = false,
            maxConcurrentInferences = 4,
            numThreads = coreCount,
            reason = "Nominal conditions: NNAPI preferred, $coreCount threads",
        )
    }
}
