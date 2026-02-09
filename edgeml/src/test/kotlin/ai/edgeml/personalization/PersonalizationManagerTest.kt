package ai.edgeml.personalization

import ai.edgeml.models.CachedModel
import ai.edgeml.testCachedModel
import ai.edgeml.testConfig
import ai.edgeml.training.TFLiteTrainer
import ai.edgeml.training.TrainingConfig
import ai.edgeml.training.TrainingDataProvider
import ai.edgeml.training.TrainingResult
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalizationManagerTest {
    private lateinit var context: Context
    private lateinit var trainer: TFLiteTrainer
    private lateinit var filesDir: File
    private val config = testConfig()

    @Before
    fun setUp() {
        filesDir = File(System.getProperty("java.io.tmpdir"), "edgeml_pers_test_${System.nanoTime()}")
        filesDir.mkdirs()

        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir

        trainer = mockk<TFLiteTrainer>(relaxed = true)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun createManager(
        bufferSizeThreshold: Int = 50,
        minSamplesForTraining: Int = 10,
        trainingMode: TrainingMode = TrainingMode.LOCAL_ONLY,
        uploadThreshold: Int = 10,
        onUploadNeeded: (suspend () -> Unit)? = null,
    ) = PersonalizationManager(
        context = context,
        config = config,
        trainer = trainer,
        bufferSizeThreshold = bufferSizeThreshold,
        minSamplesForTraining = minSamplesForTraining,
        trainingMode = trainingMode,
        uploadThreshold = uploadThreshold,
        onUploadNeeded = onUploadNeeded,
    )

    // =========================================================================
    // Model management
    // =========================================================================

    @Test
    fun `getCurrentModel returns null when no model set`() = runTest {
        val manager = createManager()
        assertNull(manager.getCurrentModel())
    }

    @Test
    fun `getCurrentModel returns base model when no personalized model`() = runTest {
        val manager = createManager()
        val baseModel = testCachedModel(modelId = "base")

        manager.setBaseModel(baseModel)

        val current = manager.getCurrentModel()
        assertNotNull(current)
        assertEquals("base", current.modelId)
    }

    @Test
    fun `getCurrentModel prefers personalized model over base`() = runTest {
        val manager = createManager()
        val baseModel = testCachedModel(modelId = "model-1", version = "1.0.0")

        // Create a fake personalized model file
        val personalizedDir = File(filesDir, "personalized_models")
        personalizedDir.mkdirs()
        File(personalizedDir, "model-1-personalized.tflite").writeText("fake")

        manager.setBaseModel(baseModel)

        val current = manager.getCurrentModel()
        assertNotNull(current)
        assertTrue(current.version.contains("personalized"))
    }

    @Test
    fun `resetPersonalization clears personalized model`() = runTest {
        val manager = createManager()
        val baseModel = testCachedModel(modelId = "model-1")

        // Create personalized file
        val personalizedDir = File(filesDir, "personalized_models")
        personalizedDir.mkdirs()
        File(personalizedDir, "model-1-personalized.tflite").writeText("fake")

        manager.setBaseModel(baseModel)
        assertTrue(manager.getCurrentModel()!!.version.contains("personalized"))

        manager.resetPersonalization()

        val current = manager.getCurrentModel()
        assertNotNull(current)
        assertFalse(current.version.contains("personalized"))
    }

    // =========================================================================
    // Buffer management
    // =========================================================================

    @Test
    fun `addTrainingSample increases buffer size`() = runTest {
        val manager = createManager()
        manager.setBaseModel(testCachedModel())

        manager.addTrainingSample(
            input = floatArrayOf(1.0f),
            target = floatArrayOf(0.0f),
        )

        val stats = manager.getStatistics()
        assertEquals(1, stats.bufferedSamples)
    }

    @Test
    fun `clearBuffer empties the buffer`() = runTest {
        val manager = createManager()
        manager.setBaseModel(testCachedModel())

        repeat(5) {
            manager.addTrainingSample(
                input = floatArrayOf(it.toFloat()),
                target = floatArrayOf(0.0f),
            )
        }

        assertEquals(5, manager.getStatistics().bufferedSamples)

        manager.clearBuffer()

        assertEquals(0, manager.getStatistics().bufferedSamples)
    }

    @Test
    fun `buffer enforces max size`() = runTest {
        // maxBufferSize = bufferSizeThreshold * 2 = 10 * 2 = 20
        val manager = createManager(bufferSizeThreshold = 10, minSamplesForTraining = 100)
        manager.setBaseModel(testCachedModel())

        repeat(25) {
            manager.addTrainingSample(
                input = floatArrayOf(it.toFloat()),
                target = floatArrayOf(0.0f),
            )
        }

        val stats = manager.getStatistics()
        assertTrue(stats.bufferedSamples <= 20)
    }

    // =========================================================================
    // Training triggers
    // =========================================================================

    @Test
    fun `trainIncrementally fails when not enough samples`() = runTest {
        val manager = createManager(minSamplesForTraining = 10)
        manager.setBaseModel(testCachedModel())

        // Add fewer than minimum
        repeat(5) {
            manager.addTrainingSample(
                input = floatArrayOf(it.toFloat()),
                target = floatArrayOf(0.0f),
            )
        }

        val result = manager.trainIncrementally()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Not enough samples") == true)
    }

    @Test
    fun `trainIncrementally fails when no model loaded`() = runTest {
        val manager = createManager(minSamplesForTraining = 1)

        manager.addTrainingSample(
            input = floatArrayOf(1.0f),
            target = floatArrayOf(0.0f),
        )

        val result = manager.trainIncrementally()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No model loaded") == true)
    }

    @Test
    fun `trainIncrementally succeeds with enough samples and model`() = runTest {
        val manager = createManager(minSamplesForTraining = 2, bufferSizeThreshold = 100)
        manager.setBaseModel(testCachedModel())

        val trainingResult = TrainingResult(
            sampleCount = 3,
            loss = 0.05,
            accuracy = 0.95,
            trainingTime = 1.0,
            updatedModelPath = null,
        )
        coEvery { trainer.train(any<TrainingDataProvider>(), any<TrainingConfig>()) } returns
            Result.success(trainingResult)

        repeat(3) {
            manager.addTrainingSample(
                input = floatArrayOf(it.toFloat()),
                target = floatArrayOf(0.0f),
            )
        }

        val result = manager.trainIncrementally()
        assertTrue(result.isSuccess)
        assertEquals(0.05, result.getOrNull()?.loss)
    }

    @Test
    fun `forceTraining fails when buffer is empty`() = runTest {
        val manager = createManager()
        manager.setBaseModel(testCachedModel())

        val result = manager.forceTraining()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No training samples") == true)
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    @Test
    fun `statistics initially show no training`() = runTest {
        val manager = createManager()

        val stats = manager.getStatistics()

        assertEquals(0, stats.totalTrainingSessions)
        assertEquals(0, stats.totalSamplesTrained)
        assertEquals(0, stats.bufferedSamples)
        assertNull(stats.lastTrainingTimeMs)
        assertNull(stats.averageLoss)
        assertNull(stats.averageAccuracy)
        assertFalse(stats.isPersonalized)
        assertEquals(TrainingMode.LOCAL_ONLY, stats.trainingMode)
    }

    @Test
    fun `statistics updated after training`() = runTest {
        val manager = createManager(minSamplesForTraining = 2, bufferSizeThreshold = 100)
        manager.setBaseModel(testCachedModel())

        coEvery { trainer.train(any<TrainingDataProvider>(), any<TrainingConfig>()) } returns
            Result.success(
                TrainingResult(
                    sampleCount = 3,
                    loss = 0.1,
                    accuracy = 0.8,
                    trainingTime = 0.5,
                ),
            )

        repeat(3) {
            manager.addTrainingSample(floatArrayOf(it.toFloat()), floatArrayOf(0.0f))
        }
        manager.trainIncrementally()

        val stats = manager.getStatistics()
        assertEquals(1, stats.totalTrainingSessions)
        assertEquals(3, stats.totalSamplesTrained)
        assertEquals(0, stats.bufferedSamples) // cleared after training
        assertNotNull(stats.lastTrainingTimeMs)
    }

    @Test
    fun `training history is accessible`() = runTest {
        val manager = createManager(minSamplesForTraining = 2, bufferSizeThreshold = 100)
        manager.setBaseModel(testCachedModel())

        coEvery { trainer.train(any<TrainingDataProvider>(), any<TrainingConfig>()) } returns
            Result.success(
                TrainingResult(
                    sampleCount = 2,
                    loss = 0.2,
                    accuracy = 0.7,
                    trainingTime = 0.3,
                ),
            )

        repeat(2) {
            manager.addTrainingSample(floatArrayOf(it.toFloat()), floatArrayOf(0.0f))
        }
        manager.trainIncrementally()

        val history = manager.getTrainingHistory()
        assertEquals(1, history.size)
        assertEquals(0.2, history[0].loss)
        assertEquals(0.7, history[0].accuracy)
    }

    // =========================================================================
    // Training modes
    // =========================================================================

    @Test
    fun `federated mode statistics`() = runTest {
        val manager = createManager(trainingMode = TrainingMode.FEDERATED)

        val stats = manager.getStatistics()
        assertEquals(TrainingMode.FEDERATED, stats.trainingMode)
    }

    @Test
    fun `federated mode triggers upload callback after threshold`() = runTest {
        var uploadCalled = false
        val manager = createManager(
            minSamplesForTraining = 1,
            bufferSizeThreshold = 100,
            trainingMode = TrainingMode.FEDERATED,
            uploadThreshold = 1,
            onUploadNeeded = { uploadCalled = true },
        )
        manager.setBaseModel(testCachedModel())

        coEvery { trainer.train(any<TrainingDataProvider>(), any<TrainingConfig>()) } returns
            Result.success(
                TrainingResult(sampleCount = 1, loss = 0.1, accuracy = 0.9, trainingTime = 0.1),
            )

        manager.addTrainingSample(floatArrayOf(1.0f), floatArrayOf(0.0f))
        manager.trainIncrementally()

        assertTrue(uploadCalled)
    }

    @Test
    fun `local only mode does not trigger upload`() = runTest {
        var uploadCalled = false
        val manager = createManager(
            minSamplesForTraining = 1,
            bufferSizeThreshold = 100,
            trainingMode = TrainingMode.LOCAL_ONLY,
            uploadThreshold = 1,
            onUploadNeeded = { uploadCalled = true },
        )
        manager.setBaseModel(testCachedModel())

        coEvery { trainer.train(any<TrainingDataProvider>(), any<TrainingConfig>()) } returns
            Result.success(
                TrainingResult(sampleCount = 1, loss = 0.1, accuracy = 0.9, trainingTime = 0.1),
            )

        manager.addTrainingSample(floatArrayOf(1.0f), floatArrayOf(0.0f))
        manager.trainIncrementally()

        assertFalse(uploadCalled)
    }
}
