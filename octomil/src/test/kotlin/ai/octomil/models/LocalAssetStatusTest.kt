package ai.octomil.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for [LocalAssetStatus] transitions.
 *
 * Validates:
 * 1. All four status states: Ready, DownloadRequired, Preparing, Unavailable
 * 2. Convenience accessors: isReady, needsDownload, isPreparing, localFile
 * 3. Status description formatting
 * 4. State transitions: DownloadRequired -> Preparing -> Ready
 * 5. State transitions: DownloadRequired -> Unavailable
 * 6. Mutual exclusivity of status flags
 */
class LocalAssetStatusTest {

    // -----------------------------------------------------------------------
    // Individual state properties
    // -----------------------------------------------------------------------

    @Test
    fun `Ready status has correct properties`() {
        val file = File("/data/app/octomil_models/model_v1.tflite")
        val cached = createCachedModel("m1", "1.0", file.absolutePath)
        val status = LocalAssetStatus.Ready(localFile = file, cachedModel = cached)

        assertTrue(status.isReady)
        assertFalse(status.needsDownload)
        assertFalse(status.isPreparing)
        assertEquals(file, status.localFile)
        assertTrue(status.statusDescription.contains("Ready"))
    }

    @Test
    fun `DownloadRequired status has correct properties`() {
        val status = LocalAssetStatus.DownloadRequired(
            sizeBytes = 500_000_000,
            modelId = "gemma-2b",
            version = "1.0",
        )

        assertFalse(status.isReady)
        assertTrue(status.needsDownload)
        assertFalse(status.isPreparing)
        assertNull(status.modelFile)
        assertTrue(status.statusDescription.contains("Download required"))
        assertTrue(status.statusDescription.contains("MB"))
    }

    @Test
    fun `Preparing status with progress has correct properties`() {
        val status = LocalAssetStatus.Preparing(progress = 0.75)

        assertFalse(status.isReady)
        assertFalse(status.needsDownload)
        assertTrue(status.isPreparing)
        assertNull(status.modelFile)
        assertTrue(status.statusDescription.contains("75%"))
    }

    @Test
    fun `Preparing status without progress has correct properties`() {
        val status = LocalAssetStatus.Preparing(progress = null)

        assertTrue(status.isPreparing)
        assertTrue(status.statusDescription.contains("Preparing"))
    }

    @Test
    fun `Unavailable status has correct properties`() {
        val status = LocalAssetStatus.Unavailable(reason = "No network connection")

        assertFalse(status.isReady)
        assertFalse(status.needsDownload)
        assertFalse(status.isPreparing)
        assertNull(status.modelFile)
        assertTrue(status.statusDescription.contains("No network connection"))
    }

    // -----------------------------------------------------------------------
    // Status transitions
    // -----------------------------------------------------------------------

    @Test
    fun `transition from DownloadRequired to Preparing to Ready`() {
        // Step 1: Not cached yet
        val step1 = LocalAssetStatus.DownloadRequired(
            sizeBytes = 100_000,
            modelId = "m1",
            version = "1.0",
        )
        assertTrue(step1.needsDownload)

        // Step 2: Download in progress
        val step2 = LocalAssetStatus.Preparing(progress = 0.5)
        assertTrue(step2.isPreparing)

        // Step 3: Download complete
        val file = File("/data/app/octomil_models/m1_1.0.tflite")
        val cached = createCachedModel("m1", "1.0", file.absolutePath)
        val step3 = LocalAssetStatus.Ready(localFile = file, cachedModel = cached)
        assertTrue(step3.isReady)
        assertNotNull(step3.localFile)
    }

    @Test
    fun `transition from DownloadRequired to Unavailable`() {
        val step1 = LocalAssetStatus.DownloadRequired(
            sizeBytes = 100_000,
            modelId = "m1",
            version = "1.0",
        )
        assertTrue(step1.needsDownload)

        val step2 = LocalAssetStatus.Unavailable(reason = "Network unavailable")
        assertFalse(step2.isReady)
        assertTrue(step2.statusDescription.contains("Unavailable"))
    }

    // -----------------------------------------------------------------------
    // Idempotent cache access
    // -----------------------------------------------------------------------

    @Test
    fun `Ready status returns same file on repeated access`() {
        val file = File("/data/app/octomil_models/model.tflite")
        val cached = createCachedModel("m1", "1.0", file.absolutePath)
        val status1 = LocalAssetStatus.Ready(localFile = file, cachedModel = cached)
        val status2 = LocalAssetStatus.Ready(localFile = file, cachedModel = cached)

        assertEquals(status1.localFile, status2.localFile)
        assertTrue(status1.isReady)
        assertTrue(status2.isReady)
    }

    // -----------------------------------------------------------------------
    // Size formatting
    // -----------------------------------------------------------------------

    @Test
    fun `DownloadRequired formats small size correctly`() {
        val status = LocalAssetStatus.DownloadRequired(
            sizeBytes = 1_048_576, // 1 MB
            modelId = "m1",
            version = "1.0",
        )
        assertTrue(status.statusDescription.contains("1.0 MB"))
    }

    @Test
    fun `DownloadRequired formats large size correctly`() {
        val status = LocalAssetStatus.DownloadRequired(
            sizeBytes = 2_621_440_000, // ~2500 MB
            modelId = "m1",
            version = "1.0",
        )
        assertTrue(status.statusDescription.contains("2500"))
    }

    // -----------------------------------------------------------------------
    // Mutual exclusivity
    // -----------------------------------------------------------------------

    @Test
    fun `all four states are mutually exclusive`() {
        val file = File("/tmp/model.tflite")
        val cached = createCachedModel("m1", "1.0", file.absolutePath)
        val states = listOf(
            LocalAssetStatus.Ready(localFile = file, cachedModel = cached),
            LocalAssetStatus.DownloadRequired(sizeBytes = 100, modelId = "m1", version = "1.0"),
            LocalAssetStatus.Preparing(progress = 0.5),
            LocalAssetStatus.Unavailable(reason = "test"),
        )

        for (state in states) {
            val flags = listOf(state.isReady, state.needsDownload, state.isPreparing).count { it }
            // At most one flag should be true (unavailable has none)
            assertTrue(
                "Multiple status flags true for: ${state.statusDescription}",
                flags <= 1,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun createCachedModel(
        modelId: String,
        version: String,
        filePath: String,
    ): CachedModel = CachedModel(
        modelId = modelId,
        version = version,
        filePath = filePath,
        checksum = "abc123",
        sizeBytes = 1024,
        format = "tflite",
        downloadedAt = System.currentTimeMillis(),
        verified = true,
    )
}
