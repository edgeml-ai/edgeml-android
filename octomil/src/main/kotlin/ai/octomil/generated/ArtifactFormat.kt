package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ArtifactFormat(val code: String) {
    COREML("coreml"),
    TFLITE("tflite"),
    ONNX("onnx"),
    GGUF("gguf"),
    MLX("mlx"),
    MNN("mnn"),
    TRANSFORMERSJS("transformersjs");

    companion object {
        fun fromCode(code: String): ArtifactFormat? =
            entries.firstOrNull { it.code == code }
    }
}
