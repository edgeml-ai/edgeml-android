package ai.edgeml.runtime

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Monitors device state (battery, thermal, memory, power-save) for runtime adaptation.
 *
 * Exposes a reactive [StateFlow] of [DeviceState] that updates whenever battery
 * level, charging status, thermal state, or power-save mode changes. The
 * [RuntimeAdapter] consumes this state to decide which compute delegate and
 * thread configuration to use for inference.
 *
 * ## Usage
 * ```kotlin
 * val monitor = DeviceStateMonitor(applicationContext)
 * monitor.startMonitoring()
 *
 * monitor.deviceState.collect { state ->
 *     val recommendation = RuntimeAdapter.recommend(state)
 *     // adapt interpreter...
 * }
 *
 * monitor.stopMonitoring()
 * ```
 *
 * ## API level compatibility
 * - Battery level & charging: all API levels (via [BroadcastReceiver] for [Intent.ACTION_BATTERY_CHANGED])
 * - Thermal status: API 29+ ([PowerManager.getCurrentThermalStatus]). Falls back to [ThermalState.NOMINAL] on older devices.
 * - Memory: all API levels ([ActivityManager.MemoryInfo])
 * - Power-save mode: API 21+ ([PowerManager.isPowerSaveMode])
 */
class DeviceStateMonitor(private val context: Context) {

    /**
     * Snapshot of device state relevant for compute adaptation.
     */
    data class DeviceState(
        /** Battery level as a percentage (0-100). */
        val batteryLevel: Int,
        /** Whether the device is plugged in (AC, USB, or wireless). */
        val isCharging: Boolean,
        /** Current thermal status of the device. */
        val thermalState: ThermalState,
        /** Available RAM in megabytes. */
        val availableMemoryMB: Long,
        /** Whether Battery Saver (low-power mode) is enabled. */
        val isLowPowerMode: Boolean,
    )

    /**
     * Simplified thermal state for adaptation decisions.
     *
     * Maps Android's [PowerManager] thermal constants to four tiers.
     */
    enum class ThermalState {
        /** Device is cool; all delegates are safe. */
        NOMINAL,
        /** Slightly warm; can still use accelerators. */
        FAIR,
        /** Hot; should avoid heavy accelerators (NNAPI/NPU). */
        SERIOUS,
        /** Dangerously hot; fall back to minimal CPU-only. */
        CRITICAL,
    }

    private val _deviceState = MutableStateFlow(readCurrentState())

    /** Reactive device state. Emits a new value whenever any monitored property changes. */
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private var isMonitoring = false

    // ----- BroadcastReceiver for battery changes -----

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                updateState()
            }
        }
    }

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                updateState()
            }
        }
    }

    // ----- Thermal listener (API 29+) -----

    private var thermalListener: Any? = null

    /**
     * Begin monitoring battery, thermal, and power-save state.
     *
     * Registers a [BroadcastReceiver] for battery changes and, on API 29+,
     * a thermal status listener. Call [stopMonitoring] to unregister.
     */
    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        // Battery changes
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, batteryFilter)

        // Power-save mode changes
        val powerSaveFilter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        context.registerReceiver(powerSaveReceiver, powerSaveFilter)

        // Thermal status listener (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val listener = PowerManager.OnThermalStatusChangedListener { _ ->
                    updateState()
                }
                powerManager.addThermalStatusListener(listener)
                thermalListener = listener
            } catch (e: Exception) {
                Timber.w(e, "Failed to register thermal status listener")
            }
        }

        // Read initial state
        updateState()
        Timber.d("DeviceStateMonitor started")
    }

    /**
     * Stop monitoring and unregister all receivers/listeners.
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Timber.w(e, "Failed to unregister battery receiver")
        }

        try {
            context.unregisterReceiver(powerSaveReceiver)
        } catch (e: Exception) {
            Timber.w(e, "Failed to unregister power-save receiver")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("UNCHECKED_CAST")
                powerManager.removeThermalStatusListener(
                    thermalListener as PowerManager.OnThermalStatusChangedListener,
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to remove thermal status listener")
            }
            thermalListener = null
        }

        Timber.d("DeviceStateMonitor stopped")
    }

    // =========================================================================
    // Internal state reading
    // =========================================================================

    private fun updateState() {
        _deviceState.value = readCurrentState()
    }

    internal fun readCurrentState(): DeviceState {
        return DeviceState(
            batteryLevel = readBatteryLevel(),
            isCharging = readIsCharging(),
            thermalState = readThermalState(),
            availableMemoryMB = readAvailableMemoryMB(),
            isLowPowerMode = readIsLowPowerMode(),
        )
    }

    internal fun readBatteryLevel(): Int {
        return try {
            val batteryManager =
                context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level >= 0) level else 50 // fallback to 50% if unavailable
        } catch (e: Exception) {
            Timber.w(e, "Failed to read battery level")
            50
        }
    }

    internal fun readIsCharging(): Boolean {
        return try {
            val batteryManager =
                context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.isCharging
        } catch (e: Exception) {
            Timber.w(e, "Failed to read charging state")
            false
        }
    }

    internal fun readThermalState(): ThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ThermalState.NOMINAL
        }
        return try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            mapThermalStatus(powerManager.currentThermalStatus)
        } catch (e: Exception) {
            Timber.w(e, "Failed to read thermal state")
            ThermalState.NOMINAL
        }
    }

    internal fun readAvailableMemoryMB(): Long {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (e: Exception) {
            Timber.w(e, "Failed to read available memory")
            0L
        }
    }

    internal fun readIsLowPowerMode(): Boolean {
        return try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isPowerSaveMode
        } catch (e: Exception) {
            Timber.w(e, "Failed to read power-save mode")
            false
        }
    }

    companion object {
        /**
         * Map Android's [PowerManager] thermal status constants to [ThermalState].
         *
         * - NONE / LIGHT → NOMINAL
         * - MODERATE → FAIR
         * - SEVERE → SERIOUS
         * - CRITICAL / EMERGENCY / SHUTDOWN → CRITICAL
         */
        internal fun mapThermalStatus(status: Int): ThermalState {
            return when (status) {
                PowerManager.THERMAL_STATUS_NONE,
                PowerManager.THERMAL_STATUS_LIGHT,
                -> ThermalState.NOMINAL

                PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR

                PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS

                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN,
                -> ThermalState.CRITICAL

                else -> ThermalState.NOMINAL
            }
        }
    }
}
