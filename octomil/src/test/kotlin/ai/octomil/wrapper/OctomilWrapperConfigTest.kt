package ai.octomil.wrapper

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [OctomilWrapperConfig] defaults, factory methods, and data class behavior.
 */
class OctomilWrapperConfigTest {

    // =========================================================================
    // Default config
    // =========================================================================

    @Test
    fun `default config has all features enabled`() {
        val config = OctomilWrapperConfig.default()

        assertTrue(config.validateInputs)
        assertTrue(config.telemetryEnabled)
        assertTrue(config.otaUpdatesEnabled)
    }

    @Test
    fun `default config has no server connectivity`() {
        val config = OctomilWrapperConfig.default()

        assertNull(config.serverUrl)
        assertNull(config.apiKey)
    }

    @Test
    fun `default config has sensible batch and flush defaults`() {
        val config = OctomilWrapperConfig.default()

        assertEquals(50, config.telemetryBatchSize)
        assertEquals(30_000L, config.telemetryFlushIntervalMs)
    }

    // =========================================================================
    // Offline config
    // =========================================================================

    @Test
    fun `offline config disables telemetry and OTA`() {
        val config = OctomilWrapperConfig.offline()

        assertFalse(config.telemetryEnabled)
        assertFalse(config.otaUpdatesEnabled)
    }

    @Test
    fun `offline config still enables validation`() {
        val config = OctomilWrapperConfig.offline()

        assertTrue(config.validateInputs)
    }

    // =========================================================================
    // Custom config
    // =========================================================================

    @Test
    fun `custom config preserves all fields`() {
        val config = OctomilWrapperConfig(
            validateInputs = false,
            telemetryEnabled = true,
            otaUpdatesEnabled = false,
            serverUrl = "https://api.example.com",
            apiKey = "test-key-123",
            telemetryBatchSize = 100,
            telemetryFlushIntervalMs = 60_000L,
        )

        assertFalse(config.validateInputs)
        assertTrue(config.telemetryEnabled)
        assertFalse(config.otaUpdatesEnabled)
        assertEquals("https://api.example.com", config.serverUrl)
        assertEquals("test-key-123", config.apiKey)
        assertEquals(100, config.telemetryBatchSize)
        assertEquals(60_000L, config.telemetryFlushIntervalMs)
    }

    // =========================================================================
    // Data class equality
    // =========================================================================

    @Test
    fun `configs with same values are equal`() {
        val a = OctomilWrapperConfig(serverUrl = "https://a.com", apiKey = "k1")
        val b = OctomilWrapperConfig(serverUrl = "https://a.com", apiKey = "k1")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `configs with different values are not equal`() {
        val a = OctomilWrapperConfig(serverUrl = "https://a.com")
        val b = OctomilWrapperConfig(serverUrl = "https://b.com")

        assertFalse(a == b)
    }

    // =========================================================================
    // Copy
    // =========================================================================

    @Test
    fun `copy with override changes only specified fields`() {
        val original = OctomilWrapperConfig.default()
        val modified = original.copy(telemetryEnabled = false)

        assertTrue(original.telemetryEnabled)
        assertFalse(modified.telemetryEnabled)
        // Everything else stays the same
        assertEquals(original.validateInputs, modified.validateInputs)
        assertEquals(original.otaUpdatesEnabled, modified.otaUpdatesEnabled)
        assertEquals(original.telemetryBatchSize, modified.telemetryBatchSize)
    }
}
