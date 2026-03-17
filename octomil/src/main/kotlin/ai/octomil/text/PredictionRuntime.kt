package ai.octomil.text

/**
 * Pluggable interface for handle-based next-token prediction.
 *
 * Supports concurrent models via opaque handles (e.g. chat model +
 * prediction model loaded simultaneously).
 */
internal interface PredictionRuntime {
    /** Load a prediction model and return an opaque handle. */
    suspend fun loadHandle(modelPath: String): Long

    /** Predict top-k next tokens given context text. */
    suspend fun predictNext(handle: Long, text: String, k: Int): List<PredictionCandidate>

    /** Unload a model by handle. */
    suspend fun unloadHandle(handle: Long)
}
