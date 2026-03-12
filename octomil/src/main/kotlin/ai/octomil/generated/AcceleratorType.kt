// Auto-generated from octomil-contracts. Do not edit.
package ai.octomil.generated

enum class AcceleratorType(val code: String) {
    GPU("gpu"),
    NPU("npu"),
    ANE("ane"),
    NNAPI("nnapi"),
    WEBGPU("webgpu");

    companion object {
        fun fromCode(code: String): AcceleratorType? =
            entries.firstOrNull { it.code == code }
    }
}
