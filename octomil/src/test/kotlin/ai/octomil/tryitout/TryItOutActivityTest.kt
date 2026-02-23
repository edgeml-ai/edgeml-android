package ai.octomil.tryitout

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [TryItOutActivity] intent parsing and [ModelInfo] extraction.
 *
 * Uses mocked [Intent] objects because the android.jar stubs do not store
 * extras in local JVM unit tests (putExtra/getStringExtra are no-ops).
 */
class TryItOutActivityTest {

    // =========================================================================
    // extractModelInfo — happy path
    // =========================================================================

    @Test
    fun `extractModelInfo returns ModelInfo with all fields`() {
        val intent = createMockIntent(
            modelName = "phi-4-mini",
            modelVersion = "1.2",
            sizeBytes = 2_700_000_000L,
            runtime = "TFLite",
            modality = "text",
        )

        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("phi-4-mini", info.modelName)
        assertEquals("1.2", info.modelVersion)
        assertEquals(2_700_000_000L, info.sizeBytes)
        assertEquals("TFLite", info.runtime)
        assertEquals("text", info.modality)
    }

    @Test
    fun `extractModelInfo works with null modality`() {
        val intent = createMockIntent(
            modelName = "mobilenet",
            modelVersion = "3.0",
            sizeBytes = 5_000_000L,
            runtime = "TFLite",
            modality = null,
        )

        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("mobilenet", info.modelName)
        assertNull(info.modality)
    }

    @Test
    fun `extractModelInfo works with zero sizeBytes`() {
        val intent = createMockIntent(
            modelName = "tiny-model",
            modelVersion = "0.1",
            sizeBytes = 0L,
            runtime = "ONNX",
        )

        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals(0L, info.sizeBytes)
    }

    // =========================================================================
    // extractModelInfo — missing required fields
    // =========================================================================

    @Test
    fun `extractModelInfo returns null when modelName is missing`() {
        val intent = createMockIntent(
            modelName = null,
            modelVersion = "1.0",
            sizeBytes = 100L,
            runtime = "TFLite",
        )

        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    @Test
    fun `extractModelInfo returns null when modelVersion is missing`() {
        val intent = createMockIntent(
            modelName = "model",
            modelVersion = null,
            sizeBytes = 100L,
            runtime = "TFLite",
        )

        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    @Test
    fun `extractModelInfo returns null when runtime is missing`() {
        val intent = createMockIntent(
            modelName = "model",
            modelVersion = "1.0",
            sizeBytes = 100L,
            runtime = null,
        )

        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    @Test
    fun `extractModelInfo returns null when sizeBytes is missing (defaults to -1)`() {
        val intent = createMockIntent(
            modelName = "model",
            modelVersion = "1.0",
            sizeBytes = null,
            runtime = "TFLite",
        )

        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    @Test
    fun `extractModelInfo returns null for empty intent`() {
        val intent = createMockIntent(
            modelName = null,
            modelVersion = null,
            sizeBytes = null,
            runtime = null,
            modality = null,
        )
        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    // =========================================================================
    // extractModelInfo — different modalities
    // =========================================================================

    @Test
    fun `extractModelInfo preserves vision modality`() {
        val intent = createMockIntent(modality = "vision")
        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("vision", info.modality)
    }

    @Test
    fun `extractModelInfo preserves audio modality`() {
        val intent = createMockIntent(modality = "audio")
        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("audio", info.modality)
    }

    @Test
    fun `extractModelInfo preserves classification modality`() {
        val intent = createMockIntent(modality = "classification")
        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("classification", info.modality)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Create a mocked [Intent] that returns the given values from getStringExtra
     * and getLongExtra. This is necessary because android.jar's [Intent] stubs
     * do not actually store extras in local JVM unit tests.
     */
    private fun createMockIntent(
        modelName: String? = "test-model",
        modelVersion: String? = "1.0",
        sizeBytes: Long? = 1_000L,
        runtime: String? = "TFLite",
        modality: String? = null,
    ): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getStringExtra(TryItOutActivity.EXTRA_MODEL_NAME) } returns modelName
        every { intent.getStringExtra(TryItOutActivity.EXTRA_MODEL_VERSION) } returns modelVersion
        every { intent.getLongExtra(TryItOutActivity.EXTRA_SIZE_BYTES, -1L) } returns (sizeBytes ?: -1L)
        every { intent.getStringExtra(TryItOutActivity.EXTRA_RUNTIME) } returns runtime
        every { intent.getStringExtra(TryItOutActivity.EXTRA_MODALITY) } returns modality
        return intent
    }
}
