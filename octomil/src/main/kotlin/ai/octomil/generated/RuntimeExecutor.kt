package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class RuntimeExecutor(val code: String) {
    COREML("coreml"),
    MLX("mlx"),
    LITERT("litert"),
    ONNXRUNTIME("onnxruntime"),
    LLAMACPP("llamacpp"),
    MNN("mnn"),
    TRANSFORMERSJS("transformersjs"),
    CLOUD("cloud"),
    WHISPER("whisper"),
    MLC("mlc"),
    CACTUS("cactus"),
    SAMSUNG_ONE("samsung_one"),
    EXECUTORCH("executorch"),
    ECHO("echo");

    companion object {
        fun fromCode(code: String): RuntimeExecutor? =
            entries.firstOrNull { it.code == code }
    }
}
