package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

data class PlanLimits(
    val maxDevices: Int?,
    val maxModels: Int?,
    val maxEnvironments: Int?,
    val storageGb: Int?,
    val requestsMonthly: Int?,
    val trainingRoundsMonthly: Int?,
    val federatedRoundsMonthly: Int?,
    val modelDownloadsMonthly: Int?,
    val modelConversionsMonthly: Int?,
    val dataRetentionDays: Int?
)

data class PlanFeatures(
    val sso: Boolean,
    val federatedLearning: Boolean,
    val differentialPrivacy: Boolean,
    val secureAggregation: Boolean,
    val hipaaMode: Boolean,
    val advancedMonitoring: Boolean,
    val webhooks: Boolean,
    val experiments: Boolean,
    val rollouts: Boolean,
    val scim: Boolean,
    val siemExport: Boolean
)

data class PlanPricing(
    val monthlyCents: Int?,
    val annualCents: Int?,
    val overagePerDeviceCents: Int?
)

enum class BillingSupportTier(val code: String) {
    COMMUNITY("community"),
    EMAIL("email"),
    DEDICATED("dedicated");
}

enum class BillingPlan(
    val code: String,
    val displayName: String,
    val limits: PlanLimits,
    val features: PlanFeatures,
    val pricing: PlanPricing,
    val support: BillingSupportTier,
) {
    FREE(
        code = "free",
        displayName = "Developer",
        limits = PlanLimits(maxDevices = 25, maxModels = 3, maxEnvironments = 1, storageGb = 5, requestsMonthly = 100000, trainingRoundsMonthly = 100, federatedRoundsMonthly = 1, modelDownloadsMonthly = 2500, modelConversionsMonthly = 20, dataRetentionDays = 7),
        features = PlanFeatures(sso = false, federatedLearning = true, differentialPrivacy = false, secureAggregation = false, hipaaMode = false, advancedMonitoring = false, webhooks = false, experiments = true, rollouts = true, scim = false, siemExport = false),
        pricing = PlanPricing(monthlyCents = 0, annualCents = 0, overagePerDeviceCents = 0),
        support = BillingSupportTier.COMMUNITY,
    ),
    TEAM(
        code = "team",
        displayName = "Team",
        limits = PlanLimits(maxDevices = 1000, maxModels = 20, maxEnvironments = 3, storageGb = 100, requestsMonthly = 1000000, trainingRoundsMonthly = 10000, federatedRoundsMonthly = 10, modelDownloadsMonthly = 50000, modelConversionsMonthly = 500, dataRetentionDays = 90),
        features = PlanFeatures(sso = true, federatedLearning = true, differentialPrivacy = false, secureAggregation = false, hipaaMode = false, advancedMonitoring = true, webhooks = true, experiments = true, rollouts = true, scim = false, siemExport = false),
        pricing = PlanPricing(monthlyCents = 120000, annualCents = 1152000, overagePerDeviceCents = 5),
        support = BillingSupportTier.EMAIL,
    ),
    ENTERPRISE(
        code = "enterprise",
        displayName = "Enterprise",
        limits = PlanLimits(maxDevices = null, maxModels = null, maxEnvironments = null, storageGb = 10000, requestsMonthly = 100000000, trainingRoundsMonthly = null, federatedRoundsMonthly = null, modelDownloadsMonthly = null, modelConversionsMonthly = null, dataRetentionDays = null),
        features = PlanFeatures(sso = true, federatedLearning = true, differentialPrivacy = true, secureAggregation = true, hipaaMode = true, advancedMonitoring = true, webhooks = true, experiments = true, rollouts = true, scim = true, siemExport = true),
        pricing = PlanPricing(monthlyCents = null, annualCents = null, overagePerDeviceCents = null),
        support = BillingSupportTier.DEDICATED,
    );

    companion object {
        fun fromCode(code: String): BillingPlan? =
            entries.firstOrNull { it.code == code }
    }
}
