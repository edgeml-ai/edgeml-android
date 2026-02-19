package ai.edgeml.runtime

import android.os.PowerManager
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [DeviceStateMonitor] state mapping logic.
 *
 * Note: Full monitoring (BroadcastReceiver, thermal listener) requires an Android
 * runtime and is tested in instrumented tests. These unit tests cover the pure
 * mapping/logic functions that don't require a Context.
 */
class DeviceStateMonitorTest {

    // =========================================================================
    // Thermal state mapping
    // =========================================================================

    @Test
    fun `THERMAL_STATUS_NONE maps to NOMINAL`() {
        assertEquals(
            DeviceStateMonitor.ThermalState.NOMINAL,
            DeviceStateMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_NONE),
        )
    }

    @Test
    fun `THERMAL_STATUS_LIGHT maps to NOMINAL`() {
        assertEquals(
            DeviceStateMonitor.ThermalState.NOMINAL,
            DeviceStateMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_LIGHT),
        )
    }

    @Test
    fun `THERMAL_STATUS_MODERATE maps to FAIR`() {
        assertEquals(
            DeviceStateMonitor.ThermalState.FAIR,
            DeviceStateMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_MODERATE),
        )
    }

    @Test
    fun `THERMAL_STATUS_SEVERE maps to SERIOUS`() {
        assertEquals(
            DeviceStateMonitor.ThermalState.SERIOUS,
            DeviceStateMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_SEVERE),
        )
    }

    @Test
    fun `THERMAL_STATUS_CRITICAL maps to CRITICAL`() {
        assertEquals(
            DeviceStateMonitor.ThermalState.CRITICAL,
            DeviceStateMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_CRITICAL),
        )
    }

    @Test
    fun `THERMAL_STATUS_EMERGENCY maps to CRITICAL`() {
        assertEquals(
            DeviceStateMonitor.ThermalState.CRITICAL,
            DeviceStateMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_EMERGENCY),
        )
    }

    @Test
    fun `THERMAL_STATUS_SHUTDOWN maps to CRITICAL`() {
        assertEquals(
            DeviceStateMonitor.ThermalState.CRITICAL,
            DeviceStateMonitor.mapThermalStatus(PowerManager.THERMAL_STATUS_SHUTDOWN),
        )
    }

    @Test
    fun `unknown thermal status maps to NOMINAL`() {
        // Future-proof: any unknown value defaults to NOMINAL
        assertEquals(
            DeviceStateMonitor.ThermalState.NOMINAL,
            DeviceStateMonitor.mapThermalStatus(999),
        )
    }

    // =========================================================================
    // DeviceState data class
    // =========================================================================

    @Test
    fun `DeviceState stores all properties correctly`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 75,
            isCharging = true,
            thermalState = DeviceStateMonitor.ThermalState.FAIR,
            availableMemoryMB = 2048,
            isLowPowerMode = false,
        )

        assertEquals(75, state.batteryLevel)
        assertEquals(true, state.isCharging)
        assertEquals(DeviceStateMonitor.ThermalState.FAIR, state.thermalState)
        assertEquals(2048L, state.availableMemoryMB)
        assertEquals(false, state.isLowPowerMode)
    }

    @Test
    fun `DeviceState equality works`() {
        val state1 = DeviceStateMonitor.DeviceState(50, false, DeviceStateMonitor.ThermalState.NOMINAL, 4096, false)
        val state2 = DeviceStateMonitor.DeviceState(50, false, DeviceStateMonitor.ThermalState.NOMINAL, 4096, false)
        val state3 = DeviceStateMonitor.DeviceState(50, true, DeviceStateMonitor.ThermalState.NOMINAL, 4096, false)

        assertEquals(state1, state2)
        assert(state1 != state3) { "States with different charging should not be equal" }
    }

    @Test
    fun `DeviceState copy modifies single field`() {
        val original = DeviceStateMonitor.DeviceState(
            batteryLevel = 80,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val modified = original.copy(batteryLevel = 10)

        assertEquals(10, modified.batteryLevel)
        assertEquals(false, modified.isCharging)
        assertEquals(DeviceStateMonitor.ThermalState.NOMINAL, modified.thermalState)
    }

    // =========================================================================
    // ThermalState enum
    // =========================================================================

    @Test
    fun `ThermalState enum has all expected values`() {
        val values = DeviceStateMonitor.ThermalState.entries
        assertEquals(4, values.size)
        assertEquals(
            listOf(
                DeviceStateMonitor.ThermalState.NOMINAL,
                DeviceStateMonitor.ThermalState.FAIR,
                DeviceStateMonitor.ThermalState.SERIOUS,
                DeviceStateMonitor.ThermalState.CRITICAL,
            ),
            values,
        )
    }

    @Test
    fun `all PowerManager thermal statuses are mapped`() {
        // Verify every known PowerManager thermal constant has a mapping
        val androidStatuses = listOf(
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
        )

        for (status in androidStatuses) {
            val mapped = DeviceStateMonitor.mapThermalStatus(status)
            // Just verify it doesn't throw and returns a valid enum
            assert(mapped in DeviceStateMonitor.ThermalState.entries) {
                "Status $status should map to a valid ThermalState"
            }
        }
    }
}
