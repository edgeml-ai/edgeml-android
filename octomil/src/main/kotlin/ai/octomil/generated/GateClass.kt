package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class GateClass(val code: String) {
    READINESS("readiness"),
    PERFORMANCE("performance"),
    OUTPUT_QUALITY("output_quality");

    companion object {
        fun fromCode(code: String): GateClass? =
            entries.firstOrNull { it.code == code }
    }
}
