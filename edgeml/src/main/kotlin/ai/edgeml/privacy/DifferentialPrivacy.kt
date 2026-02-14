package ai.edgeml.privacy

import ai.edgeml.config.PrivacyConfiguration
import ai.edgeml.training.WeightExtractor
import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Differential privacy utilities for federated learning weight updates.
 *
 * Implements the DP-SGD pipeline:
 * 1. **Gradient clipping**: L2 norm clipping to bound sensitivity.
 * 2. **Gaussian noise injection**: Calibrated N(0, sigma^2) noise.
 * 3. **Full pipeline**: Clip + calibrate sigma + add noise.
 *
 * Uses [SecureRandom] with Box-Muller transform for cryptographic Gaussian samples.
 */
object DifferentialPrivacy {
    private val secureRandom = SecureRandom()

    /**
     * Clip gradient tensors to a maximum L2 norm.
     *
     * Computes the global L2 norm across all tensors. If the norm exceeds
     * [clippingNorm], all values are scaled by (clippingNorm / globalNorm).
     *
     * @param tensors Weight delta tensors to clip
     * @param clippingNorm Maximum allowed L2 norm
     * @return Clipped tensors (new instances; originals are not modified)
     */
    internal fun clipGradients(
        tensors: Map<String, WeightExtractor.TensorData>,
        clippingNorm: Double,
    ): Map<String, WeightExtractor.TensorData> {
        val globalNormSq = tensors.values.sumOf { tensor ->
            tensor.data.sumOf { v -> (v.toDouble() * v.toDouble()) }
        }
        val globalNorm = sqrt(globalNormSq)

        if (globalNorm <= clippingNorm) {
            return tensors
        }

        val scale = (clippingNorm / globalNorm).toFloat()
        return tensors.mapValues { (_, tensor) ->
            WeightExtractor.TensorData(
                name = tensor.name,
                shape = tensor.shape.copyOf(),
                dataType = tensor.dataType,
                data = FloatArray(tensor.data.size) { i -> tensor.data[i] * scale },
            )
        }
    }

    /**
     * Add Gaussian noise N(0, sigma^2) to each element of each tensor.
     *
     * Uses Box-Muller transform with [SecureRandom] for cryptographic randomness.
     *
     * @param tensors Weight delta tensors
     * @param sigma Standard deviation of the Gaussian noise
     * @return Noisy tensors (new instances; originals are not modified)
     */
    internal fun addGaussianNoise(
        tensors: Map<String, WeightExtractor.TensorData>,
        sigma: Double,
    ): Map<String, WeightExtractor.TensorData> {
        return tensors.mapValues { (_, tensor) ->
            val noisyData = FloatArray(tensor.data.size) { i ->
                tensor.data[i] + (gaussianSample() * sigma).toFloat()
            }
            WeightExtractor.TensorData(
                name = tensor.name,
                shape = tensor.shape.copyOf(),
                dataType = tensor.dataType,
                data = noisyData,
            )
        }
    }

    /**
     * Full DP-SGD pipeline: clip gradients, calibrate noise, add noise.
     *
     * Sigma is calibrated using the analytic Gaussian mechanism:
     *   sigma = (clippingNorm * sqrt(2 * ln(1.25 / delta))) / epsilon
     *
     * If [PrivacyConfiguration.enableDifferentialPrivacy] is false, returns
     * the input tensors unmodified.
     *
     * @param tensors Weight delta tensors
     * @param config Privacy configuration with epsilon, delta, and clipping norm
     * @param sampleCount Number of training samples (unused in current calibration; reserved for subsampled mechanisms)
     * @return Privacy-transformed tensors
     */
    internal fun apply(
        tensors: Map<String, WeightExtractor.TensorData>,
        config: PrivacyConfiguration,
        @Suppress("UNUSED_PARAMETER") sampleCount: Int,
    ): Map<String, WeightExtractor.TensorData> {
        if (!config.enableDifferentialPrivacy) {
            return tensors
        }

        val clipped = clipGradients(tensors, config.dpClippingNorm)

        val sigma = calibrateSigma(
            clippingNorm = config.dpClippingNorm,
            epsilon = config.dpEpsilon,
            delta = config.dpDelta,
        )

        return addGaussianNoise(clipped, sigma)
    }

    /**
     * Calibrate sigma for the analytic Gaussian mechanism.
     *
     * sigma = (clippingNorm * sqrt(2 * ln(1.25 / delta))) / epsilon
     */
    internal fun calibrateSigma(
        clippingNorm: Double,
        epsilon: Double,
        delta: Double,
    ): Double {
        return clippingNorm * sqrt(2.0 * ln(1.25 / delta)) / epsilon
    }

    /**
     * Generate a single Gaussian sample using Box-Muller transform.
     */
    private fun gaussianSample(): Double {
        var u1: Double
        var u2: Double
        do {
            u1 = secureRandom.nextDouble()
            u2 = secureRandom.nextDouble()
        } while (u1 <= 1e-10) // avoid log(0)

        return sqrt(-2.0 * ln(u1)) * StrictMath.cos(2.0 * Math.PI * u2)
    }
}
