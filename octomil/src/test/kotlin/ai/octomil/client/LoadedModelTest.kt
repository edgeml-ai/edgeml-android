package ai.octomil.client

import ai.octomil.models.InferenceOutput
import ai.octomil.testCachedModel
import ai.octomil.training.TFLiteTrainer
import ai.octomil.training.TensorInfo
import ai.octomil.training.WarmupResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadedModelTest {

    private val cachedModel = testCachedModel()
    private val trainer = mockk<TFLiteTrainer>(relaxed = true)
    private val loadedModel = LoadedModel(cachedModel = cachedModel, trainer = trainer)

    // =========================================================================
    // Metadata delegation
    // =========================================================================

    @Test
    fun `modelId delegates to cachedModel`() {
        assertEquals("test-model", loadedModel.modelId)
    }

    @Test
    fun `version delegates to cachedModel`() {
        assertEquals("1.0.0", loadedModel.version)
    }

    @Test
    fun `format delegates to cachedModel`() {
        assertEquals("tensorflow_lite", loadedModel.format)
    }

    @Test
    fun `sizeBytes delegates to cachedModel`() {
        assertEquals(1024L, loadedModel.sizeBytes)
    }

    // =========================================================================
    // Inference
    // =========================================================================

    @Test
    fun `predict with float array delegates to trainer`() = runTest {
        val expectedOutput = InferenceOutput(
            data = floatArrayOf(0.1f, 0.9f),
            shape = intArrayOf(1, 2),
            inferenceTimeMs = 42L,
        )
        coEvery { trainer.runInference(any<FloatArray>()) } returns Result.success(expectedOutput)

        val result = loadedModel.predict(floatArrayOf(1.0f, 2.0f))

        assertTrue(result.isSuccess)
        assertEquals(42L, result.getOrNull()?.inferenceTimeMs)
        coVerify { trainer.runInference(any<FloatArray>()) }
    }

    @Test
    fun `predict propagates failure from trainer`() = runTest {
        coEvery { trainer.runInference(any<FloatArray>()) } returns
            Result.failure(RuntimeException("interpreter closed"))

        val result = loadedModel.predict(floatArrayOf(1.0f))

        assertTrue(result.isFailure)
    }

    // =========================================================================
    // Warmup
    // =========================================================================

    @Test
    fun `warmup delegates to trainer`() = runTest {
        val expected = WarmupResult(
            coldInferenceMs = 20.0,
            warmInferenceMs = 5.0,
            cpuInferenceMs = null,
            usingGpu = false,
            activeDelegate = "cpu",
        )
        coEvery { trainer.warmup() } returns expected

        val result = loadedModel.warmup()

        assertNotNull(result)
        assertEquals(20.0, result.coldInferenceMs)
        assertEquals("cpu", result.activeDelegate)
    }

    @Test
    fun `warmup returns null when trainer returns null`() = runTest {
        coEvery { trainer.warmup() } returns null

        val result = loadedModel.warmup()
        assertNull(result)
    }

    // =========================================================================
    // Tensor info
    // =========================================================================

    @Test
    fun `getTensorInfo delegates to trainer`() {
        val info = TensorInfo(
            inputShape = intArrayOf(1, 28, 28, 1),
            outputShape = intArrayOf(1, 10),
            inputType = "FLOAT32",
            outputType = "FLOAT32",
        )
        every { trainer.getTensorInfo() } returns info

        val result = loadedModel.getTensorInfo()

        assertNotNull(result)
        assertEquals("FLOAT32", result.inputType)
    }

    @Test
    fun `getTensorInfo returns null when no model loaded`() {
        every { trainer.getTensorInfo() } returns null

        assertNull(loadedModel.getTensorInfo())
    }

    // =========================================================================
    // Signature info
    // =========================================================================

    @Test
    fun `hasTrainingSignature delegates to trainer`() {
        every { trainer.hasTrainingSignature() } returns true
        assertTrue(loadedModel.hasTrainingSignature)
    }

    @Test
    fun `signatureKeys delegates to trainer`() {
        every { trainer.getSignatureKeys() } returns listOf("train", "infer", "save")
        assertEquals(listOf("train", "infer", "save"), loadedModel.signatureKeys)
    }
}
