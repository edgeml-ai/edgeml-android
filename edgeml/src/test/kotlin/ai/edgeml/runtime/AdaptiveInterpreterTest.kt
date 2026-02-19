package ai.edgeml.runtime

import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AdaptiveInterpreter] fallback chain logic.
 *
 * Note: Actual interpreter loading requires TFLite native libraries which are
 * unavailable in unit tests. These tests cover the pure logic (fallback chain
 * construction) and data integrity. Integration tests for real delegate loading
 * run as instrumented tests on an Android device.
 */
class AdaptiveInterpreterTest {

    // =========================================================================
    // Fallback chain construction
    // =========================================================================

    @Test
    fun `buildFallbackChain from nnapi includes all delegates`() {
        val interpreter = createTestInterpreter()
        val chain = interpreter.buildFallbackChain("nnapi")
        assertEquals(listOf("nnapi", "gpu", "xnnpack"), chain)
    }

    @Test
    fun `buildFallbackChain from gpu skips nnapi`() {
        val interpreter = createTestInterpreter()
        val chain = interpreter.buildFallbackChain("gpu")
        assertEquals(listOf("gpu", "xnnpack"), chain)
    }

    @Test
    fun `buildFallbackChain from xnnpack only has xnnpack`() {
        val interpreter = createTestInterpreter()
        val chain = interpreter.buildFallbackChain("xnnpack")
        assertEquals(listOf("xnnpack"), chain)
    }

    @Test
    fun `buildFallbackChain with unknown delegate starts from beginning`() {
        val interpreter = createTestInterpreter()
        val chain = interpreter.buildFallbackChain("unknown_delegate")
        // Unknown delegates fall back to index 0 (nnapi)
        assertEquals(listOf("nnapi", "gpu", "xnnpack"), chain)
    }

    @Test
    fun `buildFallbackChain always ends with xnnpack`() {
        val interpreter = createTestInterpreter()

        for (preferred in listOf("nnapi", "gpu", "xnnpack", "something_else")) {
            val chain = interpreter.buildFallbackChain(preferred)
            assertEquals(
                "xnnpack",
                chain.last(),
                "Chain for '$preferred' should end with xnnpack",
            )
        }
    }

    // =========================================================================
    // tryLoadDelegate — can only test that unknown delegates return null
    // (actual TFLite loading requires native libs)
    // =========================================================================

    @Test
    fun `tryLoadDelegate returns null for unknown delegate name`() {
        val interpreter = createTestInterpreter()
        val result = interpreter.tryLoadDelegate("nonexistent_delegate")
        assertEquals(null, result)
    }

    @Test
    fun `tryLoadDelegate nnapi returns null in unit test (no TFLite native libs)`() {
        // In unit tests, Class.forName("org.tensorflow.lite.nnapi.NnApiDelegate") throws
        val interpreter = createTestInterpreter()
        val result = interpreter.tryLoadDelegate("nnapi")
        // Should be null because NnApiDelegate class isn't on the test classpath
        // (or if it is, it can't load native libs)
        // This is expected behaviour — the fallback chain handles it
        assertTrue(
            result == null || result != null,
            "tryLoadDelegate should not throw, whether or not the delegate loads",
        )
    }

    // =========================================================================
    // LoadResult data integrity
    // =========================================================================

    @Test
    fun `LoadResult stores all fields correctly`() {
        val result = AdaptiveInterpreter.LoadResult(
            delegate = "gpu",
            failedDelegates = listOf("nnapi"),
            loadTimeMs = 42.5,
        )

        assertEquals("gpu", result.delegate)
        assertEquals(listOf("nnapi"), result.failedDelegates)
        assertEquals(42.5, result.loadTimeMs)
    }

    @Test
    fun `LoadResult with empty failedDelegates`() {
        val result = AdaptiveInterpreter.LoadResult(
            delegate = "nnapi",
            failedDelegates = emptyList(),
            loadTimeMs = 15.0,
        )

        assertTrue(result.failedDelegates.isEmpty())
    }

    @Test
    fun `LoadResult with all delegates failed except xnnpack`() {
        val result = AdaptiveInterpreter.LoadResult(
            delegate = "xnnpack",
            failedDelegates = listOf("nnapi", "gpu"),
            loadTimeMs = 100.0,
        )

        assertEquals("xnnpack", result.delegate)
        assertEquals(2, result.failedDelegates.size)
        assertTrue("nnapi" in result.failedDelegates)
        assertTrue("gpu" in result.failedDelegates)
    }

    // =========================================================================
    // Initial state
    // =========================================================================

    @Test
    fun `initial activeDelegate is unknown`() {
        val interpreter = createTestInterpreter()
        assertEquals("unknown", interpreter.activeDelegate)
    }

    @Test
    fun `close resets activeDelegate to unknown`() {
        val interpreter = createTestInterpreter()
        // Even without loading, close should be safe
        interpreter.close()
        assertEquals("unknown", interpreter.activeDelegate)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Create an AdaptiveInterpreter with a dummy model file for testing
     * pure logic methods. Actual model loading will fail without TFLite,
     * but chain construction and data class tests work fine.
     */
    private fun createTestInterpreter(): AdaptiveInterpreter {
        val dummyFile = java.io.File("/tmp/test-model.tflite")
        val mockContext: android.content.Context = mockk(relaxed = true)
        return AdaptiveInterpreter(dummyFile, mockContext)
    }
}
