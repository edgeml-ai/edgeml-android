package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class CompatibilityLevel(val code: String) {
    STABLE("stable"),
    BETA("beta"),
    EXPERIMENTAL("experimental"),
    COMPATIBILITY("compatibility");

    companion object {
        fun fromCode(code: String): CompatibilityLevel? =
            entries.firstOrNull { it.code == code }
    }
}
