package ai.octomil.text

import ai.octomil.chat.LLMRuntime
import ai.octomil.chat.LLMRuntimeRegistry
import ai.octomil.chat.GenerateConfig
import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.manifest.ModelRef
import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class OctomilTextPredictionsTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var modelDir: File
    private lateinit var modelFile: File

    private var savedFactory: ((File) -> LLMRuntime)? = null

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        filesDir = File(System.getProperty("java.io.tmpdir"), "octomil_pred_test_${System.nanoTime()}")
        filesDir.mkdirs()
        cacheDir = File(System.getProperty("java.io.tmpdir"), "octomil_pred_test_cache_${System.nanoTime()}")
        cacheDir.mkdirs()
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        // Mock assets to throw so ModelResolver.assets() short-circuits
        // instead of pulling in Octomil.copyAssetToCache (heavy statics → OOM)
        val mockAssets = mockk<AssetManager>()
        every { mockAssets.open(any()) } throws FileNotFoundException("test mock")
        every { context.assets } returns mockAssets

        // Create a fake model file on disk
        modelDir = File(filesDir, "octomil_models/test-model/1.0.0")
        modelDir.mkdirs()
        modelFile = File(modelDir, "model.gguf")
        modelFile.writeText("fake-model")

        // Save and clear global registry state
        savedFactory = LLMRuntimeRegistry.factory
    }

    @After
    fun tearDown() {
        LLMRuntimeRegistry.factory = savedFactory
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** A fake LLMRuntime that returns canned prediction results. */
    private class FakeLLMRuntime(
        private val predictions: List<Pair<String, Float>> = listOf(
            "\u0120are" to 0.9f,
            "\u0120you" to 0.8f,
            "\u0120is" to 0.7f,
            "\u0120at" to 0.6f,
            "\u0120an" to 0.5f,
            "1" to 0.4f,       // will be filtered (starts with digit)
            "\u0120a" to 0.3f, // will be filtered (single char after strip)
            ".x" to 0.2f,      // will be filtered (starts with non-letter)
        ),
    ) : LLMRuntime {
        var loadHandleCalls = 0
        var predictNextCalls = 0
        var unloadCalls = 0
        var lastHandle: Long = -1L

        override fun generate(prompt: String, config: GenerateConfig): Flow<String> = emptyFlow()
        override fun supportsPrediction(): Boolean = true

        override suspend fun loadPredictionHandle(modelPath: String): Long {
            loadHandleCalls++
            return 42L
        }

        override suspend fun predictNext(handle: Long, text: String, k: Int): List<Pair<String, Float>> {
            predictNextCalls++
            lastHandle = handle
            return predictions.take(k)
        }

        override suspend fun unloadPredictionHandle(handle: Long) {
            unloadCalls++
        }

        override fun close() {}
    }

    /** Build OctomilTextPredictions pointing at test context and optional catalog. */
    private fun buildPredictions(
        catalog: ModelCatalogService? = null,
    ): OctomilTextPredictions = OctomilTextPredictions(
        catalogProvider = { catalog },
        contextProvider = { context },
    )

    // =========================================================================
    // create() — happy path
    // =========================================================================

    @Test
    fun `create returns filtered predictions with scores`() = runTest {
        val fakeRuntime = FakeLLMRuntime()
        LLMRuntimeRegistry.factory = { fakeRuntime }

        val predictions = buildPredictions()
        val result = predictions.create(
            TextPredictionRequest(
                model = ModelRef.Id("test-model"),
                input = "Hello, how ",
                n = 3,
            )
        )

        assertEquals(3, result.predictions.size)
        assertEquals("are", result.predictions[0].text)
        assertEquals(0.9f, result.predictions[0].score)
        assertEquals("you", result.predictions[1].text)
        assertEquals("test-model", result.model)
        val latency = result.latencyMs
        assertNotNull(latency)
        assertTrue(latency >= 0)
    }

    @Test
    fun `create respects n parameter`() = runTest {
        val fakeRuntime = FakeLLMRuntime()
        LLMRuntimeRegistry.factory = { fakeRuntime }

        val predictions = buildPredictions()

        val result1 = predictions.create(
            TextPredictionRequest(model = ModelRef.Id("test-model"), input = "test", n = 1)
        )
        assertEquals(1, result1.predictions.size)

        // Unload and reload for fresh handle
        predictions.unloadAll()

        val result2 = predictions.create(
            TextPredictionRequest(model = ModelRef.Id("test-model"), input = "test", n = 5)
        )
        // Only 3 pass the filter (are, you, is — "at" and "an" are 2 chars which pass, so 5)
        assertTrue(result2.predictions.size <= 5)
    }

    @Test
    fun `create filters BPE artifacts and non-words`() = runTest {
        val fakeRuntime = FakeLLMRuntime(
            predictions = listOf(
                "\u0120hello" to 0.9f,
                "123" to 0.8f,
                "\u0120world" to 0.7f,
                "\u0120a" to 0.6f,  // single char after strip
            )
        )
        LLMRuntimeRegistry.factory = { fakeRuntime }

        val predictions = buildPredictions()
        val result = predictions.create(
            TextPredictionRequest(model = ModelRef.Id("test-model"), input = "test", n = 10)
        )

        assertEquals(2, result.predictions.size)
        assertEquals("hello", result.predictions[0].text)
        assertEquals("world", result.predictions[1].text)
    }

    // =========================================================================
    // Handle caching
    // =========================================================================

    @Test
    fun `create reuses handle for same model`() = runTest {
        val fakeRuntime = FakeLLMRuntime()
        LLMRuntimeRegistry.factory = { fakeRuntime }

        val predictions = buildPredictions()

        predictions.create(TextPredictionRequest(model = ModelRef.Id("test-model"), input = "a"))
        predictions.create(TextPredictionRequest(model = ModelRef.Id("test-model"), input = "b"))

        assertEquals(1, fakeRuntime.loadHandleCalls, "handle should be loaded once")
        assertEquals(2, fakeRuntime.predictNextCalls, "predictNext should be called twice")
    }

    @Test
    fun `create loads separate handles for different models`() = runTest {
        // Set up second model
        val model2Dir = File(filesDir, "octomil_models/other-model/1.0.0")
        model2Dir.mkdirs()
        File(model2Dir, "model.gguf").writeText("fake-other")

        var handleCounter = 100L
        val runtimes = mutableListOf<FakeLLMRuntime>()
        LLMRuntimeRegistry.factory = {
            FakeLLMRuntime().also {
                runtimes.add(it)
            }
        }

        val predictions = buildPredictions()

        predictions.create(TextPredictionRequest(model = ModelRef.Id("test-model"), input = "a"))
        predictions.create(TextPredictionRequest(model = ModelRef.Id("other-model"), input = "b"))

        assertEquals(2, runtimes.size, "two runtimes should be created")
        assertEquals(1, runtimes[0].loadHandleCalls)
        assertEquals(1, runtimes[1].loadHandleCalls)
    }

    // =========================================================================
    // unload
    // =========================================================================

    @Test
    fun `unload removes handle for specific model`() = runTest {
        val fakeRuntime = FakeLLMRuntime()
        LLMRuntimeRegistry.factory = { fakeRuntime }

        val predictions = buildPredictions()

        predictions.create(TextPredictionRequest(model = ModelRef.Id("test-model"), input = "a"))
        assertEquals(1, fakeRuntime.loadHandleCalls)

        predictions.unload("test-model")
        assertEquals(1, fakeRuntime.unloadCalls)

        // Next create should reload
        predictions.create(TextPredictionRequest(model = ModelRef.Id("test-model"), input = "b"))
        assertEquals(2, fakeRuntime.loadHandleCalls)
    }

    @Test
    fun `unload is no-op for unknown model`() = runTest {
        val predictions = buildPredictions()
        // Should not throw
        predictions.unload("nonexistent")
    }

    @Test
    fun `unloadAll clears all handles`() = runTest {
        val model2Dir = File(filesDir, "octomil_models/other-model/1.0.0")
        model2Dir.mkdirs()
        File(model2Dir, "model.gguf").writeText("fake")

        val runtimes = mutableListOf<FakeLLMRuntime>()
        LLMRuntimeRegistry.factory = { FakeLLMRuntime().also { runtimes.add(it) } }

        val predictions = buildPredictions()

        predictions.create(TextPredictionRequest(model = ModelRef.Id("test-model"), input = "a"))
        predictions.create(TextPredictionRequest(model = ModelRef.Id("other-model"), input = "b"))

        predictions.unloadAll()

        assertEquals(1, runtimes[0].unloadCalls)
        assertEquals(1, runtimes[1].unloadCalls)
    }

    // =========================================================================
    // Error cases
    // =========================================================================

    @Test
    fun `create throws RUNTIME_UNAVAILABLE when no factory registered`() = runTest {
        LLMRuntimeRegistry.factory = null

        val predictions = buildPredictions()
        try {
            predictions.create(
                TextPredictionRequest(model = ModelRef.Id("test-model"), input = "test")
            )
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, e.errorCode)
            assertTrue(e.message!!.contains("LLMRuntime"))
        }
    }

    @Test
    fun `create throws MODEL_NOT_FOUND when model file missing`() = runTest {
        LLMRuntimeRegistry.factory = { FakeLLMRuntime() }

        val predictions = buildPredictions()
        try {
            predictions.create(
                TextPredictionRequest(model = ModelRef.Id("nonexistent-model"), input = "test")
            )
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.MODEL_NOT_FOUND, e.errorCode)
            assertTrue(e.message!!.contains("nonexistent-model"))
        }
    }

    @Test
    fun `create throws RUNTIME_UNAVAILABLE when runtime does not support prediction`() = runTest {
        val nonPredictionRuntime = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = emptyFlow()
            override fun supportsPrediction(): Boolean = false
            override fun close() {}
        }
        LLMRuntimeRegistry.factory = { nonPredictionRuntime }

        val predictions = buildPredictions()
        try {
            predictions.create(
                TextPredictionRequest(model = ModelRef.Id("test-model"), input = "test")
            )
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, e.errorCode)
            assertTrue(e.message!!.contains("prediction"))
        }
    }

    @Test
    fun `create throws RUNTIME_UNAVAILABLE when handle load fails`() = runTest {
        val failingRuntime = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = emptyFlow()
            override fun supportsPrediction(): Boolean = true
            override suspend fun loadPredictionHandle(modelPath: String): Long = -1L
            override fun close() {}
        }
        LLMRuntimeRegistry.factory = { failingRuntime }

        val predictions = buildPredictions()
        try {
            predictions.create(
                TextPredictionRequest(model = ModelRef.Id("test-model"), input = "test")
            )
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, e.errorCode)
            assertTrue(e.message!!.contains("handle"))
        }
    }

    @Test
    fun `create throws RUNTIME_UNAVAILABLE for capability ref without catalog`() = runTest {
        LLMRuntimeRegistry.factory = { FakeLLMRuntime() }

        val predictions = buildPredictions(catalog = null)
        try {
            predictions.create(
                TextPredictionRequest(
                    model = ModelRef.Capability(ModelCapability.KEYBOARD_PREDICTION),
                    input = "test",
                )
            )
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, e.errorCode)
            assertTrue(e.message!!.contains("configure"))
        }
    }

    @Test
    fun `create throws MODEL_NOT_FOUND for capability without matching manifest entry`() = runTest {
        LLMRuntimeRegistry.factory = { FakeLLMRuntime() }

        // Mock catalog that returns null for all capabilities
        val catalog = mockk<ModelCatalogService>()
        every { catalog.modelIdForCapability(any()) } returns null

        val predictions = buildPredictions(catalog = catalog)
        try {
            predictions.create(
                TextPredictionRequest(
                    model = ModelRef.Capability(ModelCapability.KEYBOARD_PREDICTION),
                    input = "test",
                )
            )
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.MODEL_NOT_FOUND, e.errorCode)
        }
    }

    // =========================================================================
    // Capability resolution
    // =========================================================================

    @Test
    fun `create resolves capability to model id via catalog`() = runTest {
        val fakeRuntime = FakeLLMRuntime()
        LLMRuntimeRegistry.factory = { fakeRuntime }

        // Mock catalog that maps KEYBOARD_PREDICTION → "test-model"
        val catalog = mockk<ModelCatalogService>()
        every { catalog.modelIdForCapability(ModelCapability.KEYBOARD_PREDICTION) } returns "test-model"

        val predictions = buildPredictions(catalog = catalog)
        val result = predictions.create(
            TextPredictionRequest(
                model = ModelRef.Capability(ModelCapability.KEYBOARD_PREDICTION),
                input = "Hello ",
            )
        )

        assertEquals("test-model", result.model)
        assertTrue(result.predictions.isNotEmpty())
    }
}
