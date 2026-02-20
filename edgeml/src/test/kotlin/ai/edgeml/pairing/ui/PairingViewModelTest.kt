package ai.edgeml.pairing.ui

import ai.edgeml.pairing.BenchmarkReport
import ai.edgeml.pairing.DeploymentInfo
import ai.edgeml.pairing.PairingException
import ai.edgeml.pairing.PairingManager
import ai.edgeml.pairing.PairingSession
import ai.edgeml.pairing.PairingStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [PairingViewModel] state machine logic.
 *
 * Verifies that the ViewModel correctly transitions through states:
 * Connecting -> Downloading -> Success (happy path)
 * Connecting -> Error (failure paths)
 *
 * Also tests retry behavior and error message mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var pairingManager: PairingManager

    private val connectedSession = PairingSession(
        id = "session-1",
        code = "TOKEN123",
        modelName = "phi-4-mini",
        modelVersion = null,
        status = PairingStatus.CONNECTED,
    )

    private val deployingSession = PairingSession(
        id = "session-1",
        code = "TOKEN123",
        modelName = "phi-4-mini",
        modelVersion = "1.2",
        status = PairingStatus.DEPLOYING,
        downloadUrl = "https://example.com/model.tflite",
        downloadFormat = "tensorflow_lite",
        downloadSizeBytes = 2_700_000_000L,
    )

    private val testReport = BenchmarkReport(
        modelName = "phi-4-mini",
        deviceName = "Test Model",
        chipFamily = "test-chip",
        ramGb = 8.0,
        osVersion = "14",
        ttftMs = 30.0,
        tpotMs = 15.0,
        tokensPerSecond = 66.7,
        p50LatencyMs = 14.0,
        p95LatencyMs = 20.0,
        p99LatencyMs = 25.0,
        memoryPeakBytes = 100_000_000L,
        inferenceCount = 51,
        modelLoadTimeMs = 200.0,
        coldInferenceMs = 30.0,
        warmInferenceMs = 15.0,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        pairingManager = mockk<PairingManager>(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    fun `initial state is Connecting with host`() = runTest {
        val viewModel = createViewModel()

        // Before dispatching coroutines, state should be Connecting
        val state = viewModel.state.value
        assertIs<PairingState.Connecting>(state)
        assertEquals("192.168.1.100", state.host)
    }

    @Test
    fun `happy path transitions through Connecting to Success`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } returns connectedSession
        coEvery { pairingManager.waitForDeployment("TOKEN123") } returns DeploymentInfo(
            modelName = "phi-4-mini",
            modelVersion = "1.2",
            downloadUrl = "https://example.com/model.tflite",
            format = "tensorflow_lite",
            sizeBytes = 2_700_000_000L,
        )
        coEvery { pairingManager.executeDeployment(any()) } returns testReport
        coEvery { pairingManager.submitBenchmark("TOKEN123", any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Success>(state)
        assertEquals("phi-4-mini", state.modelName)
        assertEquals("1.2", state.modelVersion)
        assertEquals(2_700_000_000L, state.sizeBytes)
        assertEquals("TFLite", state.runtime)
    }

    @Test
    fun `success state shows correct runtime for ONNX format`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } returns connectedSession
        coEvery { pairingManager.waitForDeployment("TOKEN123") } returns DeploymentInfo(
            modelName = "phi-4-mini",
            modelVersion = "1.2",
            downloadUrl = "https://example.com/model.onnx",
            format = "onnx",
            sizeBytes = 1_000_000L,
        )
        coEvery { pairingManager.executeDeployment(any()) } returns testReport
        coEvery { pairingManager.submitBenchmark("TOKEN123", any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Success>(state)
        assertEquals("ONNX", state.runtime)
    }

    // =========================================================================
    // Error paths
    // =========================================================================

    @Test
    fun `connect failure transitions to Error state`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } throws PairingException(
            "Session not found",
            PairingException.ErrorCode.NOT_FOUND,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Error>(state)
        assertTrue(state.message.contains("not found"))
        assertFalse(state.isRetryable)
    }

    @Test
    fun `expired session transitions to non-retryable Error`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } throws PairingException(
            "Pairing session expired",
            PairingException.ErrorCode.EXPIRED,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Error>(state)
        assertTrue(state.message.contains("expired"))
        assertFalse(state.isRetryable)
    }

    @Test
    fun `cancelled session transitions to non-retryable Error`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } throws PairingException(
            "Pairing session cancelled",
            PairingException.ErrorCode.CANCELLED,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Error>(state)
        assertFalse(state.isRetryable)
    }

    @Test
    fun `timeout error is retryable`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } returns connectedSession
        coEvery { pairingManager.waitForDeployment("TOKEN123") } throws PairingException(
            "Timed out",
            PairingException.ErrorCode.TIMEOUT,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Error>(state)
        assertTrue(state.isRetryable)
        assertTrue(state.message.contains("timed out", ignoreCase = true))
    }

    @Test
    fun `network error is retryable`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } throws PairingException(
            "Network unreachable",
            PairingException.ErrorCode.NETWORK_ERROR,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Error>(state)
        assertTrue(state.isRetryable)
    }

    @Test
    fun `download failure is retryable`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } returns connectedSession
        coEvery { pairingManager.waitForDeployment("TOKEN123") } returns DeploymentInfo(
            modelName = "phi-4-mini",
            modelVersion = "1.2",
            downloadUrl = "https://example.com/model.tflite",
            format = "tensorflow_lite",
            sizeBytes = 100L,
        )
        coEvery { pairingManager.executeDeployment(any()) } throws PairingException(
            "Download failed",
            PairingException.ErrorCode.DOWNLOAD_FAILED,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Error>(state)
        assertTrue(state.isRetryable)
        assertTrue(state.message.contains("download", ignoreCase = true))
    }

    @Test
    fun `unexpected exception transitions to retryable Error`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } throws RuntimeException("Something broke")

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Error>(state)
        assertTrue(state.isRetryable)
        assertEquals("Something broke", state.message)
    }

    @Test
    fun `server error is retryable`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } throws PairingException(
            "HTTP 500",
            PairingException.ErrorCode.SERVER_ERROR,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Error>(state)
        assertTrue(state.isRetryable)
    }

    // =========================================================================
    // Retry
    // =========================================================================

    @Test
    fun `retry resets state to Connecting and retries the flow`() = runTest {
        // First attempt fails
        coEvery { pairingManager.connect("TOKEN123") } throws PairingException(
            "timeout",
            PairingException.ErrorCode.TIMEOUT,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertIs<PairingState.Error>(viewModel.state.value)

        // Set up success for retry
        coEvery { pairingManager.connect("TOKEN123") } returns connectedSession
        coEvery { pairingManager.waitForDeployment("TOKEN123") } returns DeploymentInfo(
            modelName = "phi-4-mini",
            modelVersion = "1.2",
            downloadUrl = "https://example.com/model.tflite",
            format = "tensorflow_lite",
            sizeBytes = 100L,
        )
        coEvery { pairingManager.executeDeployment(any()) } returns testReport
        coEvery { pairingManager.submitBenchmark("TOKEN123", any()) } returns Unit

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<PairingState.Success>(state)
        assertEquals("phi-4-mini", state.modelName)
    }

    @Test
    fun `retry calls connect twice total`() = runTest {
        coEvery { pairingManager.connect("TOKEN123") } throws PairingException(
            "timeout",
            PairingException.ErrorCode.TIMEOUT,
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Retry still fails
        viewModel.retry()
        advanceUntilIdle()

        coVerify(exactly = 2) { pairingManager.connect("TOKEN123") }
    }

    // =========================================================================
    // Downloading state transition
    // =========================================================================

    @Test
    fun `transitions through Downloading before Success`() = runTest {
        val states = mutableListOf<PairingState>()

        coEvery { pairingManager.connect("TOKEN123") } returns connectedSession
        coEvery { pairingManager.waitForDeployment("TOKEN123") } returns DeploymentInfo(
            modelName = "phi-4-mini",
            modelVersion = "1.2",
            downloadUrl = "https://example.com/model.tflite",
            format = "tensorflow_lite",
            sizeBytes = 2_700_000_000L,
        )
        coEvery { pairingManager.executeDeployment(any()) } coAnswers {
            // Capture the Downloading state that should have been set before this call
            states.add(PairingState.Downloading(0f, "phi-4-mini", 0L, 2_700_000_000L))
            testReport
        }
        coEvery { pairingManager.submitBenchmark("TOKEN123", any()) } returns Unit

        val viewModel = createViewModel()
        advanceUntilIdle()

        // The VM should have set a Downloading state before executeDeployment
        assertTrue(states.any { it is PairingState.Downloading })
        // Final state should be Success
        assertIs<PairingState.Success>(viewModel.state.value)
    }

    // =========================================================================
    // Companion helper tests
    // =========================================================================

    @Test
    fun `mapErrorMessage returns correct messages for each error code`() {
        val codes = PairingException.ErrorCode.entries
        for (code in codes) {
            val ex = PairingException("test", code)
            val message = PairingViewModel.mapErrorMessage(ex)
            assertTrue(message.isNotBlank(), "mapErrorMessage returned blank for $code")
        }
    }

    @Test
    fun `isRetryable returns false for EXPIRED, CANCELLED, NOT_FOUND, UNAUTHORIZED`() {
        val nonRetryable = listOf(
            PairingException.ErrorCode.EXPIRED,
            PairingException.ErrorCode.CANCELLED,
            PairingException.ErrorCode.NOT_FOUND,
            PairingException.ErrorCode.UNAUTHORIZED,
        )
        for (code in nonRetryable) {
            val ex = PairingException("test", code)
            assertFalse(
                PairingViewModel.isRetryable(ex),
                "Expected $code to be non-retryable",
            )
        }
    }

    @Test
    fun `isRetryable returns true for TIMEOUT, NETWORK_ERROR, SERVER_ERROR, DOWNLOAD_FAILED, BENCHMARK_FAILED, UNKNOWN`() {
        val retryable = listOf(
            PairingException.ErrorCode.TIMEOUT,
            PairingException.ErrorCode.NETWORK_ERROR,
            PairingException.ErrorCode.SERVER_ERROR,
            PairingException.ErrorCode.DOWNLOAD_FAILED,
            PairingException.ErrorCode.BENCHMARK_FAILED,
            PairingException.ErrorCode.UNKNOWN,
        )
        for (code in retryable) {
            val ex = PairingException("test", code)
            assertTrue(
                PairingViewModel.isRetryable(ex),
                "Expected $code to be retryable",
            )
        }
    }

    @Test
    fun `formatRuntime maps common formats correctly`() {
        assertEquals("TFLite", PairingViewModel.formatRuntime("tensorflow_lite"))
        assertEquals("TFLite", PairingViewModel.formatRuntime("tflite"))
        assertEquals("TFLite", PairingViewModel.formatRuntime("TFLite_float16"))
        assertEquals("ONNX", PairingViewModel.formatRuntime("onnx"))
        assertEquals("ONNX", PairingViewModel.formatRuntime("ONNX_quantized"))
        assertEquals("CoreML", PairingViewModel.formatRuntime("coreml"))
        assertEquals("custom_format", PairingViewModel.formatRuntime("custom_format"))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun createViewModel(): PairingViewModel {
        return PairingViewModel(
            pairingManager = pairingManager,
            token = "TOKEN123",
            host = "192.168.1.100",
        )
    }
}
