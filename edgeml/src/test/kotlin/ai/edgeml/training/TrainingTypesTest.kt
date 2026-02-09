package ai.edgeml.training

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TrainingTypesTest {
    // =========================================================================
    // TrainingConfig
    // =========================================================================

    @Test
    fun `TrainingConfig has sensible defaults`() {
        val config = TrainingConfig()
        assertEquals(1, config.epochs)
        assertEquals(32, config.batchSize)
        assertEquals(0.001f, config.learningRate)
        assertEquals("categorical_crossentropy", config.lossFunction)
        assertEquals("adam", config.optimizer)
    }

    @Test
    fun `TrainingConfig allows custom values`() {
        val config =
            TrainingConfig(
                epochs = 10,
                batchSize = 64,
                learningRate = 0.01f,
                lossFunction = "mse",
                optimizer = "sgd",
            )
        assertEquals(10, config.epochs)
        assertEquals(64, config.batchSize)
        assertEquals(0.01f, config.learningRate)
        assertEquals("mse", config.lossFunction)
        assertEquals("sgd", config.optimizer)
    }

    // =========================================================================
    // TrainingResult
    // =========================================================================

    @Test
    fun `TrainingResult stores all fields`() {
        val result =
            TrainingResult(
                sampleCount = 100,
                loss = 0.5,
                accuracy = 0.85,
                trainingTime = 12.3,
                metrics = mapOf("f1_score" to 0.9),
                updatedModelPath = "/tmp/model.tflite",
            )

        assertEquals(100, result.sampleCount)
        assertEquals(0.5, result.loss)
        assertEquals(0.85, result.accuracy)
        assertEquals(12.3, result.trainingTime)
        assertEquals(0.9, result.metrics["f1_score"])
        assertEquals("/tmp/model.tflite", result.updatedModelPath)
    }

    @Test
    fun `TrainingResult defaults to empty metrics and null path`() {
        val result =
            TrainingResult(
                sampleCount = 10,
                loss = null,
                accuracy = null,
                trainingTime = 1.0,
            )

        assertEquals(emptyMap(), result.metrics)
        assertEquals(null, result.updatedModelPath)
    }

    // =========================================================================
    // WeightUpdate
    // =========================================================================

    @Test
    fun `WeightUpdate equality compares weightsData by content`() {
        val update1 =
            WeightUpdate(
                modelId = "m1",
                version = "1.0",
                weightsData = byteArrayOf(1, 2, 3),
                sampleCount = 10,
            )
        val update2 =
            WeightUpdate(
                modelId = "m1",
                version = "1.0",
                weightsData = byteArrayOf(1, 2, 3),
                sampleCount = 10,
            )

        assertEquals(update1, update2)
        assertEquals(update1.hashCode(), update2.hashCode())
    }

    @Test
    fun `WeightUpdate not equal when weightsData differs`() {
        val update1 = WeightUpdate("m1", "1.0", byteArrayOf(1, 2, 3), 10)
        val update2 = WeightUpdate("m1", "1.0", byteArrayOf(4, 5, 6), 10)

        assertNotEquals(update1, update2)
    }

    @Test
    fun `WeightUpdate not equal when modelId differs`() {
        val data = byteArrayOf(1, 2, 3)
        val update1 = WeightUpdate("m1", "1.0", data, 10)
        val update2 = WeightUpdate("m2", "1.0", data, 10)

        assertNotEquals(update1, update2)
    }

    @Test
    fun `WeightUpdate defaults to empty metrics`() {
        val update = WeightUpdate("m1", "1.0", byteArrayOf(), 5)
        assertEquals(emptyMap(), update.metrics)
    }

    // =========================================================================
    // InMemoryTrainingDataProvider
    // =========================================================================

    @Test
    fun `InMemoryTrainingDataProvider returns all data`() =
        runBlocking {
            val data =
                listOf(
                    floatArrayOf(1f, 2f) to floatArrayOf(0f, 1f),
                    floatArrayOf(3f, 4f) to floatArrayOf(1f, 0f),
                )
            val provider = InMemoryTrainingDataProvider(data)

            val trainingData = provider.getTrainingData()
            assertEquals(2, trainingData.size)
            assertContentEquals(floatArrayOf(1f, 2f), trainingData[0].first)
            assertContentEquals(floatArrayOf(0f, 1f), trainingData[0].second)
        }

    @Test
    fun `InMemoryTrainingDataProvider returns correct sample count`() =
        runBlocking {
            val data =
                listOf(
                    floatArrayOf(1f) to floatArrayOf(0f),
                    floatArrayOf(2f) to floatArrayOf(1f),
                    floatArrayOf(3f) to floatArrayOf(0f),
                )
            val provider = InMemoryTrainingDataProvider(data)

            assertEquals(3, provider.getSampleCount())
        }

    @Test
    fun `InMemoryTrainingDataProvider handles empty data`() =
        runBlocking {
            val provider = InMemoryTrainingDataProvider(emptyList())
            assertEquals(0, provider.getSampleCount())
            assertEquals(emptyList(), provider.getTrainingData())
        }
}
