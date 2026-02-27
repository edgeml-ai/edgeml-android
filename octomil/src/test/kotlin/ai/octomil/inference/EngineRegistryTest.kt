package ai.octomil.inference

import ai.octomil.Engine
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EngineRegistryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        EngineRegistry.reset()
    }

    @After
    fun tearDown() {
        EngineRegistry.reset()
    }

    // =========================================================================
    // Default registrations
    // =========================================================================

    @Test
    fun `default TEXT modality resolves to LLMEngine`() {
        val engine = EngineRegistry.resolve(Modality.TEXT, context = context)
        assertIs<LLMEngine>(engine)
    }

    @Test
    fun `default IMAGE modality resolves to ImageEngine`() {
        val engine = EngineRegistry.resolve(Modality.IMAGE, context = context)
        assertIs<ImageEngine>(engine)
    }

    @Test
    fun `default AUDIO modality resolves to AudioEngine`() {
        val engine = EngineRegistry.resolve(Modality.AUDIO, context = context)
        assertIs<AudioEngine>(engine)
    }

    @Test
    fun `default VIDEO modality resolves to VideoEngine`() {
        val engine = EngineRegistry.resolve(Modality.VIDEO, context = context)
        assertIs<VideoEngine>(engine)
    }

    @Test
    fun `default TIME_SERIES modality resolves to LLMEngine`() {
        val engine = EngineRegistry.resolve(Modality.TIME_SERIES, context = context)
        assertIs<LLMEngine>(engine)
    }

    // =========================================================================
    // Exact match beats modality default
    // =========================================================================

    @Test
    fun `exact engine match takes priority over modality default`() {
        val custom = object : StreamingInferenceEngine {
            override fun generate(input: Any, modality: Modality) =
                kotlinx.coroutines.flow.emptyFlow<InferenceChunk>()
        }
        EngineRegistry.register(Modality.TEXT, Engine.TFLITE) { _, _ -> custom }

        val resolved = EngineRegistry.resolve(Modality.TEXT, Engine.TFLITE, context)
        assertEquals(custom, resolved)
    }

    @Test
    fun `modality default used when exact engine match absent`() {
        // No TFLITE-specific registration for IMAGE exists by default
        val engine = EngineRegistry.resolve(Modality.IMAGE, Engine.TFLITE, context)
        assertIs<ImageEngine>(engine)
    }

    // =========================================================================
    // Custom registration overrides defaults
    // =========================================================================

    @Test
    fun `custom registration overrides default for modality`() {
        val custom = object : StreamingInferenceEngine {
            override fun generate(input: Any, modality: Modality) =
                kotlinx.coroutines.flow.emptyFlow<InferenceChunk>()
        }
        EngineRegistry.register(Modality.TEXT) { _, _ -> custom }

        val resolved = EngineRegistry.resolve(Modality.TEXT, context = context)
        assertEquals(custom, resolved)
    }

    // =========================================================================
    // Missing registration throws EngineResolutionException
    // =========================================================================

    @Test
    fun `missing registration throws EngineResolutionException`() {
        EngineRegistry.clearAll()

        val ex = assertFailsWith<EngineResolutionException> {
            EngineRegistry.resolve(Modality.TEXT, context = context)
        }
        assertTrue(ex.message!!.contains("No engine registered"))
        assertTrue(ex.message!!.contains("TEXT"))
    }

    // =========================================================================
    // engineFromFilename
    // =========================================================================

    @Test
    fun `engineFromFilename returns TFLITE for tflite extension`() {
        assertEquals(Engine.TFLITE, EngineRegistry.engineFromFilename("model.tflite"))
    }

    @Test
    fun `engineFromFilename returns TFLITE case insensitive`() {
        assertEquals(Engine.TFLITE, EngineRegistry.engineFromFilename("model.TFLITE"))
    }

    @Test
    fun `engineFromFilename returns MEDIAPIPE for task extension`() {
        assertEquals(Engine.MEDIAPIPE, EngineRegistry.engineFromFilename("gemma.task"))
    }

    @Test
    fun `engineFromFilename returns MEDIAPIPE case insensitive`() {
        assertEquals(Engine.MEDIAPIPE, EngineRegistry.engineFromFilename("gemma.TASK"))
    }

    @Test
    fun `engineFromFilename returns null for unknown extension`() {
        assertNull(EngineRegistry.engineFromFilename("model.onnx"))
    }

    @Test
    fun `engineFromFilename returns null for no extension`() {
        assertNull(EngineRegistry.engineFromFilename("model"))
    }

    // =========================================================================
    // reset() restores defaults
    // =========================================================================

    @Test
    fun `reset restores defaults after custom override`() {
        val custom = object : StreamingInferenceEngine {
            override fun generate(input: Any, modality: Modality) =
                kotlinx.coroutines.flow.emptyFlow<InferenceChunk>()
        }
        EngineRegistry.register(Modality.TEXT) { _, _ -> custom }

        // Verify custom is active
        assertEquals(custom, EngineRegistry.resolve(Modality.TEXT, context = context))

        // Reset and verify default is restored
        EngineRegistry.reset()
        val resolved = EngineRegistry.resolve(Modality.TEXT, context = context)
        assertIs<LLMEngine>(resolved)
    }

    // =========================================================================
    // Thread safety
    // =========================================================================

    @Test
    fun `concurrent register and resolve does not crash`() = runTest {
        val iterations = 100
        val jobs = (1..iterations).map { i ->
            launch(Dispatchers.Default) {
                if (i % 2 == 0) {
                    EngineRegistry.register(Modality.TEXT) { ctx, _ -> LLMEngine(ctx) }
                } else {
                    EngineRegistry.resolve(Modality.TEXT, context = context)
                }
            }
        }
        jobs.forEach { it.join() }
        // If we get here without ConcurrentModificationException, the test passes.
    }

    // =========================================================================
    // MediaPipe TEXT registration
    // =========================================================================

    @Test
    fun `TEXT with MEDIAPIPE engine resolves to MediaPipeLLMEngine`() {
        val modelFile = File("/tmp/test.task")
        val engine = EngineRegistry.resolve(Modality.TEXT, Engine.MEDIAPIPE, context, modelFile)
        assertIs<MediaPipeLLMEngine>(engine)
    }

    // =========================================================================
    // modelFile passed to factory
    // =========================================================================

    @Test
    fun `resolve passes modelFile to factory`() {
        var receivedFile: File? = null
        EngineRegistry.register(Modality.IMAGE, Engine.TFLITE) { _, file ->
            receivedFile = file
            ImageEngine(context)
        }

        val testFile = File("/tmp/test.tflite")
        EngineRegistry.resolve(Modality.IMAGE, Engine.TFLITE, context, testFile)
        assertEquals(testFile, receivedFile)
    }
}
