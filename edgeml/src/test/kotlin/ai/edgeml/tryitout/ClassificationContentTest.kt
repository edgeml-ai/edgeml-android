package ai.edgeml.tryitout

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [extractTopKLabels] helper function used by [ClassificationContent].
 */
class ClassificationContentTest {

    // =========================================================================
    // extractTopKLabels
    // =========================================================================

    @Test
    fun `extractTopKLabels returns top-K sorted by confidence descending`() {
        val output = floatArrayOf(0.1f, 0.8f, 0.3f, 0.9f, 0.05f)
        val labels = extractTopKLabels(output, k = 3)

        assertEquals(3, labels.size)
        assertEquals(3, labels[0].index) // 0.9
        assertEquals(1, labels[1].index) // 0.8
        assertEquals(2, labels[2].index) // 0.3
    }

    @Test
    fun `extractTopKLabels preserves confidence values`() {
        val output = floatArrayOf(0.1f, 0.9f, 0.5f)
        val labels = extractTopKLabels(output, k = 2)

        assertEquals(0.9f, labels[0].confidence)
        assertEquals(0.5f, labels[1].confidence)
    }

    @Test
    fun `extractTopKLabels generates Class N names`() {
        val output = floatArrayOf(0.1f, 0.9f)
        val labels = extractTopKLabels(output, k = 2)

        assertEquals("Class 1", labels[0].name)
        assertEquals("Class 0", labels[1].name)
    }

    @Test
    fun `extractTopKLabels returns fewer than K when output is smaller`() {
        val output = floatArrayOf(0.5f, 0.3f)
        val labels = extractTopKLabels(output, k = 5)

        assertEquals(2, labels.size)
    }

    @Test
    fun `extractTopKLabels returns empty list for empty output`() {
        val labels = extractTopKLabels(floatArrayOf(), k = 5)
        assertTrue(labels.isEmpty())
    }

    @Test
    fun `extractTopKLabels handles single element`() {
        val output = floatArrayOf(0.42f)
        val labels = extractTopKLabels(output, k = 3)

        assertEquals(1, labels.size)
        assertEquals(0, labels[0].index)
        assertEquals(0.42f, labels[0].confidence)
    }

    @Test
    fun `extractTopKLabels handles equal confidences`() {
        val output = floatArrayOf(0.5f, 0.5f, 0.5f)
        val labels = extractTopKLabels(output, k = 3)

        assertEquals(3, labels.size)
        // All have same confidence; order among equals is stable (original order preserved)
        labels.forEach { assertEquals(0.5f, it.confidence) }
    }

    @Test
    fun `extractTopKLabels with K equals 1 returns highest confidence`() {
        val output = floatArrayOf(0.1f, 0.2f, 0.9f, 0.3f)
        val labels = extractTopKLabels(output, k = 1)

        assertEquals(1, labels.size)
        assertEquals(2, labels[0].index)
        assertEquals(0.9f, labels[0].confidence)
    }

    @Test
    fun `extractTopKLabels handles negative values`() {
        val output = floatArrayOf(-0.5f, -0.1f, 0.3f, -0.9f)
        val labels = extractTopKLabels(output, k = 2)

        assertEquals(2, labels.size)
        assertEquals(2, labels[0].index)  // 0.3
        assertEquals(1, labels[1].index)  // -0.1
    }

    @Test
    fun `extractTopKLabels with large output array performs correctly`() {
        val output = FloatArray(1000) { it.toFloat() / 1000f }
        val labels = extractTopKLabels(output, k = 5)

        assertEquals(5, labels.size)
        // Top 5 should be indices 999, 998, 997, 996, 995
        assertEquals(999, labels[0].index)
        assertEquals(998, labels[1].index)
        assertEquals(997, labels[2].index)
        assertEquals(996, labels[3].index)
        assertEquals(995, labels[4].index)
    }
}
