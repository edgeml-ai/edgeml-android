package ai.octomil.runtime.core

import ai.octomil.generated.Modality
import ai.octomil.runtime.engines.tflite.EngineRegistry
import ai.octomil.runtime.engines.tflite.EngineResolutionException
import ai.octomil.runtime.engines.tflite.InferenceChunk
import ai.octomil.runtime.engines.tflite.StreamingInferenceEngine
import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RuntimeSelectorTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var benchmarkStore: BenchmarkStore

    // Distinguishable test engines
    private val tfliteEngine = object : StreamingInferenceEngine {
        override fun generate(input: Any, modality: Modality) = emptyFlow<InferenceChunk>()
        override fun toString() = "TFLiteTestEngine"
    }
    private val llamaCppEngine = object : StreamingInferenceEngine {
        override fun generate(input: Any, modality: Modality) = emptyFlow<InferenceChunk>()
        override fun toString() = "LlamaCppTestEngine"
    }

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        prefs = FakeSharedPreferences()
        benchmarkStore = BenchmarkStore(prefs, deviceClass = "test_device", sdkVersion = "1.0.0")
        EngineRegistry.reset()
        RuntimeSelector.reset()

        // Register both TFLITE and LLAMA_CPP for TEXT
        EngineRegistry.register(Modality.TEXT, Engine.TFLITE) { _, _ -> tfliteEngine }
        EngineRegistry.register(Modality.TEXT, Engine.LLAMA_CPP) { _, _ -> llamaCppEngine }
    }

    @After
    fun tearDown() {
        EngineRegistry.reset()
        RuntimeSelector.reset()
    }

    // =========================================================================
    // Default resolution (no overrides, no benchmarks)
    // =========================================================================

    @Test
    fun `default resolution uses filename inference for gguf`() {
        val file = File("/data/models/model.gguf")
        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            modelFile = file,
            benchmarkStore = benchmarkStore,
        )
        assertEquals(llamaCppEngine, result)
    }

    @Test
    fun `default resolution uses filename inference for tflite`() {
        val file = File("/data/models/model.tflite")
        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            modelFile = file,
            benchmarkStore = benchmarkStore,
        )
        assertEquals(tfliteEngine, result)
    }

    @Test
    fun `default resolution falls back to modality default for unknown extension`() {
        val file = File("/data/models/model.bin")
        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            modelFile = file,
            benchmarkStore = benchmarkStore,
        )
        // Should fall back to modality default (LLMEngine from registerDefaults)
        assertIs<ai.octomil.runtime.engines.tflite.LLMEngine>(result)
    }

    // =========================================================================
    // Server overrides
    // =========================================================================

    @Test
    fun `server override for specific model takes highest priority`() {
        RuntimeSelector.setServerOverrides(mapOf("test-model" to Engine.LLAMA_CPP))

        // Even with a .tflite file, server override wins
        val file = File("/data/models/model.tflite")
        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            modelFile = file,
            benchmarkStore = benchmarkStore,
        )
        assertEquals(llamaCppEngine, result)
    }

    @Test
    fun `server wildcard override applies to all models`() {
        RuntimeSelector.setServerOverrides(mapOf("*" to Engine.LLAMA_CPP))

        val result = RuntimeSelector.selectEngine(
            modelId = "any-model",
            modality = Modality.TEXT,
            context = context,
            benchmarkStore = benchmarkStore,
        )
        assertEquals(llamaCppEngine, result)
    }

    @Test
    fun `server model-specific override takes priority over wildcard`() {
        RuntimeSelector.setServerOverrides(mapOf(
            "*" to Engine.LLAMA_CPP,
            "special-model" to Engine.TFLITE,
        ))

        val result = RuntimeSelector.selectEngine(
            modelId = "special-model",
            modality = Modality.TEXT,
            context = context,
            benchmarkStore = benchmarkStore,
        )
        assertEquals(tfliteEngine, result)
    }

    // =========================================================================
    // Local overrides
    // =========================================================================

    @Test
    fun `local override for specific model applies`() {
        RuntimeSelector.setLocalOverrides(mapOf("test-model" to Engine.LLAMA_CPP))

        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            benchmarkStore = benchmarkStore,
        )
        assertEquals(llamaCppEngine, result)
    }

    @Test
    fun `local wildcard override applies to all models`() {
        RuntimeSelector.setLocalOverrides(mapOf("*" to Engine.TFLITE))

        val result = RuntimeSelector.selectEngine(
            modelId = "any-model",
            modality = Modality.TEXT,
            context = context,
            benchmarkStore = benchmarkStore,
        )
        assertEquals(tfliteEngine, result)
    }

    @Test
    fun `server override takes priority over local override`() {
        RuntimeSelector.setServerOverrides(mapOf("test-model" to Engine.TFLITE))
        RuntimeSelector.setLocalOverrides(mapOf("test-model" to Engine.LLAMA_CPP))

        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            benchmarkStore = benchmarkStore,
        )
        assertEquals(tfliteEngine, result)
    }

    // =========================================================================
    // Benchmark winner
    // =========================================================================

    @Test
    fun `benchmark winner is used when no overrides set`() {
        benchmarkStore.record(
            winner = Engine.LLAMA_CPP,
            modelId = "test-model",
            modelVersion = "1.0",
        )

        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            modelVersion = "1.0",
            benchmarkStore = benchmarkStore,
        )
        assertEquals(llamaCppEngine, result)
    }

    @Test
    fun `local override takes priority over benchmark winner`() {
        benchmarkStore.record(
            winner = Engine.LLAMA_CPP,
            modelId = "test-model",
            modelVersion = "1.0",
        )
        RuntimeSelector.setLocalOverrides(mapOf("test-model" to Engine.TFLITE))

        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            modelVersion = "1.0",
            benchmarkStore = benchmarkStore,
        )
        assertEquals(tfliteEngine, result)
    }

    // =========================================================================
    // Resolution chain ordering
    // =========================================================================

    @Test
    fun `full resolution chain server then local then benchmark then filename`() {
        // Set up all layers
        val file = File("/data/models/model.tflite")
        benchmarkStore.record(
            winner = Engine.TFLITE,
            modelId = "test-model",
            modelVersion = "1.0",
        )
        RuntimeSelector.setLocalOverrides(mapOf("test-model" to Engine.TFLITE))
        RuntimeSelector.setServerOverrides(mapOf("test-model" to Engine.LLAMA_CPP))

        // Server override should win
        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            modelFile = file,
            modelVersion = "1.0",
            benchmarkStore = benchmarkStore,
        )
        assertEquals(llamaCppEngine, result)
    }

    // =========================================================================
    // Reset
    // =========================================================================

    @Test
    fun `reset clears all overrides`() {
        RuntimeSelector.setServerOverrides(mapOf("test-model" to Engine.LLAMA_CPP))
        RuntimeSelector.setLocalOverrides(mapOf("*" to Engine.LLAMA_CPP))

        RuntimeSelector.reset()

        // Without overrides, should fall back to modality default
        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            benchmarkStore = benchmarkStore,
        )
        assertIs<ai.octomil.runtime.engines.tflite.LLMEngine>(result)
    }

    // =========================================================================
    // No model file
    // =========================================================================

    @Test
    fun `resolves with null modelFile using modality default`() {
        val result = RuntimeSelector.selectEngine(
            modelId = "test-model",
            modality = Modality.TEXT,
            context = context,
            modelFile = null,
            benchmarkStore = benchmarkStore,
        )
        assertIs<ai.octomil.runtime.engines.tflite.LLMEngine>(result)
    }
}

/**
 * Simple in-memory SharedPreferences for testing.
 */
internal class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? =
        map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removals.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean { doApply(); return true }
        override fun apply() { doApply() }

        private fun doApply() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
