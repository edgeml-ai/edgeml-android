package ai.octomil.text

/**
 * Result of a [text.predictions.create][OctomilTextPredictions.create] call.
 *
 * @property predictions Ranked list of prediction candidates.
 * @property model Resolved model ID used for prediction.
 * @property latencyMs End-to-end prediction latency in milliseconds.
 */
data class TextPredictionResult(
    val predictions: List<TextPredictionCandidate>,
    val model: String,
    val latencyMs: Long? = null,
)

/**
 * A single prediction candidate with optional score.
 *
 * @property text Predicted next token or word.
 * @property score Model output score (not necessarily a calibrated probability).
 */
data class TextPredictionCandidate(
    val text: String,
    val score: Float? = null,
)
