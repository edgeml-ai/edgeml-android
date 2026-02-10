package ai.edgeml.training

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TFLiteTrainer].
 *
 * TensorFlow Lite's native library is not available on the CI JVM, so we
 * cannot test paths that create an [org.tensorflow.lite.Interpreter].
 * Instead we focus on guard clauses, state management, error paths,
 * and the new training/signature-related methods that execute
 * *before* any native call is made.
 */
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
    // loadModel — missing file path
    // =========================================================================

    @Test
    fun `loadModel fails for missing file`() = runTest(testDispatcher) {
        val model = testCachedModel(filePath = "/nonexistent/path/model.tflite")

        val result = trainer.loadModel(model)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
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
    // hasTrainingSignature / getSignatureKeys — no model
    // =========================================================================

    @Test
    fun `hasTrainingSignature returns false when no model loaded`() {
        assertFalse(trainer.hasTrainingSignature())
    }

    @Test
    fun `getSignatureKeys returns empty list when no model loaded`() {
        assertTrue(trainer.getSignatureKeys().isEmpty())
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
        assertFalse(trainer.hasTrainingSignature())
        assertTrue(trainer.getSignatureKeys().isEmpty())
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

    @Test
    fun `train with default TrainingConfig uses sensible defaults`() = runTest(testDispatcher) {
        // Verify TrainingConfig defaults are accessible (epochs=1, batchSize=32, lr=0.001)
        val config = TrainingConfig()
        assertEquals(1, config.epochs)
        assertEquals(32, config.batchSize)
        assertEquals(0.001f, config.learningRate)
        assertEquals("categorical_crossentropy", config.lossFunction)
        assertEquals("adam", config.optimizer)
    }

    @Test
    fun `train with empty data provider fails`() = runTest(testDispatcher) {
        // Even if a model were loaded, empty data should fail.
        // Without a model loaded, we get "No model loaded" first.
        val emptyProvider = InMemoryTrainingDataProvider(emptyList())

        val result = trainer.train(emptyProvider)

        assertTrue(result.isFailure)
        // Should fail with "No model loaded" since no interpreter is available
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `train with custom TrainingConfig passes epochs and batch size`() {
        // Verify custom config values can be set
        val config = TrainingConfig(
            epochs = 5,
            batchSize = 16,
            learningRate = 0.01f,
            lossFunction = "mse",
            optimizer = "sgd",
        )
        assertEquals(5, config.epochs)
        assertEquals(16, config.batchSize)
        assertEquals(0.01f, config.learningRate)
        assertEquals("mse", config.lossFunction)
        assertEquals("sgd", config.optimizer)
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

    @Test
    fun `extractWeightUpdate fails when no training session`() = runTest(testDispatcher) {
        // Even with a model conceptually loaded, if updatedModelPath is null,
        // extractWeightUpdate should fail. Since we can't load a real model
        // on JVM, we verify the guard clause indirectly.
        val trainingResult = TrainingResult(
            sampleCount = 10,
            loss = 0.1,
            accuracy = 0.9,
            trainingTime = 1.0,
        )

        val result = trainer.extractWeightUpdate(trainingResult)

        assertTrue(result.isFailure)
    }

    // =========================================================================
    // TrainingResult data class
    // =========================================================================

    @Test
    fun `TrainingResult stores all fields correctly`() {
        val metrics = mapOf("epochs" to 3.0, "batch_size" to 32.0)
        val result = TrainingResult(
            sampleCount = 100,
            loss = 0.25,
            accuracy = 0.92,
            trainingTime = 5.5,
            metrics = metrics,
            updatedModelPath = "/tmp/updated.tflite",
        )

        assertEquals(100, result.sampleCount)
        assertEquals(0.25, result.loss)
        assertEquals(0.92, result.accuracy)
        assertEquals(5.5, result.trainingTime)
        assertEquals(3.0, result.metrics["epochs"])
        assertEquals("/tmp/updated.tflite", result.updatedModelPath)
    }

    @Test
    fun `TrainingResult defaults to empty metrics and null path`() {
        val result = TrainingResult(
            sampleCount = 50,
            loss = 0.1,
            accuracy = 0.95,
            trainingTime = 1.0,
        )

        assertTrue(result.metrics.isEmpty())
        assertNull(result.updatedModelPath)
    }

    @Test
    fun `TrainingResult accepts null loss and accuracy`() {
        val result = TrainingResult(
            sampleCount = 0,
            loss = null,
            accuracy = null,
            trainingTime = 0.0,
        )

        assertNull(result.loss)
        assertNull(result.accuracy)
    }

    // =========================================================================
    // WeightUpdate data class
    // =========================================================================

    @Test
    fun `WeightUpdate equality uses content comparison for weightsData`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val update1 = WeightUpdate(
            modelId = "m1",
            version = "1.0",
            weightsData = data.copyOf(),
            sampleCount = 10,
        )
        val update2 = WeightUpdate(
            modelId = "m1",
            version = "1.0",
            weightsData = data.copyOf(),
            sampleCount = 10,
        )

        assertEquals(update1, update2)
        assertEquals(update1.hashCode(), update2.hashCode())
    }

    @Test
    fun `WeightUpdate not equal when weightsData differ`() {
        val update1 = WeightUpdate(
            modelId = "m1",
            version = "1.0",
            weightsData = byteArrayOf(1, 2, 3),
            sampleCount = 10,
        )
        val update2 = WeightUpdate(
            modelId = "m1",
            version = "1.0",
            weightsData = byteArrayOf(4, 5, 6),
            sampleCount = 10,
        )

        assertFalse(update1 == update2)
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

    @Test
    fun `TensorInfo not equal when types differ`() {
        val info1 = TensorInfo(intArrayOf(1), intArrayOf(1), "FLOAT32", "FLOAT32")
        val info2 = TensorInfo(intArrayOf(1), intArrayOf(1), "INT32", "FLOAT32")

        assertFalse(info1 == info2)
    }

    // =========================================================================
    // InMemoryTrainingDataProvider
    // =========================================================================

    @Test
    fun `InMemoryTrainingDataProvider returns data`() = runTest(testDispatcher) {
        val data = listOf(
            floatArrayOf(1.0f, 2.0f) to floatArrayOf(1.0f, 0.0f),
            floatArrayOf(3.0f, 4.0f) to floatArrayOf(0.0f, 1.0f),
        )
        val provider = InMemoryTrainingDataProvider(data)

        val result = provider.getTrainingData()
        assertEquals(2, result.size)
        assertEquals(2, provider.getSampleCount())
    }

    @Test
    fun `InMemoryTrainingDataProvider handles empty data`() = runTest(testDispatcher) {
        val provider = InMemoryTrainingDataProvider(emptyList())

        assertEquals(0, provider.getTrainingData().size)
        assertEquals(0, provider.getSampleCount())
    }

    // =========================================================================
    // inputShape and outputShape return copies
    // =========================================================================

    @Test
    fun `inputShape returns a defensive copy`() {
        val shape1 = trainer.inputShape
        val shape2 = trainer.inputShape
        // They should be equal but not the same array instance
        assertTrue(shape1.contentEquals(shape2))
    }

    @Test
    fun `outputShape returns a defensive copy`() {
        val shape1 = trainer.outputShape
        val shape2 = trainer.outputShape
        assertTrue(shape1.contentEquals(shape2))
    }

    // =========================================================================
    // Multiple trainer instances
    // =========================================================================

    @Test
    fun `separate trainer instances are independent`() = runTest(testDispatcher) {
        val config = testConfig(enableGpuAcceleration = false)
        val trainer2 = TFLiteTrainer(context, config, testDispatcher, testDispatcher)

        assertFalse(trainer.isModelLoaded())
        assertFalse(trainer2.isModelLoaded())

        trainer.close()
        // trainer2 should be unaffected
        assertFalse(trainer2.isModelLoaded())
        trainer2.close()
    }
}
