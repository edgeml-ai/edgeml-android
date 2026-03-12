// Auto-generated from octomil-contracts. Do not edit.
package ai.octomil.generated

enum class RuntimeExecutor(val code: String) {
    COREML("coreml"),
    MLX("mlx"),
    LITERT("litert"),
    ONNXRUNTIME("onnxruntime"),
    LLAMACPP("llamacpp"),
    MNN("mnn"),
    TRANSFORMERSJS("transformersjs"),
    CLOUD("cloud");

    companion object {
        fun fromCode(code: String): RuntimeExecutor? =
            entries.firstOrNull { it.code == code }
    }
}
