package ai.octomil.runtime.engines.tflite

import ai.octomil.generated.Modality
import ai.octomil.runtime.core.Engine
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        // No LLAMA_CPP-specific registration for IMAGE exists by default,
        // so it falls back to the modality default.
        val engine = EngineRegistry.resolve(Modality.IMAGE, Engine.LLAMA_CPP, context)
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
    fun `engineFromFilename returns LLAMA_CPP for gguf extension`() {
        assertEquals(Engine.LLAMA_CPP, EngineRegistry.engineFromFilename("model.gguf"))
    }

    @Test
    fun `engineFromFilename returns LLAMA_CPP case insensitive`() {
        assertEquals(Engine.LLAMA_CPP, EngineRegistry.engineFromFilename("model.GGUF"))
    }

    @Test
    fun `engineFromFilename returns null for task extension`() {
        assertNull(EngineRegistry.engineFromFilename("gemma.task"))
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
    // Explicit TFLITE key registrations
    // =========================================================================

    @Test
    fun `explicit TFLITE key resolves for TEXT modality`() {
        val resolved = EngineRegistry.resolve(Modality.TEXT, Engine.TFLITE, context)
        assertIs<LLMEngine>(resolved)
    }

    @Test
    fun `explicit TFLITE key resolves for IMAGE modality`() {
        val resolved = EngineRegistry.resolve(Modality.IMAGE, Engine.TFLITE, context)
        assertIs<ImageEngine>(resolved)
    }

    @Test
    fun `explicit TFLITE key resolves for AUDIO modality`() {
        val resolved = EngineRegistry.resolve(Modality.AUDIO, Engine.TFLITE, context)
        assertIs<AudioEngine>(resolved)
    }

    @Test
    fun `explicit TFLITE key resolves for VIDEO modality`() {
        val resolved = EngineRegistry.resolve(Modality.VIDEO, Engine.TFLITE, context)
        assertIs<VideoEngine>(resolved)
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

    // =========================================================================
    // Helper: create a test EnginePlugin
    // =========================================================================

    private fun testPlugin(
        name: String,
        available: Boolean = true,
        info: String = "",
        supports: Boolean = true,
        tps: Double = 50.0,
        priority: Int = 100,
        error: String? = null,
    ): EnginePlugin = object : EnginePlugin {
        override val name: String = name
        override val priority: Int = priority
        override fun detect(context: Context) = available
        override fun detectInfo(context: Context) = info
        override fun supportsModel(modelName: String) = supports
        override fun benchmark(context: Context, modelName: String, nTokens: Int) =
            BenchmarkResult(
                engineName = name,
                tokensPerSecond = tps,
                ttftMs = 10.0,
                memoryMb = 64.0,
                error = error,
            )
        override fun createEngine(context: Context, modelName: String): StreamingInferenceEngine =
            StreamingInferenceEngine { _, _ -> kotlinx.coroutines.flow.emptyFlow() }
    }

    // =========================================================================
    // detectAll
    // =========================================================================

    @Test
    fun `detectAll returns detection result for each plugin`() {
        EngineRegistry.registerPlugin(testPlugin("alpha", available = true, info = "v1"))
        EngineRegistry.registerPlugin(testPlugin("beta", available = false, info = "missing"))

        val results = EngineRegistry.detectAll(Modality.TEXT, context)
        assertEquals(2, results.size)

        val alpha = results.first { it.engine == "alpha" }
        assertTrue(alpha.available)
        assertEquals("v1", alpha.info)

        val beta = results.first { it.engine == "beta" }
        assertFalse(beta.available)
        assertEquals("missing", beta.info)
    }

    @Test
    fun `detectAll returns empty list when no plugins registered`() {
        val results = EngineRegistry.detectAll(Modality.TEXT, context)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `detectAll handles plugin that throws during detect`() {
        val crashPlugin = object : EnginePlugin {
            override val name = "crashy"
            override fun detect(context: Context): Boolean = throw RuntimeException("boom")
            override fun supportsModel(modelName: String) = true
            override fun benchmark(context: Context, modelName: String, nTokens: Int) =
                BenchmarkResult("crashy", 0.0, 0.0, 0.0)
            override fun createEngine(context: Context, modelName: String) =
                StreamingInferenceEngine { _, _ -> kotlinx.coroutines.flow.emptyFlow() }
        }
        EngineRegistry.registerPlugin(crashPlugin)

        val results = EngineRegistry.detectAll(Modality.TEXT, context)
        assertEquals(1, results.size)
        assertFalse(results[0].available)
    }

    @Test
    fun `detectAll sorts by priority`() {
        EngineRegistry.registerPlugin(testPlugin("low-pri", priority = 200))
        EngineRegistry.registerPlugin(testPlugin("high-pri", priority = 10))

        val results = EngineRegistry.detectAll(Modality.TEXT, context)
        assertEquals("high-pri", results[0].engine)
        assertEquals("low-pri", results[1].engine)
    }

    // =========================================================================
    // benchmarkAll
    // =========================================================================

    @Test
    fun `benchmarkAll returns ranked results sorted by tps descending`() = runTest {
        EngineRegistry.registerPlugin(testPlugin("slow", tps = 10.0))
        EngineRegistry.registerPlugin(testPlugin("fast", tps = 100.0))
        EngineRegistry.registerPlugin(testPlugin("mid", tps = 50.0))

        val ranked = EngineRegistry.benchmarkAll(Modality.TEXT, context, "test-model")
        assertEquals(3, ranked.size)
        assertEquals("fast", ranked[0].engine)
        assertEquals("mid", ranked[1].engine)
        assertEquals("slow", ranked[2].engine)
    }

    @Test
    fun `benchmarkAll filters out unavailable plugins`() = runTest {
        EngineRegistry.registerPlugin(testPlugin("available", available = true, tps = 50.0))
        EngineRegistry.registerPlugin(testPlugin("unavailable", available = false, tps = 100.0))

        val ranked = EngineRegistry.benchmarkAll(Modality.TEXT, context, "test-model")
        assertEquals(1, ranked.size)
        assertEquals("available", ranked[0].engine)
    }

    @Test
    fun `benchmarkAll filters out plugins that do not support model`() = runTest {
        EngineRegistry.registerPlugin(testPlugin("supports", supports = true, tps = 50.0))
        EngineRegistry.registerPlugin(testPlugin("no-support", supports = false, tps = 100.0))

        val ranked = EngineRegistry.benchmarkAll(Modality.TEXT, context, "test-model")
        assertEquals(1, ranked.size)
        assertEquals("supports", ranked[0].engine)
    }

    @Test
    fun `benchmarkAll handles plugin that throws during benchmark`() = runTest {
        val crashPlugin = object : EnginePlugin {
            override val name = "crashy"
            override fun detect(context: Context) = true
            override fun supportsModel(modelName: String) = true
            override fun benchmark(context: Context, modelName: String, nTokens: Int): BenchmarkResult =
                throw RuntimeException("benchmark failed")
            override fun createEngine(context: Context, modelName: String) =
                StreamingInferenceEngine { _, _ -> kotlinx.coroutines.flow.emptyFlow() }
        }
        EngineRegistry.registerPlugin(crashPlugin)

        val ranked = EngineRegistry.benchmarkAll(Modality.TEXT, context, "test-model")
        assertEquals(1, ranked.size)
        assertFalse(ranked[0].result.ok)
        assertEquals("benchmark failed", ranked[0].result.error)
    }

    @Test
    fun `benchmarkAll returns empty when no plugins registered`() = runTest {
        val ranked = EngineRegistry.benchmarkAll(Modality.TEXT, context, "test-model")
        assertTrue(ranked.isEmpty())
    }

    // =========================================================================
    // selectBest
    // =========================================================================

    @Test
    fun `selectBest returns first ok result`() {
        val ranked = listOf(
            RankedEngine("fast", BenchmarkResult("fast", 100.0, 5.0, 64.0)),
            RankedEngine("slow", BenchmarkResult("slow", 50.0, 10.0, 64.0)),
        )
        val best = EngineRegistry.selectBest(ranked)
        assertNotNull(best)
        assertEquals("fast", best.engine)
    }

    @Test
    fun `selectBest skips errored results`() {
        val ranked = listOf(
            RankedEngine("err", BenchmarkResult("err", 100.0, 5.0, 64.0, error = "fail")),
            RankedEngine("ok", BenchmarkResult("ok", 50.0, 10.0, 64.0)),
        )
        val best = EngineRegistry.selectBest(ranked)
        assertNotNull(best)
        assertEquals("ok", best.engine)
    }

    @Test
    fun `selectBest returns null when all errored`() {
        val ranked = listOf(
            RankedEngine("a", BenchmarkResult("a", 0.0, 0.0, 0.0, error = "fail")),
            RankedEngine("b", BenchmarkResult("b", 0.0, 0.0, 0.0, error = "fail")),
        )
        assertNull(EngineRegistry.selectBest(ranked))
    }

    @Test
    fun `selectBest returns null for empty list`() {
        assertNull(EngineRegistry.selectBest(emptyList()))
    }

    // =========================================================================
    // reset clears plugins
    // =========================================================================

    @Test
    fun `reset clears registered plugins`() {
        EngineRegistry.registerPlugin(testPlugin("test-engine"))
        assertEquals(1, EngineRegistry.detectAll(Modality.TEXT, context).size)

        EngineRegistry.reset()
        assertTrue(EngineRegistry.detectAll(Modality.TEXT, context).isEmpty())
    }
}
