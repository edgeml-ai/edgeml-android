// Auto-generated from octomil-contracts. Do not edit.

enum class ModelSourceFormat(val code: String) {
    SAFETENSORS("safetensors"),
    PYTORCH("pytorch"),
    TENSORFLOW("tensorflow"),
    ONNX("onnx"),
    GGUF("gguf"),
    CUSTOM("custom");

    companion object {
        fun fromCode(code: String): ModelSourceFormat? =
            entries.firstOrNull { it.code == code }
    }
}
