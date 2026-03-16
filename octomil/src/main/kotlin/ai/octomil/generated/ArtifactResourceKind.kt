package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ArtifactResourceKind(val code: String) {
    WEIGHTS("weights"),
    TOKENIZER("tokenizer"),
    TOKENIZER_CONFIG("tokenizer_config"),
    MODEL_CONFIG("model_config"),
    GENERATION_CONFIG("generation_config"),
    PROCESSOR("processor"),
    VOCAB("vocab"),
    MERGES("merges"),
    ADAPTER("adapter"),
    MANIFEST("manifest"),
    SIGNATURE("signature"),
    PROJECTOR("projector"),
    METADATA("metadata");

    companion object {
        fun fromCode(code: String): ArtifactResourceKind? =
            entries.firstOrNull { it.code == code }
    }
}
