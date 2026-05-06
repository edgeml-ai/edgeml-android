package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class EvaluationPhase(val code: String) {
    PRE_INFERENCE("pre_inference"),
    DURING_INFERENCE("during_inference"),
    POST_INFERENCE("post_inference");

    companion object {
        fun fromCode(code: String): EvaluationPhase? =
            entries.firstOrNull { it.code == code }
    }
}
