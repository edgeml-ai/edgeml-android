package ai.edgeml.analytics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =========================================================================
// Request Models
// =========================================================================

/**
 * Filter criteria for analytics queries.
 */
@Serializable
data class AnalyticsFilter(
    @SerialName("start_time")
    val startTime: String? = null,
    @SerialName("end_time")
    val endTime: String? = null,
    @SerialName("device_platform")
    val devicePlatform: String? = null,
    @SerialName("min_sample_count")
    val minSampleCount: Int? = null,
)

@Serializable
data class DescriptiveRequest(
    @SerialName("variable")
    val variable: String,
    @SerialName("group_by")
    val groupBy: String = "device_group",
    @SerialName("group_ids")
    val groupIds: List<String>? = null,
    @SerialName("include_percentiles")
    val includePercentiles: Boolean = true,
    @SerialName("filters")
    val filters: AnalyticsFilter? = null,
)

@Serializable
data class TTestRequest(
    @SerialName("variable")
    val variable: String,
    @SerialName("group_a")
    val groupA: String,
    @SerialName("group_b")
    val groupB: String,
    @SerialName("confidence_level")
    val confidenceLevel: Double = 0.95,
    @SerialName("filters")
    val filters: AnalyticsFilter? = null,
)

@Serializable
data class ChiSquareRequest(
    @SerialName("variable_1")
    val variable1: String,
    @SerialName("variable_2")
    val variable2: String,
    @SerialName("group_ids")
    val groupIds: List<String>? = null,
    @SerialName("confidence_level")
    val confidenceLevel: Double = 0.95,
    @SerialName("filters")
    val filters: AnalyticsFilter? = null,
)

@Serializable
data class AnovaRequest(
    @SerialName("variable")
    val variable: String,
    @SerialName("group_by")
    val groupBy: String = "device_group",
    @SerialName("group_ids")
    val groupIds: List<String>? = null,
    @SerialName("confidence_level")
    val confidenceLevel: Double = 0.95,
    @SerialName("post_hoc")
    val postHoc: Boolean = true,
    @SerialName("filters")
    val filters: AnalyticsFilter? = null,
)

// =========================================================================
// Response Models
// =========================================================================

/**
 * Result of a descriptive statistics query.
 */
@Serializable
data class DescriptiveResult(
    @SerialName("variable")
    val variable: String,
    @SerialName("group_by")
    val groupBy: String,
    @SerialName("groups")
    val groups: List<GroupStats>,
)

/**
 * Descriptive statistics for a single group.
 */
@Serializable
data class GroupStats(
    @SerialName("group_id")
    val groupId: String,
    @SerialName("count")
    val count: Int,
    @SerialName("mean")
    val mean: Double,
    @SerialName("median")
    val median: Double? = null,
    @SerialName("std_dev")
    val stdDev: Double? = null,
    @SerialName("min")
    val min: Double? = null,
    @SerialName("max")
    val max: Double? = null,
    @SerialName("percentiles")
    val percentiles: Map<String, Double>? = null,
)

/**
 * Result of a two-sample t-test.
 */
@Serializable
data class TTestResult(
    @SerialName("variable")
    val variable: String,
    @SerialName("group_a")
    val groupA: String,
    @SerialName("group_b")
    val groupB: String,
    @SerialName("t_statistic")
    val tStatistic: Double,
    @SerialName("p_value")
    val pValue: Double,
    @SerialName("degrees_of_freedom")
    val degreesOfFreedom: Double,
    @SerialName("confidence_interval")
    val confidenceInterval: ConfidenceInterval? = null,
    @SerialName("significant")
    val significant: Boolean,
)

/**
 * Confidence interval for a statistical test.
 */
@Serializable
data class ConfidenceInterval(
    @SerialName("lower")
    val lower: Double,
    @SerialName("upper")
    val upper: Double,
    @SerialName("level")
    val level: Double,
)

/**
 * Result of a chi-square test of independence.
 */
@Serializable
data class ChiSquareResult(
    @SerialName("variable_1")
    val variable1: String,
    @SerialName("variable_2")
    val variable2: String,
    @SerialName("chi_square_statistic")
    val chiSquareStatistic: Double,
    @SerialName("p_value")
    val pValue: Double,
    @SerialName("degrees_of_freedom")
    val degreesOfFreedom: Int,
    @SerialName("significant")
    val significant: Boolean,
    @SerialName("cramers_v")
    val cramersV: Double? = null,
)

/**
 * Result of a one-way ANOVA test.
 */
@Serializable
data class AnovaResult(
    @SerialName("variable")
    val variable: String,
    @SerialName("group_by")
    val groupBy: String,
    @SerialName("f_statistic")
    val fStatistic: Double,
    @SerialName("p_value")
    val pValue: Double,
    @SerialName("degrees_of_freedom_between")
    val degreesOfFreedomBetween: Int,
    @SerialName("degrees_of_freedom_within")
    val degreesOfFreedomWithin: Int,
    @SerialName("significant")
    val significant: Boolean,
    @SerialName("post_hoc_pairs")
    val postHocPairs: List<PostHocPair>? = null,
)

/**
 * Post-hoc pairwise comparison result.
 */
@Serializable
data class PostHocPair(
    @SerialName("group_a")
    val groupA: String,
    @SerialName("group_b")
    val groupB: String,
    @SerialName("p_value")
    val pValue: Double,
    @SerialName("significant")
    val significant: Boolean,
)

/**
 * A saved analytics query with its result.
 */
@Serializable
data class AnalyticsQuery(
    @SerialName("id")
    val id: String,
    @SerialName("federation_id")
    val federationId: String,
    @SerialName("query_type")
    val queryType: String,
    @SerialName("variable")
    val variable: String,
    @SerialName("group_by")
    val groupBy: String,
    @SerialName("status")
    val status: String,
    @SerialName("result")
    val result: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    @SerialName("error_message")
    val errorMessage: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Response for listing analytics queries.
 */
@Serializable
data class AnalyticsQueryListResponse(
    @SerialName("queries")
    val queries: List<AnalyticsQuery>,
    @SerialName("total")
    val total: Int,
)
