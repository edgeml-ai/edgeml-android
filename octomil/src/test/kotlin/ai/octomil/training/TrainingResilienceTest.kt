package ai.octomil.training

import ai.octomil.runtime.DeviceStateMonitor.DeviceState
import ai.octomil.runtime.DeviceStateMonitor.ThermalState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for training resilience: [TrainingEligibility] and [GradientCache].
 *
 * 12 tests covering battery, thermal, low-power mode, network eligibility
 * checks, and gradient caching for offline/retry scenarios.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingResilienceTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var cacheDir: File
    private lateinit var gradientCache: GradientCache

    @Before
    fun setUp() {
        cacheDir = File(
            System.getProperty("java.io.tmpdir"),
            "octomil_gradient_test_${System.nanoTime()}",
        )
        cacheDir.mkdirs()
        gradientCache = GradientCache.createForTesting(cacheDir, testDispatcher)
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    // =========================================================================
    // TrainingEligibility — Battery
    // =========================================================================

    @Test
    fun `batteryAboveThreshold_eligible`() {
        val state = deviceState(batteryLevel = 50, isCharging = false)
        val result = TrainingEligibility.checkEligibility(
            deviceState = state,
            isConnected = true,
            isMetered = false,
            minBatteryLevel = 20,
            requireUnmeteredNetwork = false,
        )

        assertTrue(result.eligible)
        assertNull(result.reason)
    }

    @Test
    fun `batteryBelowThreshold_ineligible`() {
        val state = deviceState(batteryLevel = 10, isCharging = false)
        val result = TrainingEligibility.checkEligibility(
            deviceState = state,
            isConnected = true,
            isMetered = false,
            minBatteryLevel = 20,
            requireUnmeteredNetwork = false,
        )

        assertFalse(result.eligible)
        assertEquals(IneligibilityReason.LOW_BATTERY, result.reason)
    }

    @Test
    fun `chargingOverridesBattery`() {
        val state = deviceState(batteryLevel = 5, isCharging = true)
        val result = TrainingEligibility.checkEligibility(
            deviceState = state,
            isConnected = true,
            isMetered = false,
            minBatteryLevel = 20,
            requireUnmeteredNetwork = false,
        )

        assertTrue(result.eligible)
        assertNull(result.reason)
    }

    // =========================================================================
    // TrainingEligibility — Thermal & Low-Power Mode
    // =========================================================================

    @Test
    fun `thermalSeriousBlocks`() {
        val state = deviceState(thermalState = ThermalState.SERIOUS)
        val result = TrainingEligibility.checkEligibility(
            deviceState = state,
            isConnected = true,
            isMetered = false,
            minBatteryLevel = 20,
            requireUnmeteredNetwork = false,
        )

        assertFalse(result.eligible)
        assertEquals(IneligibilityReason.THERMAL_THROTTLING, result.reason)
    }

    @Test
    fun `lowPowerModeBlocks`() {
        val state = deviceState(isLowPowerMode = true)
        val result = TrainingEligibility.checkEligibility(
            deviceState = state,
            isConnected = true,
            isMetered = false,
            minBatteryLevel = 20,
            requireUnmeteredNetwork = false,
        )

        assertFalse(result.eligible)
        assertEquals(IneligibilityReason.LOW_POWER_MODE, result.reason)
    }

    // =========================================================================
    // TrainingEligibility — Network
    // =========================================================================

    @Test
    fun `networkWifiUnmetered_suitable`() {
        val quality = TrainingEligibility.assessNetworkQuality(
            isConnected = true,
            isWifi = true,
            isMetered = false,
            requireUnmeteredNetwork = true,
        )

        assertTrue(quality.suitable)
        assertTrue(quality.isWifi)
        assertFalse(quality.isMetered)
    }

    @Test
    fun `networkCellularMetered_notSuitable`() {
        val quality = TrainingEligibility.assessNetworkQuality(
            isConnected = true,
            isWifi = false,
            isMetered = true,
            requireUnmeteredNetwork = true,
        )

        assertFalse(quality.suitable)
        assertFalse(quality.isWifi)
        assertTrue(quality.isMetered)
    }

    @Test
    fun `noConnection_notSuitable`() {
        val quality = TrainingEligibility.assessNetworkQuality(
            isConnected = false,
            isWifi = false,
            isMetered = false,
            requireUnmeteredNetwork = false,
        )

        assertFalse(quality.suitable)
        assertFalse(quality.isConnected)
    }

    // =========================================================================
    // GradientCache
    // =========================================================================

    @Test
    fun `gradientCache_storeAndRetrieve`() = runTest(testDispatcher) {
        val entry = testGradientEntry(roundId = "round-1")

        val stored = gradientCache.store(entry)
        assertTrue(stored)

        val retrieved = gradientCache.get("round-1")
        assertNotNull(retrieved)
        assertEquals("round-1", retrieved.roundId)
        assertEquals("fed-1", retrieved.federationId)
        assertEquals(100, retrieved.sampleCount)
        assertFalse(retrieved.submitted)
    }

    @Test
    fun `gradientCache_listPending`() = runTest(testDispatcher) {
        gradientCache.store(testGradientEntry(roundId = "r1", timestamp = 1000L))
        gradientCache.store(testGradientEntry(roundId = "r2", timestamp = 2000L))
        gradientCache.store(
            testGradientEntry(roundId = "r3", timestamp = 3000L).copy(submitted = true),
        )

        val pending = gradientCache.listPending()
        assertEquals(2, pending.size)
        assertEquals("r1", pending[0].roundId)
        assertEquals("r2", pending[1].roundId)
    }

    @Test
    fun `gradientCache_markSubmitted`() = runTest(testDispatcher) {
        gradientCache.store(testGradientEntry(roundId = "r1"))

        val marked = gradientCache.markSubmitted("r1")
        assertTrue(marked)

        val entry = gradientCache.get("r1")
        assertNotNull(entry)
        assertTrue(entry.submitted)

        // Should no longer appear in pending list
        val pending = gradientCache.listPending()
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `gradientCache_purgeOld`() = runTest(testDispatcher) {
        val now = 100_000L
        val oldEntry = testGradientEntry(roundId = "old", timestamp = 1000L)
        val recentEntry = testGradientEntry(roundId = "recent", timestamp = 99_000L)

        gradientCache.store(oldEntry)
        gradientCache.store(recentEntry)

        val purged = gradientCache.purgeOlderThan(maxAgeMs = 50_000L, now = now)
        assertEquals(1, purged)

        assertNull(gradientCache.get("old"))
        assertNotNull(gradientCache.get("recent"))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun deviceState(
        batteryLevel: Int = 80,
        isCharging: Boolean = false,
        thermalState: ThermalState = ThermalState.NOMINAL,
        availableMemoryMB: Long = 2048L,
        isLowPowerMode: Boolean = false,
    ): DeviceState = DeviceState(
        batteryLevel = batteryLevel,
        isCharging = isCharging,
        thermalState = thermalState,
        availableMemoryMB = availableMemoryMB,
        isLowPowerMode = isLowPowerMode,
    )

    private fun testGradientEntry(
        roundId: String = "round-1",
        federationId: String = "fed-1",
        timestamp: Long = System.currentTimeMillis(),
        sampleCount: Int = 100,
    ): GradientCacheEntry = GradientCacheEntry(
        roundId = roundId,
        federationId = federationId,
        weightDeltaBase64 = "dGVzdC13ZWlnaHRz", // "test-weights" in base64
        metrics = mapOf("loss" to 0.05, "accuracy" to 0.95),
        timestamp = timestamp,
        sampleCount = sampleCount,
    )
}
