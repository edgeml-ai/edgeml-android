package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ExecutionMode(val code: String) {
    SDK_RUNTIME("sdk_runtime"),
    HOSTED_GATEWAY("hosted_gateway"),
    EXTERNAL_ENDPOINT("external_endpoint");

    companion object {
        fun fromCode(code: String): ExecutionMode? =
            entries.firstOrNull { it.code == code }
    }
}
