// Auto-generated from octomil-contracts. Do not edit.

enum class ThermalState(val code: String) {
    NOMINAL("nominal"),
    FAIR("fair"),
    SERIOUS("serious"),
    CRITICAL("critical");

    companion object {
        fun fromCode(code: String): ThermalState? =
            entries.firstOrNull { it.code == code }
    }
}
