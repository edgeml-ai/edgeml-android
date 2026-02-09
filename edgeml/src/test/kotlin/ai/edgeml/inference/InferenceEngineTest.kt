package ai.edgeml.inference

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InferenceEngineTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
    }

    // =========================================================================
    // LLMEngine
    // =========================================================================

    @Test
    fun `LLMEngine emits chunks with TEXT modality`() = runTest {
        val engine = LLMEngine(context, maxTokens = 100)

        val chunks = engine.generate("Hello world", Modality.TEXT).toList()

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertEquals(Modality.TEXT, chunk.modality)
            assertTrue(chunk.data.isNotEmpty())
        }
    }

    @Test
    fun `LLMEngine respects maxTokens limit`() = runTest {
        val engine = LLMEngine(context, maxTokens = 3)

        val chunks = engine.generate("Tell me a very long story", Modality.TEXT).toList()

        assertTrue(chunks.size <= 3)
    }

    @Test
    fun `LLMEngine chunk indices are sequential`() = runTest {
        val engine = LLMEngine(context, maxTokens = 100)

        val chunks = engine.generate("Test prompt", Modality.TEXT).toList()

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index)
        }
    }

    @Test
    fun `LLMEngine emits non-empty data per chunk`() = runTest {
        val engine = LLMEngine(context, maxTokens = 100)

        val chunks = engine.generate("Hello", Modality.TEXT).toList()

        chunks.forEach { chunk ->
            assertTrue(chunk.data.isNotEmpty(), "Chunk ${chunk.index} has empty data")
        }
    }

    // =========================================================================
    // ImageEngine
    // =========================================================================

    @Test
    fun `ImageEngine emits correct number of steps`() = runTest {
        val engine = ImageEngine(context, steps = 5)

        val chunks = engine.generate("a cat", Modality.IMAGE).toList()

        assertEquals(5, chunks.size)
    }

    @Test
    fun `ImageEngine emits IMAGE modality`() = runTest {
        val engine = ImageEngine(context, steps = 3)

        val chunks = engine.generate("prompt", Modality.IMAGE).toList()

        chunks.forEach { chunk ->
            assertEquals(Modality.IMAGE, chunk.modality)
        }
    }

    @Test
    fun `ImageEngine chunk indices are sequential`() = runTest {
        val engine = ImageEngine(context, steps = 10)

        val chunks = engine.generate("prompt", Modality.IMAGE).toList()

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index)
        }
    }

    @Test
    fun `ImageEngine emits non-empty data`() = runTest {
        val engine = ImageEngine(context, steps = 2)

        val chunks = engine.generate("prompt", Modality.IMAGE).toList()

        chunks.forEach { chunk ->
            assertTrue(chunk.data.isNotEmpty())
        }
    }

    // =========================================================================
    // AudioEngine
    // =========================================================================

    @Test
    fun `AudioEngine emits correct number of frames`() = runTest {
        val engine = AudioEngine(context, totalFrames = 10)

        val chunks = engine.generate("audio input", Modality.AUDIO).toList()

        assertEquals(10, chunks.size)
    }

    @Test
    fun `AudioEngine emits AUDIO modality`() = runTest {
        val engine = AudioEngine(context, totalFrames = 3)

        val chunks = engine.generate("input", Modality.AUDIO).toList()

        chunks.forEach { chunk ->
            assertEquals(Modality.AUDIO, chunk.modality)
        }
    }

    @Test
    fun `AudioEngine emits non-empty audio data`() = runTest {
        val engine = AudioEngine(context, totalFrames = 2)

        val chunks = engine.generate("input", Modality.AUDIO).toList()

        chunks.forEach { chunk ->
            assertTrue(chunk.data.isNotEmpty())
            // Audio frames should be 1024 * 2 = 2048 bytes
            assertEquals(2048, chunk.data.size)
        }
    }

    @Test
    fun `AudioEngine chunk indices are sequential`() = runTest {
        val engine = AudioEngine(context, totalFrames = 5)

        val chunks = engine.generate("input", Modality.AUDIO).toList()

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index)
        }
    }

    // =========================================================================
    // VideoEngine
    // =========================================================================

    @Test
    fun `VideoEngine emits correct number of frames`() = runTest {
        val engine = VideoEngine(context, frameCount = 8)

        val chunks = engine.generate("video input", Modality.VIDEO).toList()

        assertEquals(8, chunks.size)
    }

    @Test
    fun `VideoEngine emits VIDEO modality`() = runTest {
        val engine = VideoEngine(context, frameCount = 3)

        val chunks = engine.generate("input", Modality.VIDEO).toList()

        chunks.forEach { chunk ->
            assertEquals(Modality.VIDEO, chunk.modality)
        }
    }

    @Test
    fun `VideoEngine emits non-empty frame data`() = runTest {
        val engine = VideoEngine(context, frameCount = 2)

        val chunks = engine.generate("input", Modality.VIDEO).toList()

        chunks.forEach { chunk ->
            assertTrue(chunk.data.isNotEmpty())
            assertEquals(1024, chunk.data.size)
        }
    }

    @Test
    fun `VideoEngine chunk indices are sequential`() = runTest {
        val engine = VideoEngine(context, frameCount = 6)

        val chunks = engine.generate("input", Modality.VIDEO).toList()

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index)
        }
    }

    // =========================================================================
    // StreamingInferenceEngine interface
    // =========================================================================

    @Test
    fun `all engines implement StreamingInferenceEngine`() {
        val engines: List<StreamingInferenceEngine> = listOf(
            LLMEngine(context),
            ImageEngine(context),
            AudioEngine(context),
            VideoEngine(context),
        )

        assertEquals(4, engines.size)
    }

    // =========================================================================
    // Modality value mapping
    // =========================================================================

    @Test
    fun `Modality value is lowercase name`() {
        assertEquals("text", Modality.TEXT.value)
        assertEquals("image", Modality.IMAGE.value)
        assertEquals("audio", Modality.AUDIO.value)
        assertEquals("video", Modality.VIDEO.value)
    }

    @Test
    fun `all Modality values are present`() {
        assertEquals(4, Modality.entries.size)
    }
}
