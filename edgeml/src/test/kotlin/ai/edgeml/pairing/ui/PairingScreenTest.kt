package ai.edgeml.pairing.ui

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for utility functions used in [PairingScreen].
 */
class PairingScreenTest {

    // =========================================================================
    // formatBytes()
    // =========================================================================

    @Test
    fun `formatBytes returns 0 B for zero`() {
        assertEquals("0 B", formatBytes(0))
    }

    @Test
    fun `formatBytes returns 0 B for negative`() {
        assertEquals("0 B", formatBytes(-100))
    }

    @Test
    fun `formatBytes formats bytes correctly`() {
        assertEquals("512 B", formatBytes(512))
    }

    @Test
    fun `formatBytes formats kilobytes correctly`() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
    }

    @Test
    fun `formatBytes formats megabytes correctly`() {
        assertEquals("1.0 MB", formatBytes(1_048_576))
        assertEquals("10.5 MB", formatBytes(11_010_048))
    }

    @Test
    fun `formatBytes formats gigabytes correctly`() {
        assertEquals("2.7 GB", formatBytes(2_900_000_000))
        assertEquals("1.0 GB", formatBytes(1_073_741_824))
    }

    @Test
    fun `formatBytes formats terabytes correctly`() {
        assertEquals("1.0 TB", formatBytes(1_099_511_627_776))
    }

    @Test
    fun `formatBytes handles exact boundary values`() {
        // Exactly 1 KB
        assertEquals("1.0 KB", formatBytes(1024))
        // Exactly 1 MB
        assertEquals("1.0 MB", formatBytes(1024 * 1024))
        // Exactly 1 GB
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `formatBytes handles 1 byte`() {
        assertEquals("1 B", formatBytes(1))
    }

    @Test
    fun `formatBytes handles common model sizes`() {
        // 100 MB model
        assertEquals("100.0 MB", formatBytes(104_857_600))
        // 2.7 GB model (like phi-4-mini)
        assertEquals("2.5 GB", formatBytes(2_684_354_560))
    }

    // =========================================================================
    // PairingState data class tests
    // =========================================================================

    @Test
    fun `PairingState Connecting holds host`() {
        val state = PairingState.Connecting("192.168.1.100")
        assertEquals("192.168.1.100", state.host)
    }

    @Test
    fun `PairingState Downloading holds progress data`() {
        val state = PairingState.Downloading(
            progress = 0.78f,
            modelName = "phi-4-mini",
            bytesDownloaded = 2_100_000_000L,
            totalBytes = 2_700_000_000L,
        )
        assertEquals(0.78f, state.progress)
        assertEquals("phi-4-mini", state.modelName)
        assertEquals(2_100_000_000L, state.bytesDownloaded)
        assertEquals(2_700_000_000L, state.totalBytes)
    }

    @Test
    fun `PairingState Success holds model info`() {
        val state = PairingState.Success(
            modelName = "phi-4-mini",
            modelVersion = "1.2",
            sizeBytes = 2_700_000_000L,
            runtime = "TFLite",
        )
        assertEquals("phi-4-mini", state.modelName)
        assertEquals("1.2", state.modelVersion)
        assertEquals(2_700_000_000L, state.sizeBytes)
        assertEquals("TFLite", state.runtime)
    }

    @Test
    fun `PairingState Error holds message and retryability`() {
        val retryable = PairingState.Error("Network error", isRetryable = true)
        assertEquals("Network error", retryable.message)
        assertEquals(true, retryable.isRetryable)

        val nonRetryable = PairingState.Error("Session expired", isRetryable = false)
        assertEquals(false, nonRetryable.isRetryable)
    }

    @Test
    fun `PairingState Error defaults to retryable`() {
        val state = PairingState.Error("Something went wrong")
        assertEquals(true, state.isRetryable)
    }
}
