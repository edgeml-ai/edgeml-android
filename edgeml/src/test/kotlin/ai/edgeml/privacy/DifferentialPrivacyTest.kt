package ai.edgeml.privacy

import ai.edgeml.config.PrivacyConfiguration
import ai.edgeml.training.WeightExtractor
import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DifferentialPrivacyTest {

    private fun tensor(name: String, vararg values: Float): WeightExtractor.TensorData {
        return WeightExtractor.TensorData(
            name = name,
            shape = intArrayOf(values.size),
            dataType = 0,
            data = values.toList().toFloatArray(),
        )
    }

    private fun l2Norm(tensors: Map<String, WeightExtractor.TensorData>): Double {
        return sqrt(
            tensors.values.sumOf { t ->
                t.data.sumOf { v -> (v.toDouble() * v.toDouble()) }
            },
        )
    }

    // =========================================================================
    // clipGradients
    // =========================================================================

    @Test
    fun `clipGradients clamps to L2 norm`() {
        // Create a tensor with L2 norm = 5.0
        val tensors = mapOf("w" to tensor("w", 3.0f, 4.0f))
        val originalNorm = l2Norm(tensors)
        assertEquals(5.0, originalNorm, 1e-6)

        val clipped = DifferentialPrivacy.clipGradients(tensors, clippingNorm = 2.0)
        val clippedNorm = l2Norm(clipped)

        assertTrue(clippedNorm <= 2.0 + 1e-6, "Clipped norm $clippedNorm should be <= 2.0")
    }

    @Test
    fun `clipGradients no-op when within norm`() {
        val tensors = mapOf("w" to tensor("w", 0.3f, 0.4f))
        val originalNorm = l2Norm(tensors)
        assertTrue(originalNorm < 1.0)

        val clipped = DifferentialPrivacy.clipGradients(tensors, clippingNorm = 1.0)

        // Values should be unchanged (same object returned)
        assertTrue(clipped === tensors, "Should return same map instance when no clipping needed")
    }

    @Test
    fun `clipGradients preserves direction`() {
        val tensors = mapOf("w" to tensor("w", 6.0f, 8.0f))

        val clipped = DifferentialPrivacy.clipGradients(tensors, clippingNorm = 5.0)
        val data = clipped["w"]!!.data

        // Original ratio 6:8 = 3:4 should be preserved
        val ratio = data[0] / data[1]
        assertEquals(0.75f, ratio, 1e-5f)
    }

    @Test
    fun `clipGradients works across multiple tensors`() {
        // Two tensors: [3, 0] and [0, 4] -> global L2 = 5
        val tensors = mapOf(
            "a" to tensor("a", 3.0f, 0.0f),
            "b" to tensor("b", 0.0f, 4.0f),
        )

        val clipped = DifferentialPrivacy.clipGradients(tensors, clippingNorm = 2.5)
        val clippedNorm = l2Norm(clipped)
        assertTrue(clippedNorm <= 2.5 + 1e-6)
    }

    // =========================================================================
    // addGaussianNoise
    // =========================================================================

    @Test
    fun `addGaussianNoise changes all values`() {
        val data = FloatArray(100) { 1.0f }
        val tensors = mapOf("w" to tensor("w", *data))

        val noisy = DifferentialPrivacy.addGaussianNoise(tensors, sigma = 1.0)
        val noisyData = noisy["w"]!!.data

        // Statistically, with sigma=1.0, the probability that even one of 100 elements
        // stays exactly at 1.0f is vanishingly small
        val unchangedCount = noisyData.count { it == 1.0f }
        assertTrue(unchangedCount == 0, "Expected all values to change, but $unchangedCount were unchanged")
    }

    @Test
    fun `addGaussianNoise preserves tensor metadata`() {
        val original = WeightExtractor.TensorData(
            name = "layer1/kernel",
            shape = intArrayOf(2, 3),
            dataType = 0,
            data = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
        )
        val tensors = mapOf("layer1/kernel" to original)

        val noisy = DifferentialPrivacy.addGaussianNoise(tensors, sigma = 0.1)
        val result = noisy["layer1/kernel"]!!

        assertEquals("layer1/kernel", result.name)
        assertTrue(intArrayOf(2, 3).contentEquals(result.shape))
        assertEquals(0, result.dataType)
        assertEquals(6, result.data.size)
    }

    @Test
    fun `addGaussianNoise with zero sigma returns original values`() {
        val tensors = mapOf("w" to tensor("w", 1.0f, 2.0f, 3.0f))

        val noisy = DifferentialPrivacy.addGaussianNoise(tensors, sigma = 0.0)
        val data = noisy["w"]!!.data

        assertEquals(1.0f, data[0], 1e-7f)
        assertEquals(2.0f, data[1], 1e-7f)
        assertEquals(3.0f, data[2], 1e-7f)
    }

    // =========================================================================
    // apply (full pipeline)
    // =========================================================================

    @Test
    fun `apply integrates clipping and noise`() {
        // Large norm tensor that will be clipped
        val tensors = mapOf("w" to tensor("w", 30.0f, 40.0f))
        val config = PrivacyConfiguration(
            enableDifferentialPrivacy = true,
            dpEpsilon = 1.0,
            dpClippingNorm = 1.0,
            dpDelta = 1e-5,
        )

        val result = DifferentialPrivacy.apply(tensors, config, sampleCount = 10)

        // The output should exist and have the right size
        assertEquals(1, result.size)
        assertEquals(2, result["w"]!!.data.size)

        // The original had norm 50; after clipping to 1.0 + noise,
        // values should be drastically different from originals
        val outputNorm = l2Norm(result)
        assertTrue(outputNorm < 50.0, "Output norm should be much less than original 50.0")
    }

    @Test
    fun `apply with DP disabled returns identity`() {
        val tensors = mapOf("w" to tensor("w", 1.0f, 2.0f, 3.0f))
        val config = PrivacyConfiguration(
            enableDifferentialPrivacy = false,
            dpEpsilon = 1.0,
            dpClippingNorm = 1.0,
        )

        val result = DifferentialPrivacy.apply(tensors, config, sampleCount = 10)

        // Should return same reference
        assertTrue(result === tensors, "With DP disabled, should return the same map instance")
    }

    @Test
    fun `noise magnitude scales with clipping norm and inverse epsilon`() {
        // Higher epsilon = less noise. Lower epsilon = more noise.
        // We test by comparing variance at two epsilon levels.
        val baseData = FloatArray(1000) { 0.0f }
        val tensors = mapOf("w" to tensor("w", *baseData))

        // High epsilon (less noise)
        val highEpsilonConfig = PrivacyConfiguration(
            enableDifferentialPrivacy = true,
            dpEpsilon = 10.0,
            dpClippingNorm = 1.0,
            dpDelta = 1e-5,
        )
        val highEpsilonResult = DifferentialPrivacy.apply(tensors, highEpsilonConfig, sampleCount = 100)

        // Low epsilon (more noise)
        val lowEpsilonConfig = PrivacyConfiguration(
            enableDifferentialPrivacy = true,
            dpEpsilon = 0.1,
            dpClippingNorm = 1.0,
            dpDelta = 1e-5,
        )
        val lowEpsilonResult = DifferentialPrivacy.apply(tensors, lowEpsilonConfig, sampleCount = 100)

        // Compare variance: low epsilon should have much higher variance
        val highEpsilonVariance = computeVariance(highEpsilonResult["w"]!!.data)
        val lowEpsilonVariance = computeVariance(lowEpsilonResult["w"]!!.data)

        assertTrue(
            lowEpsilonVariance > highEpsilonVariance * 10,
            "Low epsilon variance ($lowEpsilonVariance) should be much larger than " +
                "high epsilon variance ($highEpsilonVariance)",
        )
    }

    // =========================================================================
    // calibrateSigma
    // =========================================================================

    @Test
    fun `calibrateSigma returns expected value`() {
        val sigma = DifferentialPrivacy.calibrateSigma(
            clippingNorm = 1.0,
            epsilon = 1.0,
            delta = 1e-5,
        )

        // sigma = clippingNorm * sqrt(2 * ln(1.25 / 1e-5)) / epsilon
        // = 1.0 * sqrt(2 * ln(125000)) / 1.0
        // = sqrt(2 * 11.7361) = sqrt(23.4722) ≈ 4.8448
        assertTrue(sigma > 4.0 && sigma < 6.0, "Sigma should be around 4.8, got $sigma")
    }

    @Test
    fun `calibrateSigma scales linearly with clipping norm`() {
        val sigma1 = DifferentialPrivacy.calibrateSigma(1.0, 1.0, 1e-5)
        val sigma2 = DifferentialPrivacy.calibrateSigma(2.0, 1.0, 1e-5)

        assertEquals(sigma1 * 2.0, sigma2, 1e-10)
    }

    @Test
    fun `calibrateSigma scales inversely with epsilon`() {
        val sigma1 = DifferentialPrivacy.calibrateSigma(1.0, 1.0, 1e-5)
        val sigma2 = DifferentialPrivacy.calibrateSigma(1.0, 2.0, 1e-5)

        assertEquals(sigma1 / 2.0, sigma2, 1e-10)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun computeVariance(data: FloatArray): Double {
        val mean = data.map { it.toDouble() }.average()
        return data.map { (it.toDouble() - mean).let { d -> d * d } }.average()
    }
}
