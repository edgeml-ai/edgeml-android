package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class RouteMode(val code: String) {
    SDK_RUNTIME("sdk_runtime"),
    EXTERNAL_ENDPOINT("external_endpoint"),
    HOSTED_GATEWAY("hosted_gateway");

    companion object {
        fun fromCode(code: String): RouteMode? =
            entries.firstOrNull { it.code == code }
    }
}
