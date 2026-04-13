package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class DeliveryMode(val code: String) {
    BUNDLED("bundled"),
    MANAGED("managed"),
    CLOUD("cloud");

    companion object {
        fun fromCode(code: String): DeliveryMode? =
            entries.firstOrNull { it.code == code }
    }
}
