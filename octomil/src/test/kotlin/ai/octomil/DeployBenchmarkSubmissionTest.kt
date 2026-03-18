package ai.octomil

import ai.octomil.api.OctomilApi
import ai.octomil.pairing.BenchmarkReport
import ai.octomil.pairing.DeviceCapabilities
import ai.octomil.pairing.DeviceConnectRequest
import ai.octomil.training.WarmupResult
import android.content.Context
import android.os.Build
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for benchmark submission from the deploy layer.
 *
 * Verifies that [Octomil.submitBenchmarkIfNeeded] (accessed via deploy())
 * correctly converts [WarmupResult] to [BenchmarkReport] and submits it
 * to the server. Also verifies non-fatal error handling.
 *
 * Note: These tests use reflection to invoke the private submitBenchmarkIfNeeded
 * method directly, since Octomil.deploy() depends on TFLite being on the classpath.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeployBenchmarkSubmissionTest {

    private lateinit var api: OctomilApi
    private lateinit var context: Context

    private val testDeviceRequest = DeviceConnectRequest(
        deviceId = "test-device-id",
        platform = "android",
        deviceName = "Test Device",
        chipFamily = "test-chip",
        ramGb = 8.0,
        osVersion = "14",
        npuAvailable = false,
        gpuAvailable = true,
    )

    @Before
    fun setUp() {
        api = mockk<OctomilApi>(relaxed = true)
        context = mockk<Context>(relaxed = true)

        every { context.applicationContext } returns context

        mockkObject(DeviceCapabilities)
        every { DeviceCapabilities.collect(any()) } returns testDeviceRequest
    }

    @After
    fun tearDown() {
        unmockkObject(DeviceCapabilities)
    }

    // =========================================================================
    // submitBenchmarkIfNeeded via reflection
    // =========================================================================

    @Test
    fun `submitBenchmarkIfNeeded submits report when pairingCode and api provided`() = runTest {
        val warmup = WarmupResult(
            coldInferenceMs = 25.0,
            warmInferenceMs = 12.0,
            cpuInferenceMs = null,
            usingGpu = true,
            activeDelegate = "gpu",
            disabledDelegates = listOf("nnapi"),
        )

        val reportSlot = slot<BenchmarkReport>()
        coEvery { api.submitBenchmark("PAIR123", capture(reportSlot)) } returns Response.success(Unit)

        invokeSubmitBenchmarkIfNeeded(
            context = context,
            modelName = "test-model",
            warmupResult = warmup,
            pairingCode = "PAIR123",
            api = api,
        )

        coVerify(exactly = 1) { api.submitBenchmark("PAIR123", any()) }

        val report = reportSlot.captured
        assertEquals("test-model", report.modelName)
        assertEquals("Test Device", report.deviceName)
        assertEquals("test-chip", report.chipFamily)
        assertEquals(8.0, report.ramGb)
        assertEquals(25.0, report.coldInferenceMs)
        assertEquals(12.0, report.warmInferenceMs)
        assertEquals(25.0, report.ttftMs)
        assertEquals(12.0, report.tpotMs)
        assertTrue(report.tokensPerSecond > 0)
        assertEquals("gpu", report.activeDelegate)
        assertEquals(listOf("nnapi"), report.disabledDelegates)
        assertEquals(2, report.inferenceCount)
    }

    @Test
    fun `submitBenchmarkIfNeeded skips when pairingCode is null`() = runTest {
        val warmup = WarmupResult(
            coldInferenceMs = 10.0,
            warmInferenceMs = 5.0,
            cpuInferenceMs = null,
            usingGpu = false,
            activeDelegate = "cpu",
        )

        invokeSubmitBenchmarkIfNeeded(
            context = context,
            modelName = "test-model",
            warmupResult = warmup,
            pairingCode = null,
            api = api,
        )

        coVerify(exactly = 0) { api.submitBenchmark(any(), any()) }
    }

    @Test
    fun `submitBenchmarkIfNeeded skips when api is null`() = runTest {
        val warmup = WarmupResult(
            coldInferenceMs = 10.0,
            warmInferenceMs = 5.0,
            cpuInferenceMs = null,
            usingGpu = false,
            activeDelegate = "cpu",
        )

        invokeSubmitBenchmarkIfNeeded(
            context = context,
            modelName = "test-model",
            warmupResult = warmup,
            pairingCode = "CODE",
            api = null,
        )

        coVerify(exactly = 0) { api.submitBenchmark(any(), any()) }
    }

    @Test
    fun `submitBenchmarkIfNeeded skips when warmupResult is null`() = runTest {
        invokeSubmitBenchmarkIfNeeded(
            context = context,
            modelName = "test-model",
            warmupResult = null,
            pairingCode = "CODE",
            api = api,
        )

        coVerify(exactly = 0) { api.submitBenchmark(any(), any()) }
    }

    @Test
    fun `submitBenchmarkIfNeeded does not throw on server error`() = runTest {
        val warmup = WarmupResult(
            coldInferenceMs = 10.0,
            warmInferenceMs = 5.0,
            cpuInferenceMs = null,
            usingGpu = false,
            activeDelegate = "cpu",
        )

        coEvery { api.submitBenchmark("CODE", any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        // Should not throw
        invokeSubmitBenchmarkIfNeeded(
            context = context,
            modelName = "test-model",
            warmupResult = warmup,
            pairingCode = "CODE",
            api = api,
        )
    }

    @Test
    fun `submitBenchmarkIfNeeded does not throw on network exception`() = runTest {
        val warmup = WarmupResult(
            coldInferenceMs = 10.0,
            warmInferenceMs = 5.0,
            cpuInferenceMs = null,
            usingGpu = false,
            activeDelegate = "cpu",
        )

        coEvery { api.submitBenchmark("CODE", any()) } throws RuntimeException("Network unreachable")

        // Should not throw
        invokeSubmitBenchmarkIfNeeded(
            context = context,
            modelName = "test-model",
            warmupResult = warmup,
            pairingCode = "CODE",
            api = api,
        )
    }

    @Test
    fun `submitBenchmarkIfNeeded sets tokensPerSecond to 0 when warmInferenceMs is 0`() = runTest {
        val warmup = WarmupResult(
            coldInferenceMs = 10.0,
            warmInferenceMs = 0.0,
            cpuInferenceMs = null,
            usingGpu = false,
            activeDelegate = "cpu",
        )

        val reportSlot = slot<BenchmarkReport>()
        coEvery { api.submitBenchmark("CODE", capture(reportSlot)) } returns Response.success(Unit)

        invokeSubmitBenchmarkIfNeeded(
            context = context,
            modelName = "test-model",
            warmupResult = warmup,
            pairingCode = "CODE",
            api = api,
        )

        assertEquals(0.0, reportSlot.captured.tokensPerSecond)
    }

    @Test
    fun `submitBenchmarkIfNeeded sets disabledDelegates to null when empty`() = runTest {
        val warmup = WarmupResult(
            coldInferenceMs = 10.0,
            warmInferenceMs = 5.0,
            cpuInferenceMs = null,
            usingGpu = false,
            activeDelegate = "cpu",
            disabledDelegates = emptyList(),
        )

        val reportSlot = slot<BenchmarkReport>()
        coEvery { api.submitBenchmark("CODE", capture(reportSlot)) } returns Response.success(Unit)

        invokeSubmitBenchmarkIfNeeded(
            context = context,
            modelName = "test-model",
            warmupResult = warmup,
            pairingCode = "CODE",
            api = api,
        )

        assertNull(reportSlot.captured.disabledDelegates)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Invoke Octomil.submitBenchmarkIfNeeded via reflection since it's private.
     */
    private suspend fun invokeSubmitBenchmarkIfNeeded(
        context: Context,
        modelName: String,
        warmupResult: WarmupResult?,
        pairingCode: String?,
        api: OctomilApi?,
    ) {
        val method = Octomil::class.java.getDeclaredMethod(
            "submitBenchmarkIfNeeded",
            Context::class.java,
            String::class.java,
            WarmupResult::class.java,
            String::class.java,
            OctomilApi::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        method.isAccessible = true

        // Call the suspend function via reflection using a coroutine continuation
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            method.invoke(Octomil, context, modelName, warmupResult, pairingCode, api, cont)
        }
    }
}
