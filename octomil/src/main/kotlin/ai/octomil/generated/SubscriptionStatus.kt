package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class SubscriptionStatus(val code: String) {
    ACTIVE("active"),
    PAST_DUE("past_due"),
    CANCELED("canceled"),
    TRIALING("trialing"),
    INCOMPLETE("incomplete"),
    INCOMPLETE_EXPIRED("incomplete_expired"),
    UNPAID("unpaid"),
    PAUSED("paused");

    companion object {
        fun fromCode(code: String): SubscriptionStatus? =
            entries.firstOrNull { it.code == code }
    }
}
