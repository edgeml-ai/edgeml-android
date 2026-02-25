package ai.octomil.training

import ai.octomil.runtime.DeviceStateMonitor.DeviceState
import ai.octomil.runtime.DeviceStateMonitor.ThermalState

/**
 * Reason why training was deemed ineligible.
 */
enum class IneligibilityReason {
    /** Battery level is below the configured threshold and the device is not charging. */
    LOW_BATTERY,
    /** Device thermal state is SERIOUS or CRITICAL. */
    THERMAL_THROTTLING,
    /** Battery Saver / low-power mode is active. */
    LOW_POWER_MODE,
    /** Network is unavailable. */
    NO_CONNECTION,
    /** Network is metered (cellular) and unmetered is required. */
    METERED_NETWORK,
}

/**
 * Result of a training eligibility check.
 *
 * @property eligible Whether training should proceed.
 * @property reason The reason training is ineligible, or null if eligible.
 */
data class EligibilityResult(
    val eligible: Boolean,
    val reason: IneligibilityReason? = null,
)

/**
 * Network quality assessment for training.
 *
 * @property suitable Whether the network is suitable for gradient upload.
 * @property isConnected Whether any network is available.
 * @property isWifi Whether the connection is via WiFi.
 * @property isMetered Whether the connection is metered.
 */
data class NetworkQuality(
    val suitable: Boolean,
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isMetered: Boolean,
)

/**
 * Pure-function eligibility checks for on-device training.
 *
 * Uses [DeviceState] from [ai.octomil.runtime.DeviceStateMonitor] and network
 * state from [ai.octomil.utils.NetworkMonitor] to decide whether the device
 * is in a suitable state for training.
 */
object TrainingEligibility {

    /**
     * Check whether the device is eligible for training.
     *
     * @param deviceState Current device state snapshot.
     * @param isConnected Whether the device has network connectivity.
     * @param isMetered Whether the current network is metered.
     * @param minBatteryLevel Minimum battery percentage required (0-100).
     * @param requireUnmeteredNetwork Whether to require an unmetered (WiFi) connection.
     * @return [EligibilityResult] indicating whether training should proceed.
     */
    fun checkEligibility(
        deviceState: DeviceState,
        isConnected: Boolean,
        isMetered: Boolean,
        minBatteryLevel: Int,
        requireUnmeteredNetwork: Boolean,
    ): EligibilityResult {
        // Low-power mode blocks training
        if (deviceState.isLowPowerMode) {
            return EligibilityResult(eligible = false, reason = IneligibilityReason.LOW_POWER_MODE)
        }

        // Thermal throttling blocks training
        if (deviceState.thermalState == ThermalState.SERIOUS ||
            deviceState.thermalState == ThermalState.CRITICAL
        ) {
            return EligibilityResult(eligible = false, reason = IneligibilityReason.THERMAL_THROTTLING)
        }

        // Battery check: charging overrides the minimum level
        if (!deviceState.isCharging && deviceState.batteryLevel < minBatteryLevel) {
            return EligibilityResult(eligible = false, reason = IneligibilityReason.LOW_BATTERY)
        }

        // Network checks
        if (!isConnected) {
            return EligibilityResult(eligible = false, reason = IneligibilityReason.NO_CONNECTION)
        }

        if (requireUnmeteredNetwork && isMetered) {
            return EligibilityResult(eligible = false, reason = IneligibilityReason.METERED_NETWORK)
        }

        return EligibilityResult(eligible = true)
    }

    /**
     * Assess current network quality for gradient upload.
     *
     * @param isConnected Whether the device has network connectivity.
     * @param isWifi Whether the connection is via WiFi.
     * @param isMetered Whether the connection is metered.
     * @param requireUnmeteredNetwork Whether to require unmetered for suitability.
     * @return [NetworkQuality] assessment.
     */
    fun assessNetworkQuality(
        isConnected: Boolean,
        isWifi: Boolean,
        isMetered: Boolean,
        requireUnmeteredNetwork: Boolean,
    ): NetworkQuality {
        val suitable = isConnected && (!requireUnmeteredNetwork || !isMetered)
        return NetworkQuality(
            suitable = suitable,
            isConnected = isConnected,
            isWifi = isWifi,
            isMetered = isMetered,
        )
    }
}
