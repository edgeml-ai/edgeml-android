package ai.edgeml.secagg

import kotlin.math.ceil

/**
 * Quantization pipeline for SecAgg+: converts floating-point weight updates
 * to integers suitable for additive mod masking, then back.
 *
 * Pipeline: clip to [-clipping_range, +clipping_range] -> shift to [0, 2*clipping_range]
 *           -> scale to [0, target_range] -> stochastic round -> (mask mod mod_range)
 *           -> dequantize
 *
 * Matches the Python SDK's `quantize()` / `dequantize()` and the server's
 * `_quantize()` / `_dequantize()` functions.
 */
object Quantization {

    /**
     * Stochastic quantize floats to integers in [0, target_range].
     *
     * Follows the Flower SecAgg+ quantization scheme:
     *   1. Clip values to [-clipping_range, +clipping_range]
     *   2. Shift to [0, 2 * clipping_range]
     *   3. Scale to [0, target_range]
     *   4. Stochastic round to integers
     *
     * The inverse is [dequantize].
     *
     * @param values The float values (e.g., weight deltas).
     * @param clippingRange Symmetric clipping bound.
     * @param targetRange Integer target range (e.g., 2^22). Long to support large ranges.
     * @return List of quantized integers in [0, targetRange].
     */
    fun quantize(
        values: List<Float>,
        clippingRange: Float,
        targetRange: Long,
    ): List<Long> {
        if (values.isEmpty()) return emptyList()
        if (clippingRange == 0.0f) return List(values.size) { 0L }

        val quantizer = targetRange.toDouble() / (2.0 * clippingRange)

        return values.map { v ->
            val clipped = v.toDouble().coerceIn(-clippingRange.toDouble(), clippingRange.toDouble())
            val preQuantized = (clipped + clippingRange) * quantizer
            stochasticRound(preQuantized)
        }
    }

    /**
     * Reverse [quantize] -- map integers back to floats in
     * [-clippingRange, +clippingRange].
     *
     * @param quantized The quantized integer values.
     * @param clippingRange The symmetric clipping bound used during quantization.
     * @param targetRange The target range used during quantization.
     * @return List of reconstructed float values.
     */
    fun dequantize(
        quantized: List<Long>,
        clippingRange: Float,
        targetRange: Long,
    ): List<Float> {
        if (quantized.isEmpty()) return emptyList()
        if (targetRange == 0L) return List(quantized.size) { 0.0f }

        val scale = (2.0 * clippingRange) / targetRange
        val shift = -clippingRange.toDouble()
        return quantized.map { q ->
            (q * scale + shift).toFloat()
        }
    }

    /**
     * Stochastic rounding: ceil(x) with probability (x - floor(x)),
     * floor(x) with probability (ceil(x) - x).
     *
     * Matches the Flower SecAgg+ stochastic rounding implementation.
     */
    private fun stochasticRound(value: Double): Long {
        val c = ceil(value).toLong()
        val probDown = c.toDouble() - value
        return if (Math.random() < probDown) c - 1 else c
    }
}
