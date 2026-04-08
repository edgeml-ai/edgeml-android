package ai.octomil.training

/**
 * Information about model tensors.
 */
data class TensorInfo(
    val inputShape: IntArray,
    val outputShape: IntArray,
    val inputType: String,
    val outputType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TensorInfo

        if (!inputShape.contentEquals(other.inputShape)) return false
        if (!outputShape.contentEquals(other.outputShape)) return false
        if (inputType != other.inputType) return false
        if (outputType != other.outputType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = inputShape.contentHashCode()
        result = 31 * result + outputShape.contentHashCode()
        result = 31 * result + inputType.hashCode()
        result = 31 * result + outputType.hashCode()
        return result
    }
}

/**
 * Result of a warmup pass including delegate benchmark.
 *
 * @property coldInferenceMs First inference time (includes JIT/shader/delegate init).
 * @property warmInferenceMs Second inference time (steady-state latency with selected delegate).
 * @property cpuInferenceMs CPU-only warm latency, if GPU was benchmarked. Null when GPU wasn't active.
 * @property usingGpu Whether the GPU delegate is active after warmup (may be false if it was disabled).
 * @property delegateDisabled True if GPU was disabled during warmup because CPU was faster.
 */
data class WarmupResult(
    val coldInferenceMs: Double,
    val warmInferenceMs: Double,
    val cpuInferenceMs: Double?,
    val usingGpu: Boolean,
    /** Which delegate survived warmup: "vendor_npu", "gpu", or "cpu" */
    val activeDelegate: String,
    /** Delegates that were disabled during cascade (e.g. ["vendor_npu", "gpu"]) */
    val disabledDelegates: List<String> = emptyList(),
) {
    /** True if any delegate was disabled during warmup. */
    val delegateDisabled: Boolean get() = disabledDelegates.isNotEmpty()
}
