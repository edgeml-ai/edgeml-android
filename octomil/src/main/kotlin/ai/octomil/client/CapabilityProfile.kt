package ai.octomil.client

import ai.octomil.generated.DeviceClass

/**
 * Represents the hardware and runtime capabilities of the current device.
 *
 * Produced by [CapabilitiesClient.current] and used to determine which
 * model variants, delegates, and runtimes are appropriate for this device.
 *
 * ```kotlin
 * val profile = client.capabilities.current()
 * println(profile.deviceClass)       // FLAGSHIP, HIGH, MID, LOW
 * println(profile.availableRuntimes) // [tflite, nnapi, gpu]
 * println(profile.accelerators)      // [nnapi, gpu, xnnpack]
 * ```
 */
data class CapabilityProfile(
    /** Tiered device classification based on RAM and SoC. */
    val deviceClass: DeviceClass,
    /** Runtime backends available on this device (e.g., "tflite", "nnapi", "gpu"). */
    val availableRuntimes: List<String>,
    /** Total device RAM in megabytes. */
    val memoryMb: Long,
    /** Available internal storage in megabytes. */
    val storageMb: Long,
    /** Platform identifier (always "android"). */
    val platform: String = "android",
    /** Hardware accelerators detected (e.g., "nnapi", "gpu", "xnnpack", "vendor_npu"). */
    val accelerators: List<String>,
)
