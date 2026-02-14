package ai.edgeml.analytics

import ai.edgeml.api.EdgeMLApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FederatedAnalyticsApiTest {

    private lateinit var api: EdgeMLApi
    private lateinit var analyticsApi: FederatedAnalyticsApi
    private val federationId = "fed-123"

    @Before
    fun setUp() {
        api = mockk<EdgeMLApi>(relaxed = true)
        analyticsApi = FederatedAnalyticsApi(api, federationId)
    }

    // =========================================================================
    // Descriptive
    // =========================================================================

    @Test
    fun `descriptive returns result on success`() = runTest {
        val expected = DescriptiveResult(
            variable = "accuracy",
            groupBy = "device_group",
            groups = listOf(
                GroupStats(
                    groupId = "g1",
                    count = 100,
                    mean = 0.85,
                    median = 0.86,
                    stdDev = 0.05,
                    min = 0.7,
                    max = 0.95,
                ),
            ),
        )
        coEvery { api.runDescriptive(federationId, any()) } returns Response.success(expected)

        val result = analyticsApi.descriptive(variable = "accuracy")

        assertTrue(result.isSuccess)
        val body = result.getOrNull()!!
        assertEquals("accuracy", body.variable)
        assertEquals("device_group", body.groupBy)
        assertEquals(1, body.groups.size)
        assertEquals(0.85, body.groups[0].mean)
    }

    @Test
    fun `descriptive passes parameters correctly`() = runTest {
        val expected = DescriptiveResult(
            variable = "loss",
            groupBy = "federation_member",
            groups = emptyList(),
        )
        coEvery { api.runDescriptive(federationId, any()) } returns Response.success(expected)

        analyticsApi.descriptive(
            variable = "loss",
            groupBy = "federation_member",
            groupIds = listOf("g1", "g2"),
            includePercentiles = false,
        )

        coVerify {
            api.runDescriptive(
                federationId,
                match {
                    it.variable == "loss" &&
                        it.groupBy == "federation_member" &&
                        it.groupIds == listOf("g1", "g2") &&
                        !it.includePercentiles
                },
            )
        }
    }

    @Test
    fun `descriptive returns failure on HTTP error`() = runTest {
        coEvery { api.runDescriptive(federationId, any()) } returns
            Response.error(400, okhttp3.ResponseBody.Companion.create(null, ""))

        val result = analyticsApi.descriptive(variable = "accuracy")

        assertTrue(result.isFailure)
    }

    // =========================================================================
    // T-Test
    // =========================================================================

    @Test
    fun `tTest returns result on success`() = runTest {
        val expected = TTestResult(
            variable = "accuracy",
            groupA = "ios",
            groupB = "android",
            tStatistic = 2.35,
            pValue = 0.021,
            degreesOfFreedom = 98.0,
            significant = true,
            confidenceInterval = ConfidenceInterval(lower = 0.01, upper = 0.15, level = 0.95),
        )
        coEvery { api.runTTest(federationId, any()) } returns Response.success(expected)

        val result = analyticsApi.tTest(
            variable = "accuracy",
            groupA = "ios",
            groupB = "android",
        )

        assertTrue(result.isSuccess)
        val body = result.getOrNull()!!
        assertEquals("accuracy", body.variable)
        assertEquals("ios", body.groupA)
        assertEquals("android", body.groupB)
        assertEquals(2.35, body.tStatistic)
        assertTrue(body.significant)
        assertNotNull(body.confidenceInterval)
        assertEquals(0.95, body.confidenceInterval?.level)
    }

    @Test
    fun `tTest passes confidence level`() = runTest {
        val expected = TTestResult(
            variable = "loss",
            groupA = "a",
            groupB = "b",
            tStatistic = 1.0,
            pValue = 0.3,
            degreesOfFreedom = 50.0,
            significant = false,
        )
        coEvery { api.runTTest(federationId, any()) } returns Response.success(expected)

        analyticsApi.tTest(
            variable = "loss",
            groupA = "a",
            groupB = "b",
            confidenceLevel = 0.99,
        )

        coVerify {
            api.runTTest(
                federationId,
                match { it.confidenceLevel == 0.99 },
            )
        }
    }

    // =========================================================================
    // Chi-Square
    // =========================================================================

    @Test
    fun `chiSquare returns result on success`() = runTest {
        val expected = ChiSquareResult(
            variable1 = "platform",
            variable2 = "outcome",
            chiSquareStatistic = 12.5,
            pValue = 0.002,
            degreesOfFreedom = 3,
            significant = true,
            cramersV = 0.35,
        )
        coEvery { api.runChiSquare(federationId, any()) } returns Response.success(expected)

        val result = analyticsApi.chiSquare(
            variable1 = "platform",
            variable2 = "outcome",
        )

        assertTrue(result.isSuccess)
        val body = result.getOrNull()!!
        assertEquals("platform", body.variable1)
        assertEquals("outcome", body.variable2)
        assertEquals(12.5, body.chiSquareStatistic)
        assertTrue(body.significant)
        assertEquals(0.35, body.cramersV)
    }

    @Test
    fun `chiSquare passes group IDs`() = runTest {
        val expected = ChiSquareResult(
            variable1 = "a",
            variable2 = "b",
            chiSquareStatistic = 1.0,
            pValue = 0.5,
            degreesOfFreedom = 1,
            significant = false,
        )
        coEvery { api.runChiSquare(federationId, any()) } returns Response.success(expected)

        analyticsApi.chiSquare(
            variable1 = "a",
            variable2 = "b",
            groupIds = listOf("g1", "g2"),
        )

        coVerify {
            api.runChiSquare(
                federationId,
                match { it.groupIds == listOf("g1", "g2") },
            )
        }
    }

    // =========================================================================
    // ANOVA
    // =========================================================================

    @Test
    fun `anova returns result on success`() = runTest {
        val expected = AnovaResult(
            variable = "latency",
            groupBy = "device_group",
            fStatistic = 5.67,
            pValue = 0.004,
            degreesOfFreedomBetween = 2,
            degreesOfFreedomWithin = 97,
            significant = true,
            postHocPairs = listOf(
                PostHocPair(groupA = "g1", groupB = "g2", pValue = 0.003, significant = true),
                PostHocPair(groupA = "g1", groupB = "g3", pValue = 0.12, significant = false),
            ),
        )
        coEvery { api.runAnova(federationId, any()) } returns Response.success(expected)

        val result = analyticsApi.anova(variable = "latency")

        assertTrue(result.isSuccess)
        val body = result.getOrNull()!!
        assertEquals("latency", body.variable)
        assertEquals(5.67, body.fStatistic)
        assertTrue(body.significant)
        assertEquals(2, body.postHocPairs?.size)
        assertTrue(body.postHocPairs!![0].significant)
    }

    @Test
    fun `anova passes post hoc flag`() = runTest {
        val expected = AnovaResult(
            variable = "v",
            groupBy = "device_group",
            fStatistic = 1.0,
            pValue = 0.5,
            degreesOfFreedomBetween = 1,
            degreesOfFreedomWithin = 50,
            significant = false,
        )
        coEvery { api.runAnova(federationId, any()) } returns Response.success(expected)

        analyticsApi.anova(variable = "v", postHoc = false)

        coVerify {
            api.runAnova(
                federationId,
                match { !it.postHoc },
            )
        }
    }

    // =========================================================================
    // List Queries
    // =========================================================================

    @Test
    fun `listQueries returns result on success`() = runTest {
        val expected = AnalyticsQueryListResponse(
            queries = listOf(
                AnalyticsQuery(
                    id = "q1",
                    federationId = federationId,
                    queryType = "descriptive",
                    variable = "accuracy",
                    groupBy = "device_group",
                    status = "completed",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:01Z",
                ),
            ),
            total = 1,
        )
        coEvery { api.listAnalyticsQueries(federationId, any(), any()) } returns
            Response.success(expected)

        val result = analyticsApi.listQueries(limit = 10, offset = 5)

        assertTrue(result.isSuccess)
        val body = result.getOrNull()!!
        assertEquals(1, body.total)
        assertEquals("q1", body.queries[0].id)
    }

    @Test
    fun `listQueries passes limit and offset`() = runTest {
        val expected = AnalyticsQueryListResponse(queries = emptyList(), total = 0)
        coEvery { api.listAnalyticsQueries(federationId, any(), any()) } returns
            Response.success(expected)

        analyticsApi.listQueries(limit = 25, offset = 10)

        coVerify { api.listAnalyticsQueries(federationId, 25, 10) }
    }

    // =========================================================================
    // Get Query
    // =========================================================================

    @Test
    fun `getQuery returns result on success`() = runTest {
        val expected = AnalyticsQuery(
            id = "q1",
            federationId = federationId,
            queryType = "t_test",
            variable = "loss",
            groupBy = "device_group",
            status = "completed",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:01Z",
        )
        coEvery { api.getAnalyticsQuery(federationId, "q1") } returns Response.success(expected)

        val result = analyticsApi.getQuery("q1")

        assertTrue(result.isSuccess)
        assertEquals("q1", result.getOrNull()!!.id)
        assertEquals("t_test", result.getOrNull()!!.queryType)
    }

    @Test
    fun `getQuery returns failure on HTTP error`() = runTest {
        coEvery { api.getAnalyticsQuery(federationId, "q1") } returns
            Response.error(404, okhttp3.ResponseBody.Companion.create(null, ""))

        val result = analyticsApi.getQuery("q1")

        assertTrue(result.isFailure)
    }

    // =========================================================================
    // Error Handling
    // =========================================================================

    @Test
    fun `descriptive returns failure on exception`() = runTest {
        coEvery { api.runDescriptive(federationId, any()) } throws RuntimeException("network error")

        val result = analyticsApi.descriptive(variable = "accuracy")

        assertTrue(result.isFailure)
    }

    @Test
    fun `tTest returns failure on exception`() = runTest {
        coEvery { api.runTTest(federationId, any()) } throws RuntimeException("timeout")

        val result = analyticsApi.tTest(variable = "v", groupA = "a", groupB = "b")

        assertTrue(result.isFailure)
    }
}
