package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class BillingMetric(val code: String) {
    HOSTED_REQUESTS_MONTHLY("hosted_requests_monthly"),
    API_REQUESTS_MONTHLY("api_requests_monthly"),
    MANAGED_BILLABLE_MICROS_MONTHLY("managed_billable_micros_monthly");

    companion object {
        fun fromCode(code: String): BillingMetric? =
            entries.firstOrNull { it.code == code }
    }
}
