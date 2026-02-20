package ai.edgeml.tryitout

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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [TryItOutViewModel] state machine and inference execution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TryItOutViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val textModelInfo = ModelInfo(
        modelName = "phi-4-mini",
        modelVersion = "1.2",
        sizeBytes = 2_700_000_000L,
        runtime = "TFLite",
        modality = "text",
    )

    private val visionModelInfo = ModelInfo(
        modelName = "yolo-v8",
        modelVersion = "2.0",
        sizeBytes = 150_000_000L,
        runtime = "TFLite",
        modality = "vision",
    )

    private val classificationModelInfo = ModelInfo(
        modelName = "mobilenet-v3",
        modelVersion = "1.0",
        sizeBytes = 5_000_000L,
        runtime = "TFLite",
        modality = "classification",
    )

    private val audioModelInfo = ModelInfo(
        modelName = "whisper-tiny",
        modelVersion = "1.0",
        sizeBytes = 75_000_000L,
        runtime = "TFLite",
        modality = "audio",
    )

    private val nullModalityModelInfo = ModelInfo(
        modelName = "generic-model",
        modelVersion = "1.0",
        sizeBytes = 10_000_000L,
        runtime = "TFLite",
        modality = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // Initial state
    // =========================================================================

    @Test
    fun `initial state is Idle`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val viewModel = TryItOutViewModel(textModelInfo, runner)

        assertIs<TryItOutState.Idle>(viewModel.state.value)
    }

    @Test
    fun `modelInfo is accessible`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val viewModel = TryItOutViewModel(textModelInfo, runner)

        assertEquals("phi-4-mini", viewModel.modelInfo.modelName)
        assertEquals("1.2", viewModel.modelInfo.modelVersion)
        assertEquals(2_700_000_000L, viewModel.modelInfo.sizeBytes)
        assertEquals("TFLite", viewModel.modelInfo.runtime)
        assertEquals("text", viewModel.modelInfo.modality)
    }

    // =========================================================================
    // Effective modality routing
    // =========================================================================

    @Test
    fun `effectiveModality returns text for text modality`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val viewModel = TryItOutViewModel(textModelInfo, runner)

        assertEquals("text", viewModel.effectiveModality)
    }

    @Test
    fun `effectiveModality returns vision for vision modality`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val viewModel = TryItOutViewModel(visionModelInfo, runner)

        assertEquals("vision", viewModel.effectiveModality)
    }

    @Test
    fun `effectiveModality returns classification for classification modality`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val viewModel = TryItOutViewModel(classificationModelInfo, runner)

        assertEquals("classification", viewModel.effectiveModality)
    }

    @Test
    fun `effectiveModality returns audio for audio modality`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val viewModel = TryItOutViewModel(audioModelInfo, runner)

        assertEquals("audio", viewModel.effectiveModality)
    }

    @Test
    fun `effectiveModality defaults to text when modality is null`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val viewModel = TryItOutViewModel(nullModalityModelInfo, runner)

        assertEquals("text", viewModel.effectiveModality)
    }

    @Test
    fun `effectiveModality normalizes uppercase modality`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val info = textModelInfo.copy(modality = "VISION")
        val viewModel = TryItOutViewModel(info, runner)

        assertEquals("vision", viewModel.effectiveModality)
    }

    @Test
    fun `effectiveModality trims whitespace`() {
        val runner = mockk<InferenceRunner>(relaxed = true)
        val info = textModelInfo.copy(modality = "  audio  ")
        val viewModel = TryItOutViewModel(info, runner)

        assertEquals("audio", viewModel.effectiveModality)
    }

    // =========================================================================
    // Inference — happy path
    // =========================================================================

    @Test
    fun `runInference transitions to Loading then Result on success`() = runTest {
        val expectedOutput = floatArrayOf(0.1f, 0.9f, 0.3f)
        val runner = mockk<InferenceRunner>()
        coEvery { runner.runInference(any()) } returns expectedOutput

        val viewModel = TryItOutViewModel(textModelInfo, runner)

        viewModel.runInference(floatArrayOf(1f, 2f, 3f))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<TryItOutState.Result>(state)
        assertContentEquals(expectedOutput, state.output)
        // Latency should be non-negative (may be 0 in test due to mocked nanoTime)
        assert(state.latencyMs >= 0) { "Latency should be non-negative, was ${state.latencyMs}" }
    }

    @Test
    fun `runInference calls inferenceRunner with correct input`() = runTest {
        val input = floatArrayOf(42f, 99f)
        val runner = mockk<InferenceRunner>()
        coEvery { runner.runInference(input) } returns floatArrayOf(0.5f)

        val viewModel = TryItOutViewModel(textModelInfo, runner)

        viewModel.runInference(input)
        advanceUntilIdle()

        coVerify(exactly = 1) { runner.runInference(input) }
    }

    // =========================================================================
    // Inference — error path
    // =========================================================================

    @Test
    fun `runInference transitions to Error on exception`() = runTest {
        val runner = mockk<InferenceRunner>()
        coEvery { runner.runInference(any()) } throws RuntimeException("TFLite crashed")

        val viewModel = TryItOutViewModel(textModelInfo, runner)

        viewModel.runInference(floatArrayOf(1f))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<TryItOutState.Error>(state)
        assertEquals("TFLite crashed", state.message)
    }

    @Test
    fun `runInference error message defaults when exception message is null`() = runTest {
        val runner = mockk<InferenceRunner>()
        coEvery { runner.runInference(any()) } throws RuntimeException()

        val viewModel = TryItOutViewModel(textModelInfo, runner)

        viewModel.runInference(floatArrayOf(1f))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<TryItOutState.Error>(state)
        assertEquals("Inference failed", state.message)
    }

    // =========================================================================
    // Reset
    // =========================================================================

    @Test
    fun `reset returns to Idle from Result`() = runTest {
        val runner = mockk<InferenceRunner>()
        coEvery { runner.runInference(any()) } returns floatArrayOf(0.5f)

        val viewModel = TryItOutViewModel(textModelInfo, runner)

        viewModel.runInference(floatArrayOf(1f))
        advanceUntilIdle()
        assertIs<TryItOutState.Result>(viewModel.state.value)

        viewModel.reset()
        assertIs<TryItOutState.Idle>(viewModel.state.value)
    }

    @Test
    fun `reset returns to Idle from Error`() = runTest {
        val runner = mockk<InferenceRunner>()
        coEvery { runner.runInference(any()) } throws RuntimeException("fail")

        val viewModel = TryItOutViewModel(textModelInfo, runner)

        viewModel.runInference(floatArrayOf(1f))
        advanceUntilIdle()
        assertIs<TryItOutState.Error>(viewModel.state.value)

        viewModel.reset()
        assertIs<TryItOutState.Idle>(viewModel.state.value)
    }

    // =========================================================================
    // Multiple inferences
    // =========================================================================

    @Test
    fun `can run inference multiple times sequentially`() = runTest {
        var callCount = 0
        val runner = InferenceRunner { input ->
            callCount++
            FloatArray(input.size) { it.toFloat() * callCount }
        }

        val viewModel = TryItOutViewModel(textModelInfo, runner)

        viewModel.runInference(floatArrayOf(1f))
        advanceUntilIdle()

        val firstResult = viewModel.state.value
        assertIs<TryItOutState.Result>(firstResult)

        viewModel.runInference(floatArrayOf(2f))
        advanceUntilIdle()

        val secondResult = viewModel.state.value
        assertIs<TryItOutState.Result>(secondResult)
        assertEquals(2, callCount)
    }
}
