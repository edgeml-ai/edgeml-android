package ai.octomil.wrapper

/**
 * Configuration for the Octomil TFLite wrapper.
 *
 * Controls validation, telemetry, and OTA update behavior. All features
 * are opt-in and gracefully degrade when the server is unreachable.
 *
 * ```kotlin
 * val config = OctomilWrapperConfig(
 *     serverUrl = "https://api.octomil.com",
 *     apiKey = "your-api-key",
 * )
 * val interpreter = Octomil.wrap(Interpreter(modelFile), "classifier", config)
 * ```
 */
data class OctomilWrapperConfig(
    /** Whether to validate inputs against the model contract fetched from the server. */
    val validateInputs: Boolean = true,
    /** Whether to report inference telemetry (latency, success/failure). */
    val telemetryEnabled: Boolean = true,
    /** Whether to check for OTA model updates on init. */
    val otaUpdatesEnabled: Boolean = true,
    /** Server URL for telemetry and updates. Null means offline-only mode. */
    val serverUrl: String? = null,
    /** API key for server authentication. Null means offline-only mode. */
    val apiKey: String? = null,
    /** Organization ID included in the v2 telemetry resource envelope. */
    val orgId: String? = null,
    /** Device identifier included in the v2 telemetry resource envelope. */
    val deviceId: String? = null,
    /** Maximum number of telemetry events to buffer before flushing. */
    val telemetryBatchSize: Int = 50,
    /** Interval in milliseconds between automatic telemetry flushes. */
    val telemetryFlushIntervalMs: Long = 30_000L,
) {
    companion object {
        /** Default configuration: all features enabled, no server connectivity. */
        fun default() = OctomilWrapperConfig()

        /** Offline-only configuration: no telemetry or OTA, but validation still active. */
        fun offline() = OctomilWrapperConfig(
            telemetryEnabled = false,
            otaUpdatesEnabled = false,
        )
    }
}
