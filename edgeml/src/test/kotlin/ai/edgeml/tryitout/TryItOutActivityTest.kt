package ai.edgeml.tryitout

import android.content.Intent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [TryItOutActivity] intent parsing and [ModelInfo] extraction.
 *
 * Uses plain Intent objects (no Robolectric needed) to verify the companion
 * object's [extractModelInfo] logic.
 */
class TryItOutActivityTest {

    // =========================================================================
    // extractModelInfo — happy path
    // =========================================================================

    @Test
    fun `extractModelInfo returns ModelInfo with all fields`() {
        val intent = Intent().apply {
            putExtra(TryItOutActivity.EXTRA_MODEL_NAME, "phi-4-mini")
            putExtra(TryItOutActivity.EXTRA_MODEL_VERSION, "1.2")
            putExtra(TryItOutActivity.EXTRA_SIZE_BYTES, 2_700_000_000L)
            putExtra(TryItOutActivity.EXTRA_RUNTIME, "TFLite")
            putExtra(TryItOutActivity.EXTRA_MODALITY, "text")
        }

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
        val intent = Intent().apply {
            putExtra(TryItOutActivity.EXTRA_MODEL_NAME, "mobilenet")
            putExtra(TryItOutActivity.EXTRA_MODEL_VERSION, "3.0")
            putExtra(TryItOutActivity.EXTRA_SIZE_BYTES, 5_000_000L)
            putExtra(TryItOutActivity.EXTRA_RUNTIME, "TFLite")
        }

        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("mobilenet", info.modelName)
        assertNull(info.modality)
    }

    @Test
    fun `extractModelInfo works with zero sizeBytes`() {
        val intent = Intent().apply {
            putExtra(TryItOutActivity.EXTRA_MODEL_NAME, "tiny-model")
            putExtra(TryItOutActivity.EXTRA_MODEL_VERSION, "0.1")
            putExtra(TryItOutActivity.EXTRA_SIZE_BYTES, 0L)
            putExtra(TryItOutActivity.EXTRA_RUNTIME, "ONNX")
        }

        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals(0L, info.sizeBytes)
    }

    // =========================================================================
    // extractModelInfo — missing required fields
    // =========================================================================

    @Test
    fun `extractModelInfo returns null when modelName is missing`() {
        val intent = Intent().apply {
            putExtra(TryItOutActivity.EXTRA_MODEL_VERSION, "1.0")
            putExtra(TryItOutActivity.EXTRA_SIZE_BYTES, 100L)
            putExtra(TryItOutActivity.EXTRA_RUNTIME, "TFLite")
        }

        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    @Test
    fun `extractModelInfo returns null when modelVersion is missing`() {
        val intent = Intent().apply {
            putExtra(TryItOutActivity.EXTRA_MODEL_NAME, "model")
            putExtra(TryItOutActivity.EXTRA_SIZE_BYTES, 100L)
            putExtra(TryItOutActivity.EXTRA_RUNTIME, "TFLite")
        }

        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    @Test
    fun `extractModelInfo returns null when runtime is missing`() {
        val intent = Intent().apply {
            putExtra(TryItOutActivity.EXTRA_MODEL_NAME, "model")
            putExtra(TryItOutActivity.EXTRA_MODEL_VERSION, "1.0")
            putExtra(TryItOutActivity.EXTRA_SIZE_BYTES, 100L)
        }

        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    @Test
    fun `extractModelInfo returns null when sizeBytes is missing (defaults to -1)`() {
        val intent = Intent().apply {
            putExtra(TryItOutActivity.EXTRA_MODEL_NAME, "model")
            putExtra(TryItOutActivity.EXTRA_MODEL_VERSION, "1.0")
            putExtra(TryItOutActivity.EXTRA_RUNTIME, "TFLite")
        }

        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    @Test
    fun `extractModelInfo returns null for empty intent`() {
        val intent = Intent()
        assertNull(TryItOutActivity.extractModelInfo(intent))
    }

    // =========================================================================
    // extractModelInfo — different modalities
    // =========================================================================

    @Test
    fun `extractModelInfo preserves vision modality`() {
        val intent = createFullIntent(modality = "vision")
        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("vision", info.modality)
    }

    @Test
    fun `extractModelInfo preserves audio modality`() {
        val intent = createFullIntent(modality = "audio")
        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("audio", info.modality)
    }

    @Test
    fun `extractModelInfo preserves classification modality`() {
        val intent = createFullIntent(modality = "classification")
        val info = TryItOutActivity.extractModelInfo(intent)

        assertNotNull(info)
        assertEquals("classification", info.modality)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun createFullIntent(
        modelName: String = "test-model",
        modelVersion: String = "1.0",
        sizeBytes: Long = 1_000L,
        runtime: String = "TFLite",
        modality: String? = null,
    ): Intent = Intent().apply {
        putExtra(TryItOutActivity.EXTRA_MODEL_NAME, modelName)
        putExtra(TryItOutActivity.EXTRA_MODEL_VERSION, modelVersion)
        putExtra(TryItOutActivity.EXTRA_SIZE_BYTES, sizeBytes)
        putExtra(TryItOutActivity.EXTRA_RUNTIME, runtime)
        modality?.let { putExtra(TryItOutActivity.EXTRA_MODALITY, it) }
    }
}
