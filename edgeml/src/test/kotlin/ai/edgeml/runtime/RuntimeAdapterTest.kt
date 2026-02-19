package ai.edgeml.runtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [RuntimeAdapter] compute recommendation logic.
 *
 * Verifies that the adapter produces the correct delegate, threading, and
 * throttling configuration for each device state scenario.
 */
class RuntimeAdapterTest {

    // =========================================================================
    // Critical thermal
    // =========================================================================

    @Test
    fun `critical thermal selects CPU only with throttling`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 80,
            isCharging = true,
            thermalState = DeviceStateMonitor.ThermalState.CRITICAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("xnnpack", rec.preferredDelegate)
        assertTrue(rec.shouldThrottle)
        assertTrue(rec.reduceBatchSize)
        assertEquals(1, rec.maxConcurrentInferences)
        assertEquals(2, rec.numThreads)
    }

    @Test
    fun `critical thermal overrides high battery and charging`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 100,
            isCharging = true,
            thermalState = DeviceStateMonitor.ThermalState.CRITICAL,
            availableMemoryMB = 8192,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("xnnpack", rec.preferredDelegate)
        assertTrue(rec.shouldThrottle)
    }

    // =========================================================================
    // Serious thermal
    // =========================================================================

    @Test
    fun `serious thermal selects GPU and reduces threads`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 70,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.SERIOUS,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("gpu", rec.preferredDelegate)
        assertFalse(rec.shouldThrottle)
        assertEquals(2, rec.numThreads)
        assertEquals(2, rec.maxConcurrentInferences)
    }

    @Test
    fun `serious thermal overrides low battery concern`() {
        // Serious thermal takes priority over battery < 20%
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 15,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.SERIOUS,
            availableMemoryMB = 2048,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("gpu", rec.preferredDelegate)
    }

    // =========================================================================
    // Battery Saver mode
    // =========================================================================

    @Test
    fun `battery saver mode selects CPU only with minimal concurrency`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 50,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = true,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("xnnpack", rec.preferredDelegate)
        assertFalse(rec.shouldThrottle)
        assertEquals(1, rec.maxConcurrentInferences)
        assertEquals(2, rec.numThreads)
    }

    @Test
    fun `battery saver with fair thermal still uses CPU`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 60,
            isCharging = true,
            thermalState = DeviceStateMonitor.ThermalState.FAIR,
            availableMemoryMB = 4096,
            isLowPowerMode = true,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("xnnpack", rec.preferredDelegate)
    }

    // =========================================================================
    // Battery < 10%
    // =========================================================================

    @Test
    fun `battery below 10 selects CPU only with reduced batch`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 5,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("xnnpack", rec.preferredDelegate)
        assertTrue(rec.reduceBatchSize)
        assertEquals(1, rec.maxConcurrentInferences)
        assertEquals(2, rec.numThreads)
    }

    @Test
    fun `battery exactly 9 is below 10 threshold`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 9,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("xnnpack", rec.preferredDelegate)
        assertTrue(rec.reduceBatchSize)
    }

    @Test
    fun `battery exactly 10 is not below 10 threshold`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 10,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        // 10% is NOT < 10, so falls to the < 20 + not charging rule
        assertEquals("xnnpack", rec.preferredDelegate)
        assertFalse(rec.reduceBatchSize)
    }

    // =========================================================================
    // Battery < 20% and not charging
    // =========================================================================

    @Test
    fun `battery below 20 not charging selects CPU with 4 threads`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 15,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("xnnpack", rec.preferredDelegate)
        assertFalse(rec.shouldThrottle)
        assertFalse(rec.reduceBatchSize)
        assertEquals(4, rec.numThreads)
        assertEquals(2, rec.maxConcurrentInferences)
    }

    @Test
    fun `battery below 20 but charging uses NNAPI`() {
        // Charging negates the low-battery concern
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 15,
            isCharging = true,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("nnapi", rec.preferredDelegate)
    }

    @Test
    fun `battery exactly 20 not charging uses NNAPI (not below threshold)`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 20,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        // 20% is NOT < 20, so falls to nominal
        assertEquals("nnapi", rec.preferredDelegate)
    }

    // =========================================================================
    // Nominal conditions
    // =========================================================================

    @Test
    fun `nominal conditions select NNAPI with full core count`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 80,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("nnapi", rec.preferredDelegate)
        assertFalse(rec.shouldThrottle)
        assertFalse(rec.reduceBatchSize)
        assertEquals(4, rec.maxConcurrentInferences)
        // numThreads = Runtime.availableProcessors(), varies by machine
        assertTrue(rec.numThreads >= 1)
    }

    @Test
    fun `nominal with fair thermal still uses NNAPI`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 60,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.FAIR,
            availableMemoryMB = 4096,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("nnapi", rec.preferredDelegate)
    }

    @Test
    fun `fully charged and nominal produces best performance config`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 100,
            isCharging = true,
            thermalState = DeviceStateMonitor.ThermalState.NOMINAL,
            availableMemoryMB = 8192,
            isLowPowerMode = false,
        )

        val rec = RuntimeAdapter.recommend(state)

        assertEquals("nnapi", rec.preferredDelegate)
        assertFalse(rec.shouldThrottle)
        assertFalse(rec.reduceBatchSize)
        assertEquals(4, rec.maxConcurrentInferences)
    }

    // =========================================================================
    // Reason string
    // =========================================================================

    @Test
    fun `recommendation includes non-empty reason`() {
        val states = listOf(
            DeviceStateMonitor.DeviceState(80, false, DeviceStateMonitor.ThermalState.CRITICAL, 4096, false),
            DeviceStateMonitor.DeviceState(80, false, DeviceStateMonitor.ThermalState.SERIOUS, 4096, false),
            DeviceStateMonitor.DeviceState(50, false, DeviceStateMonitor.ThermalState.NOMINAL, 4096, true),
            DeviceStateMonitor.DeviceState(5, false, DeviceStateMonitor.ThermalState.NOMINAL, 4096, false),
            DeviceStateMonitor.DeviceState(15, false, DeviceStateMonitor.ThermalState.NOMINAL, 4096, false),
            DeviceStateMonitor.DeviceState(80, false, DeviceStateMonitor.ThermalState.NOMINAL, 4096, false),
        )

        for (state in states) {
            val rec = RuntimeAdapter.recommend(state)
            assertTrue(
                rec.reason.isNotBlank(),
                "Reason should not be blank for state: $state",
            )
        }
    }

    // =========================================================================
    // Priority ordering
    // =========================================================================

    @Test
    fun `critical thermal takes priority over battery saver`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 5,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.CRITICAL,
            availableMemoryMB = 1024,
            isLowPowerMode = true,
        )

        val rec = RuntimeAdapter.recommend(state)

        // Critical thermal should win — throttle must be true
        assertTrue(rec.shouldThrottle)
        assertEquals("xnnpack", rec.preferredDelegate)
        assertEquals(1, rec.maxConcurrentInferences)
    }

    @Test
    fun `serious thermal takes priority over battery saver and low battery`() {
        val state = DeviceStateMonitor.DeviceState(
            batteryLevel = 5,
            isCharging = false,
            thermalState = DeviceStateMonitor.ThermalState.SERIOUS,
            availableMemoryMB = 1024,
            isLowPowerMode = true,
        )

        val rec = RuntimeAdapter.recommend(state)

        // Serious thermal should win — GPU not xnnpack
        assertEquals("gpu", rec.preferredDelegate)
    }
}
