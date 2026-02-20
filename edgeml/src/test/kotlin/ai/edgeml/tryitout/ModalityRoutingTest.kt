package ai.edgeml.tryitout

import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for modality routing logic in [TryItOutViewModel].
 *
 * Verifies that the [effectiveModality] property correctly normalizes,
 * trims, and defaults the modality string for UI routing decisions.
 */
class ModalityRoutingTest {

    private val runner = mockk<InferenceRunner>(relaxed = true)

    private fun modelInfoWithModality(modality: String?) = ModelInfo(
        modelName = "test",
        modelVersion = "1.0",
        sizeBytes = 100L,
        runtime = "TFLite",
        modality = modality,
    )

    // =========================================================================
    // Known modalities
    // =========================================================================

    @Test
    fun `text modality routes correctly`() {
        val vm = TryItOutViewModel(modelInfoWithModality("text"), runner)
        assertEquals("text", vm.effectiveModality)
    }

    @Test
    fun `vision modality routes correctly`() {
        val vm = TryItOutViewModel(modelInfoWithModality("vision"), runner)
        assertEquals("vision", vm.effectiveModality)
    }

    @Test
    fun `audio modality routes correctly`() {
        val vm = TryItOutViewModel(modelInfoWithModality("audio"), runner)
        assertEquals("audio", vm.effectiveModality)
    }

    @Test
    fun `classification modality routes correctly`() {
        val vm = TryItOutViewModel(modelInfoWithModality("classification"), runner)
        assertEquals("classification", vm.effectiveModality)
    }

    // =========================================================================
    // Normalization
    // =========================================================================

    @Test
    fun `uppercase TEXT normalizes to text`() {
        val vm = TryItOutViewModel(modelInfoWithModality("TEXT"), runner)
        assertEquals("text", vm.effectiveModality)
    }

    @Test
    fun `mixed case Vision normalizes to vision`() {
        val vm = TryItOutViewModel(modelInfoWithModality("Vision"), runner)
        assertEquals("vision", vm.effectiveModality)
    }

    @Test
    fun `uppercase AUDIO normalizes to audio`() {
        val vm = TryItOutViewModel(modelInfoWithModality("AUDIO"), runner)
        assertEquals("audio", vm.effectiveModality)
    }

    @Test
    fun `mixed case Classification normalizes to classification`() {
        val vm = TryItOutViewModel(modelInfoWithModality("Classification"), runner)
        assertEquals("classification", vm.effectiveModality)
    }

    // =========================================================================
    // Whitespace handling
    // =========================================================================

    @Test
    fun `leading whitespace is trimmed`() {
        val vm = TryItOutViewModel(modelInfoWithModality("  vision"), runner)
        assertEquals("vision", vm.effectiveModality)
    }

    @Test
    fun `trailing whitespace is trimmed`() {
        val vm = TryItOutViewModel(modelInfoWithModality("audio   "), runner)
        assertEquals("audio", vm.effectiveModality)
    }

    @Test
    fun `whitespace on both sides is trimmed`() {
        val vm = TryItOutViewModel(modelInfoWithModality("  text  "), runner)
        assertEquals("text", vm.effectiveModality)
    }

    // =========================================================================
    // Null / empty / unknown defaults
    // =========================================================================

    @Test
    fun `null modality defaults to text`() {
        val vm = TryItOutViewModel(modelInfoWithModality(null), runner)
        assertEquals("text", vm.effectiveModality)
    }

    @Test
    fun `empty string modality returns empty (treated as unknown)`() {
        val vm = TryItOutViewModel(modelInfoWithModality(""), runner)
        // Empty string after trim and lowercase is still empty
        // The screen will route to text via the else branch
        assertEquals("", vm.effectiveModality)
    }

    @Test
    fun `unknown modality passes through`() {
        val vm = TryItOutViewModel(modelInfoWithModality("tabular"), runner)
        assertEquals("tabular", vm.effectiveModality)
    }

    // =========================================================================
    // ModelInfo data class
    // =========================================================================

    @Test
    fun `ModelInfo equality`() {
        val a = modelInfoWithModality("text")
        val b = modelInfoWithModality("text")
        assertEquals(a, b)
    }

    @Test
    fun `ModelInfo copy preserves fields`() {
        val original = modelInfoWithModality("vision")
        val copy = original.copy(modelName = "new-model")
        assertEquals("new-model", copy.modelName)
        assertEquals("vision", copy.modality)
        assertEquals("1.0", copy.modelVersion)
    }
}
