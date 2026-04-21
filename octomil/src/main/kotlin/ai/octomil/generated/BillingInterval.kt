package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class BillingInterval(val code: String) {
    MONTHLY("monthly"),
    ANNUAL("annual");

    companion object {
        fun fromCode(code: String): BillingInterval? =
            entries.firstOrNull { it.code == code }
    }
}
