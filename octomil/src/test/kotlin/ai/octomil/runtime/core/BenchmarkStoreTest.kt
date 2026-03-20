package ai.octomil.runtime.core

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BenchmarkStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: BenchmarkStore

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = BenchmarkStore(prefs, deviceClass = "test_device", sdkVersion = "1.0.0")
    }

    // =========================================================================
    // record + winner round-trip
    // =========================================================================

    @Test
    fun `record and winner round-trip with artifactDigest`() {
        store.record(
            winner = Engine.LLAMA_CPP,
            modelId = "whisper-tiny",
            artifactDigest = "abc123",
        )
        val result = store.winner(
            modelId = "whisper-tiny",
            artifactDigest = "abc123",
        )
        assertEquals(Engine.LLAMA_CPP, result)
    }

    @Test
    fun `record and winner round-trip with modelVersion`() {
        store.record(
            winner = Engine.TFLITE,
            modelId = "llama-3.2-1b",
            modelVersion = "2.0.0",
        )
        val result = store.winner(
            modelId = "llama-3.2-1b",
            modelVersion = "2.0.0",
        )
        assertEquals(Engine.TFLITE, result)
    }

    @Test
    fun `record and winner round-trip with path fallback`() {
        store.record(
            winner = Engine.TFLITE,
            modelId = "test-model",
            modelFilePath = "/data/models/test-model.tflite",
        )
        val result = store.winner(
            modelId = "test-model",
            modelFilePath = "/data/models/test-model.tflite",
        )
        assertEquals(Engine.TFLITE, result)
    }

    // =========================================================================
    // Missing data returns null
    // =========================================================================

    @Test
    fun `winner returns null when no record exists`() {
        val result = store.winner(modelId = "no-such-model")
        assertNull(result)
    }

    @Test
    fun `winner returns null when key components differ`() {
        store.record(
            winner = Engine.TFLITE,
            modelId = "test-model",
            modelVersion = "1.0",
        )
        // Different version -> different key
        val result = store.winner(
            modelId = "test-model",
            modelVersion = "2.0",
        )
        assertNull(result)
    }

    @Test
    fun `winner returns null when model ID differs`() {
        store.record(
            winner = Engine.TFLITE,
            modelId = "model-a",
            modelVersion = "1.0",
        )
        val result = store.winner(
            modelId = "model-b",
            modelVersion = "1.0",
        )
        assertNull(result)
    }

    // =========================================================================
    // Identity hierarchy
    // =========================================================================

    @Test
    fun `artifactDigest takes priority over modelVersion`() {
        // Record with digest
        store.record(
            winner = Engine.LLAMA_CPP,
            modelId = "test-model",
            modelVersion = "1.0",
            artifactDigest = "sha256abc",
        )
        // Retrieve with same digest but different version
        val result = store.winner(
            modelId = "test-model",
            modelVersion = "2.0",
            artifactDigest = "sha256abc",
        )
        assertEquals(Engine.LLAMA_CPP, result)
    }

    @Test
    fun `modelVersion takes priority over path`() {
        // Record with version
        store.record(
            winner = Engine.TFLITE,
            modelId = "test-model",
            modelFilePath = "/path/a",
            modelVersion = "1.0",
        )
        // Retrieve with same version but different path
        val result = store.winner(
            modelId = "test-model",
            modelFilePath = "/path/b",
            modelVersion = "1.0",
        )
        assertEquals(Engine.TFLITE, result)
    }

    // =========================================================================
    // clearAll
    // =========================================================================

    @Test
    fun `clearAll removes all benchmark entries`() {
        store.record(winner = Engine.TFLITE, modelId = "a", modelVersion = "1.0")
        store.record(winner = Engine.LLAMA_CPP, modelId = "b", modelVersion = "1.0")

        store.clearAll()

        assertNull(store.winner(modelId = "a", modelVersion = "1.0"))
        assertNull(store.winner(modelId = "b", modelVersion = "1.0"))
    }

    @Test
    fun `clearAll does not affect non-benchmark prefs keys`() {
        prefs.edit().putString("other_key", "value").apply()
        store.record(winner = Engine.TFLITE, modelId = "test", modelVersion = "1.0")

        store.clearAll()

        // Benchmark cleared
        assertNull(store.winner(modelId = "test", modelVersion = "1.0"))
        // Other key preserved
        assertEquals("value", prefs.getString("other_key", null))
    }

    // =========================================================================
    // Device/SDK differentiation
    // =========================================================================

    @Test
    fun `different device class produces different key`() {
        val storeA = BenchmarkStore(prefs, deviceClass = "device_a", sdkVersion = "1.0.0")
        val storeB = BenchmarkStore(prefs, deviceClass = "device_b", sdkVersion = "1.0.0")

        storeA.record(winner = Engine.TFLITE, modelId = "test", modelVersion = "1.0")

        // Different device class -> no match
        assertNull(storeB.winner(modelId = "test", modelVersion = "1.0"))
    }

    @Test
    fun `different SDK version produces different key`() {
        val storeA = BenchmarkStore(prefs, deviceClass = "device", sdkVersion = "1.0.0")
        val storeB = BenchmarkStore(prefs, deviceClass = "device", sdkVersion = "2.0.0")

        storeA.record(winner = Engine.TFLITE, modelId = "test", modelVersion = "1.0")

        // Different SDK version -> no match
        assertNull(storeB.winner(modelId = "test", modelVersion = "1.0"))
    }

    // =========================================================================
    // Key structure
    // =========================================================================

    @Test
    fun `storeKey with digest uses d prefix`() {
        val key = store.storeKey(
            modelId = "test",
            artifactDigest = "abc123",
        )
        assertTrue(key.contains("d:abc123"))
        assertTrue(key.startsWith("octomil_bm_"))
    }

    @Test
    fun `storeKey with version uses v prefix`() {
        val key = store.storeKey(
            modelId = "test",
            modelVersion = "1.0.0",
        )
        assertTrue(key.contains("v:1.0.0"))
    }

    @Test
    fun `storeKey with path uses p prefix`() {
        val key = store.storeKey(
            modelId = "test",
            modelFilePath = "/data/models/model.gguf",
        )
        assertTrue(key.contains("p:models/model.gguf"))
    }

    // =========================================================================
    // canonicalArtifactPath
    // =========================================================================

    @Test
    fun `canonicalArtifactPath returns last two segments`() {
        val path = BenchmarkStore.canonicalArtifactPath("/data/app/models/whisper-tiny.bin")
        assertEquals("models/whisper-tiny.bin", path)
    }

    @Test
    fun `canonicalArtifactPath returns single segment when only one exists`() {
        val path = BenchmarkStore.canonicalArtifactPath("/model.bin")
        assertEquals("model.bin", path)
    }

    @Test
    fun `canonicalArtifactPath returns unknown for null`() {
        val path = BenchmarkStore.canonicalArtifactPath(null)
        assertEquals("unknown", path)
    }

    // =========================================================================
    // Artifact digest computation
    // =========================================================================

    @Test
    fun `artifactDigest returns null for nonexistent path`() {
        assertNull(BenchmarkStore.artifactDigest("/nonexistent/path"))
    }

    @Test
    fun `artifactDigest computes digest for single file`() {
        val file = tempFolder.newFile("test.bin")
        file.writeText("hello world")
        val digest = BenchmarkStore.artifactDigest(file.absolutePath)
        assertNotNull(digest)
        assertEquals(64, digest.length) // SHA-256 hex string
    }

    @Test
    fun `artifactDigest is deterministic for same content`() {
        val file = tempFolder.newFile("test.bin")
        file.writeText("deterministic content")
        val d1 = BenchmarkStore.artifactDigest(file.absolutePath)
        val d2 = BenchmarkStore.artifactDigest(file.absolutePath)
        assertEquals(d1, d2)
    }

    @Test
    fun `artifactDigest differs for different content`() {
        val f1 = tempFolder.newFile("a.bin")
        f1.writeText("content A")
        val f2 = tempFolder.newFile("b.bin")
        f2.writeText("content B")
        val d1 = BenchmarkStore.artifactDigest(f1.absolutePath)
        val d2 = BenchmarkStore.artifactDigest(f2.absolutePath)
        assertNotEquals(d1, d2)
    }

    @Test
    fun `artifactDigest computes manifest digest for directory`() {
        val dir = tempFolder.newFolder("model_dir")
        File(dir, "weights.bin").writeText("weights data")
        File(dir, "config.json").writeText("{\"version\": 1}")
        val digest = BenchmarkStore.artifactDigest(dir.absolutePath)
        assertNotNull(digest)
        assertEquals(64, digest.length)
    }

    @Test
    fun `artifactDigest for directory is deterministic`() {
        val dir = tempFolder.newFolder("model_dir2")
        File(dir, "a.bin").writeText("data A")
        File(dir, "b.bin").writeText("data B")
        val d1 = BenchmarkStore.artifactDigest(dir.absolutePath)
        val d2 = BenchmarkStore.artifactDigest(dir.absolutePath)
        assertEquals(d1, d2)
    }

    @Test
    fun `artifactDigest for directory changes when file added`() {
        val dir = tempFolder.newFolder("model_dir3")
        File(dir, "a.bin").writeText("data A")
        val d1 = BenchmarkStore.artifactDigest(dir.absolutePath)

        File(dir, "b.bin").writeText("data B")
        val d2 = BenchmarkStore.artifactDigest(dir.absolutePath)

        assertNotEquals(d1, d2)
    }

    // =========================================================================
    // Engine.fromWireValue
    // =========================================================================

    @Test
    fun `Engine fromWireValue parses known values`() {
        assertEquals(Engine.TFLITE, Engine.fromWireValue("tflite"))
        assertEquals(Engine.LLAMA_CPP, Engine.fromWireValue("llama_cpp"))
        assertEquals(Engine.AUTO, Engine.fromWireValue("auto"))
    }

    @Test
    fun `Engine fromWireValue returns null for unknown value`() {
        assertNull(Engine.fromWireValue("unknown_engine"))
    }

    @Test
    fun `Engine wireValue round-trips through fromWireValue`() {
        for (engine in Engine.entries) {
            assertEquals(engine, Engine.fromWireValue(engine.wireValue))
        }
    }
}
