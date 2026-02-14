package ai.edgeml.analytics

import ai.edgeml.api.EdgeMLApi
import timber.log.Timber

/**
 * Client for federated analytics queries across federation members.
 *
 * Provides methods for running cross-site statistical analyses including
 * descriptive statistics, t-tests, chi-square tests, and ANOVA.
 *
 * Use [ai.edgeml.client.EdgeMLClient.analytics] to create an instance.
 *
 * @param api The Retrofit API interface.
 * @param federationId The federation to run analytics against.
 */
class FederatedAnalyticsApi(
    private val api: EdgeMLApi,
    private val federationId: String,
) {
    // =========================================================================
    // Statistical Analyses
    // =========================================================================

    /**
     * Run descriptive statistics across groups in the federation.
     *
     * @param variable The variable to analyze.
     * @param groupBy How to group the data ("device_group" or "federation_member").
     * @param groupIds Optional list of specific group IDs to include.
     * @param includePercentiles Whether to include percentile calculations.
     * @param filters Optional filters to apply.
     * @return Descriptive statistics result.
     */
    suspend fun descriptive(
        variable: String,
        groupBy: String = "device_group",
        groupIds: List<String>? = null,
        includePercentiles: Boolean = true,
        filters: AnalyticsFilter? = null,
    ): Result<DescriptiveResult> {
        return try {
            val request = DescriptiveRequest(
                variable = variable,
                groupBy = groupBy,
                groupIds = groupIds,
                includePercentiles = includePercentiles,
                filters = filters,
            )
            val response = api.runDescriptive(federationId, request)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(Exception("Empty descriptive response"))
                Result.success(body)
            } else {
                Result.failure(Exception("Descriptive analytics failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Descriptive analytics error")
            Result.failure(e)
        }
    }

    /**
     * Run a two-sample t-test between two groups.
     *
     * @param variable The variable to test.
     * @param groupA First group identifier.
     * @param groupB Second group identifier.
     * @param confidenceLevel Confidence level for the test (default 0.95).
     * @param filters Optional filters to apply.
     * @return T-test result.
     */
    suspend fun tTest(
        variable: String,
        groupA: String,
        groupB: String,
        confidenceLevel: Double = 0.95,
        filters: AnalyticsFilter? = null,
    ): Result<TTestResult> {
        return try {
            val request = TTestRequest(
                variable = variable,
                groupA = groupA,
                groupB = groupB,
                confidenceLevel = confidenceLevel,
                filters = filters,
            )
            val response = api.runTTest(federationId, request)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(Exception("Empty t-test response"))
                Result.success(body)
            } else {
                Result.failure(Exception("T-test failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "T-test error")
            Result.failure(e)
        }
    }

    /**
     * Run a chi-square test of independence.
     *
     * @param variable1 First categorical variable.
     * @param variable2 Second categorical variable.
     * @param groupIds Optional list of group IDs to include.
     * @param confidenceLevel Confidence level for the test (default 0.95).
     * @param filters Optional filters to apply.
     * @return Chi-square test result.
     */
    suspend fun chiSquare(
        variable1: String,
        variable2: String,
        groupIds: List<String>? = null,
        confidenceLevel: Double = 0.95,
        filters: AnalyticsFilter? = null,
    ): Result<ChiSquareResult> {
        return try {
            val request = ChiSquareRequest(
                variable1 = variable1,
                variable2 = variable2,
                groupIds = groupIds,
                confidenceLevel = confidenceLevel,
                filters = filters,
            )
            val response = api.runChiSquare(federationId, request)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(Exception("Empty chi-square response"))
                Result.success(body)
            } else {
                Result.failure(Exception("Chi-square test failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Chi-square error")
            Result.failure(e)
        }
    }

    /**
     * Run a one-way ANOVA test across groups.
     *
     * @param variable The variable to analyze.
     * @param groupBy How to group the data ("device_group" or "federation_member").
     * @param groupIds Optional list of specific group IDs to include.
     * @param confidenceLevel Confidence level for the test (default 0.95).
     * @param postHoc Whether to include post-hoc pairwise comparisons.
     * @param filters Optional filters to apply.
     * @return ANOVA result.
     */
    suspend fun anova(
        variable: String,
        groupBy: String = "device_group",
        groupIds: List<String>? = null,
        confidenceLevel: Double = 0.95,
        postHoc: Boolean = true,
        filters: AnalyticsFilter? = null,
    ): Result<AnovaResult> {
        return try {
            val request = AnovaRequest(
                variable = variable,
                groupBy = groupBy,
                groupIds = groupIds,
                confidenceLevel = confidenceLevel,
                postHoc = postHoc,
                filters = filters,
            )
            val response = api.runAnova(federationId, request)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(Exception("Empty ANOVA response"))
                Result.success(body)
            } else {
                Result.failure(Exception("ANOVA failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "ANOVA error")
            Result.failure(e)
        }
    }

    // =========================================================================
    // Query History
    // =========================================================================

    /**
     * List past analytics queries for this federation.
     *
     * @param limit Maximum number of queries to return (default 50).
     * @param offset Offset for pagination (default 0).
     * @return Analytics query list response.
     */
    suspend fun listQueries(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<AnalyticsQueryListResponse> {
        return try {
            val response = api.listAnalyticsQueries(federationId, limit, offset)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(Exception("Empty queries response"))
                Result.success(body)
            } else {
                Result.failure(Exception("List queries failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "List queries error")
            Result.failure(e)
        }
    }

    /**
     * Get a specific analytics query by ID.
     *
     * @param queryId The query identifier.
     * @return The analytics query with its result.
     */
    suspend fun getQuery(queryId: String): Result<AnalyticsQuery> {
        return try {
            val response = api.getAnalyticsQuery(federationId, queryId)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(Exception("Empty query response"))
                Result.success(body)
            } else {
                Result.failure(Exception("Get query failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Get query error")
            Result.failure(e)
        }
    }
}
