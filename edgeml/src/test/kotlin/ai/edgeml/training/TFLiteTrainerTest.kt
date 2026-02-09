package ai.edgeml.training

import ai.edgeml.models.CachedModel
import ai.edgeml.testCachedModel
import ai.edgeml.testConfig
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TFLiteTrainerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var tmpDir: File
    private lateinit var trainer: TFLiteTrainer

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "edgeml_trainer_test_${System.nanoTime()}")
        tmpDir.mkdirs()

        context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tmpDir

        val config = testConfig(enableGpuAcceleration = false)
        trainer = TFLiteTrainer(context, config, testDispatcher, testDispatcher)
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // =========================================================================
    // loadModel
    // =========================================================================

    @Test
    fun `loadModel fails for missing file`() = runTest(testDispatcher) {
        val model = testCachedModel(filePath = "/nonexistent/path/model.tflite")

        val result = trainer.loadModel(model)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
    }

    @Test
    fun `loadModel fails for invalid tflite file`() = runTest(testDispatcher) {
        val modelFile = File(tmpDir, "bad-model.tflite")
        modelFile.writeText("not a valid tflite model")

        val model = testCachedModel(filePath = modelFile.absolutePath)

        val result = trainer.loadModel(model)

        // TFLite will throw when trying to interpret invalid data
        assertTrue(result.isFailure)
    }

    // =========================================================================
    // isModelLoaded / currentModel
    // =========================================================================

    @Test
    fun `isModelLoaded returns false when no model loaded`() {
        assertFalse(trainer.isModelLoaded())
    }

    @Test
    fun `currentModel returns null when no model loaded`() {
        assertNull(trainer.currentModel)
    }

    // =========================================================================
    // runInference without model
    // =========================================================================

    @Test
    fun `runInference fails when no model loaded`() = runTest(testDispatcher) {
        val result = trainer.runInference(floatArrayOf(1.0f, 2.0f))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No model loaded") == true)
    }

    @Test
    fun `classify fails when no model loaded`() = runTest(testDispatcher) {
        val result = trainer.classify(floatArrayOf(1.0f, 2.0f))

        assertTrue(result.isFailure)
    }

    @Test
    fun `runBatchInference fails when no model loaded`() = runTest(testDispatcher) {
        val result = trainer.runBatchInference(listOf(floatArrayOf(1.0f)))

        assertTrue(result.isFailure)
    }

    // =========================================================================
    // getTensorInfo
    // =========================================================================

    @Test
    fun `getTensorInfo returns null when no model loaded`() {
        val info = trainer.getTensorInfo()
        assertNull(info)
    }

    // =========================================================================
    // isUsingGpu
    // =========================================================================

    @Test
    fun `isUsingGpu returns false by default`() {
        assertFalse(trainer.isUsingGpu())
    }

    // =========================================================================
    // Input/output shape defaults
    // =========================================================================

    @Test
    fun `inputShape is empty when no model loaded`() {
        assertEquals(0, trainer.inputShape.size)
    }

    @Test
    fun `outputShape is empty when no model loaded`() {
        assertEquals(0, trainer.outputShape.size)
    }

    @Test
    fun `getInputSize returns 1 when no model loaded`() {
        // fold of empty array with initial 1 returns 1
        assertEquals(1, trainer.getInputSize())
    }

    @Test
    fun `getOutputSize returns 1 when no model loaded`() {
        assertEquals(1, trainer.getOutputSize())
    }

    // =========================================================================
    // close
    // =========================================================================

    @Test
    fun `close resets all state`() = runTest(testDispatcher) {
        // Close on an unloaded trainer should not throw
        trainer.close()

        assertFalse(trainer.isModelLoaded())
        assertNull(trainer.currentModel)
        assertNull(trainer.getTensorInfo())
        assertFalse(trainer.isUsingGpu())
    }

    @Test
    fun `close can be called multiple times safely`() = runTest(testDispatcher) {
        trainer.close()
        trainer.close()
        // No exception means success
    }

    // =========================================================================
    // train without model
    // =========================================================================

    @Test
    fun `train fails when no model loaded`() = runTest(testDispatcher) {
        val dataProvider = InMemoryTrainingDataProvider(
            listOf(floatArrayOf(1.0f) to floatArrayOf(0.0f)),
        )

        val result = trainer.train(dataProvider)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No model loaded") == true)
    }

    // =========================================================================
    // extractWeightUpdate without model
    // =========================================================================

    @Test
    fun `extractWeightUpdate fails when no model loaded`() = runTest(testDispatcher) {
        val trainingResult = TrainingResult(
            sampleCount = 10,
            loss = 0.1,
            accuracy = 0.9,
            trainingTime = 1.0,
        )

        val result = trainer.extractWeightUpdate(trainingResult)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No model loaded") == true)
    }

    // =========================================================================
    // TensorInfo data class
    // =========================================================================

    @Test
    fun `TensorInfo equality compares shapes by content`() {
        val info1 = TensorInfo(intArrayOf(1, 28, 28), intArrayOf(1, 10), "FLOAT32", "FLOAT32")
        val info2 = TensorInfo(intArrayOf(1, 28, 28), intArrayOf(1, 10), "FLOAT32", "FLOAT32")

        assertEquals(info1, info2)
        assertEquals(info1.hashCode(), info2.hashCode())
    }

    @Test
    fun `TensorInfo not equal when shapes differ`() {
        val info1 = TensorInfo(intArrayOf(1, 28, 28), intArrayOf(1, 10), "FLOAT32", "FLOAT32")
        val info2 = TensorInfo(intArrayOf(1, 32, 32), intArrayOf(1, 10), "FLOAT32", "FLOAT32")

        assertFalse(info1 == info2)
    }
}
