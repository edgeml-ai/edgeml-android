package ai.octomil.runtime.engines.tflite

import ai.octomil.chat.GenerateConfig
import ai.octomil.chat.LLMRuntime
import ai.octomil.generated.MessageRole
import ai.octomil.runtime.core.GenerationConfig
import ai.octomil.runtime.core.RuntimeContentPart
import ai.octomil.runtime.core.RuntimeMessage
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.planner.CachedBenchmark
import ai.octomil.runtime.planner.FakePlannerPrefs
import ai.octomil.runtime.planner.RuntimePlanner
import ai.octomil.runtime.planner.RuntimePlannerStore
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMRuntimeAdapterTest {

    @Test
    fun `run collects all tokens and returns response`() = runTest {
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                emit("Hello")
                emit(" world")
            }
            override fun close() {}
        }

        val adapter = LLMRuntimeAdapter(llm)
        val response = adapter.run(stubRequest("test"))

        assertEquals("Hello world", response.text)
        assertEquals("stop", response.finishReason)
        assertNotNull(response.usage)
        assertEquals(2, response.usage!!.completionTokens)
    }

    @Test
    fun `stream emits chunks for each token`() = runTest {
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                emit("token1")
                emit("token2")
                emit("token3")
            }
            override fun close() {}
        }

        val adapter = LLMRuntimeAdapter(llm)
        val chunks = adapter.stream(stubRequest("test")).toList()

        assertEquals(3, chunks.size)
        assertEquals("token1", chunks[0].text)
        assertEquals("token2", chunks[1].text)
        assertEquals("token3", chunks[2].text)
    }

    @Test
    fun `run passes config to LLMRuntime`() = runTest {
        var capturedConfig: GenerateConfig? = null
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                capturedConfig = config
                emit("ok")
            }
            override fun close() {}
        }

        val adapter = LLMRuntimeAdapter(llm)
        adapter.run(RuntimeRequest(
            messages = listOf(RuntimeMessage(role = MessageRole.USER, parts = listOf(RuntimeContentPart.Text("test")))),
            generationConfig = GenerationConfig(
                maxTokens = 100,
                temperature = 0.5f,
                topP = 0.9f,
                stop = listOf("END"),
            ),
        ))

        assertNotNull(capturedConfig)
        assertEquals(100, capturedConfig!!.maxTokens)
        assertEquals(0.5f, capturedConfig!!.temperature)
        assertEquals(0.9f, capturedConfig!!.topP)
        assertEquals(listOf("END"), capturedConfig!!.stop)
    }

    @Test
    fun `close delegates to LLMRuntime`() {
        var closed = false
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {}
            override fun close() { closed = true }
        }

        val adapter = LLMRuntimeAdapter(llm)
        adapter.close()
        assert(closed)
    }

    // =========================================================================
    // Real benchmark recording
    // =========================================================================

    @Test
    fun `run records benchmark when planner is wired`() = runTest {
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                emit("Hello")
                emit(" world")
            }
            override fun close() {}
        }

        val context: Context = mockk(relaxed = true)
        val prefs = FakePlannerPrefs()
        val store = RuntimePlannerStore(prefs)
        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
            profileCollector = {
                ai.octomil.runtime.planner.DeviceRuntimeProfile(
                    sdkVersion = "1.0.0",
                    arch = "arm64-v8a",
                )
            },
        )

        val adapter = LLMRuntimeAdapter(llm).also {
            it.planner = planner
            it.modelName = "test-model"
            it.engineId = "llama.cpp"
        }

        adapter.run(stubRequest("test"))

        // Verify benchmark was recorded in the planner store
        val cacheKey = RuntimePlannerStore.makeCacheKey(
            model = "test-model",
            capability = "text",
            policy = "local_first",
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
        )
        val cached = store.getBenchmark(cacheKey)
        assertNotNull(cached)
        assertEquals("test-model", cached!!.model)
        assertEquals("llama.cpp", cached.engine)
        assertTrue(cached.tokensPerSecond > 0)
    }

    @Test
    fun `run does not record benchmark without planner`() = runTest {
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                emit("ok")
            }
            override fun close() {}
        }

        val adapter = LLMRuntimeAdapter(llm)
        // planner is null by default -- should not throw or crash
        adapter.run(stubRequest("test"))
    }

    @Test
    fun `run does not record benchmark without model name`() = runTest {
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                emit("ok")
            }
            override fun close() {}
        }

        val context: Context = mockk(relaxed = true)
        val prefs = FakePlannerPrefs()
        val store = RuntimePlannerStore(prefs)
        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
        )

        val adapter = LLMRuntimeAdapter(llm).also {
            it.planner = planner
            // modelName is null -- should skip recording
        }

        adapter.run(stubRequest("test"))
        // No crash, no benchmark recorded
    }

    // =========================================================================
    // recordBenchmarkResult companion
    // =========================================================================

    @Test
    fun `recordBenchmarkResult converts BenchmarkResult to planner cache entry`() {
        val context: Context = mockk(relaxed = true)
        val prefs = FakePlannerPrefs()
        val store = RuntimePlannerStore(prefs)
        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
            profileCollector = {
                ai.octomil.runtime.planner.DeviceRuntimeProfile(
                    sdkVersion = "1.0.0",
                    arch = "arm64-v8a",
                )
            },
        )

        val result = BenchmarkResult(
            engineName = "llama_cpp",
            tokensPerSecond = 25.0,
            ttftMs = 150.0,
            memoryMb = 512.0,
        )

        LLMRuntimeAdapter.recordBenchmarkResult(
            planner = planner,
            result = result,
            modelName = "phi-4-mini",
        )

        val cacheKey = RuntimePlannerStore.makeCacheKey(
            model = "phi-4-mini",
            capability = "text",
            policy = "local_first",
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
        )
        val cached = store.getBenchmark(cacheKey)
        assertNotNull(cached)
        assertEquals("phi-4-mini", cached!!.model)
        assertEquals("llama.cpp", cached.engine)
        assertEquals(25.0, cached.tokensPerSecond, 0.01)
    }

    @Test
    fun `recordBenchmarkResult skips errored results`() {
        val context: Context = mockk(relaxed = true)
        val prefs = FakePlannerPrefs()
        val store = RuntimePlannerStore(prefs)
        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
            profileCollector = {
                ai.octomil.runtime.planner.DeviceRuntimeProfile(
                    sdkVersion = "1.0.0",
                    arch = "arm64-v8a",
                )
            },
        )

        val result = BenchmarkResult(
            engineName = "tflite",
            tokensPerSecond = 0.0,
            ttftMs = 0.0,
            memoryMb = 0.0,
            error = "model load failed",
        )

        LLMRuntimeAdapter.recordBenchmarkResult(
            planner = planner,
            result = result,
            modelName = "test-model",
        )

        // No benchmark should be stored for failed results
        val cacheKey = RuntimePlannerStore.makeCacheKey(
            model = "test-model",
            capability = "text",
            policy = "local_first",
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
        )
        val cached = store.getBenchmark(cacheKey)
        // Result had error, so recordBenchmarkResult should have returned early
        kotlin.test.assertNull(cached)
    }

    private fun stubRequest(text: String) = RuntimeRequest(
        messages = listOf(RuntimeMessage(role = MessageRole.USER, parts = listOf(RuntimeContentPart.Text(text)))),
    )
}
