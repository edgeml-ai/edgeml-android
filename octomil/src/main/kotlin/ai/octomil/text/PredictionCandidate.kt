package ai.octomil.text

/**
 * A single next-token prediction candidate.
 *
 * Temporary internal type — will be replaced by a contract-generated
 * type in the canonical `text.predictions.create` API (PR 2).
 */
internal data class PredictionCandidate(val text: String, val confidence: Float)
