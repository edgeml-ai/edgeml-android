package ai.octomil

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ModelResolver] built-in resolvers and chaining.
 */
class ModelResolverTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)

        filesDir = File(System.getProperty("java.io.tmpdir"), "octomil_resolver_test_files_${System.nanoTime()}")
        filesDir.mkdirs()

        cacheDir = File(System.getProperty("java.io.tmpdir"), "octomil_resolver_test_cache_${System.nanoTime()}")
        cacheDir.mkdirs()

        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    // =========================================================================
    // paired() resolver
    // =========================================================================

    @Test
    fun `paired resolver finds model in latest version directory`() = runTest {
        // Set up: filesDir/octomil_models/mobilenet-v2/1.0.0/model.tflite
        val modelDir = File(filesDir, "octomil_models/mobilenet-v2/1.0.0")
        modelDir.mkdirs()
        val modelFile = File(modelDir, "model.tflite")
        modelFile.writeText("fake-model")

        val result = ModelResolver.paired().resolve(context, "mobilenet-v2")

        assertNotNull(result)
        assertEquals("model.tflite", result.name)
        assertTrue(result.exists())
    }

    @Test
    fun `paired resolver picks latest version when multiple exist`() = runTest {
        // Set up two versions
        val v1Dir = File(filesDir, "octomil_models/my-model/1.0.0")
        v1Dir.mkdirs()
        File(v1Dir, "model_v1.tflite").writeText("old")

        val v2Dir = File(filesDir, "octomil_models/my-model/2.0.0")
        v2Dir.mkdirs()
        File(v2Dir, "model_v2.tflite").writeText("new")

        val result = ModelResolver.paired().resolve(context, "my-model")

        assertNotNull(result)
        assertEquals("model_v2.tflite", result.name)
    }

    @Test
    fun `paired resolver returns null when model not found`() = runTest {
        val result = ModelResolver.paired().resolve(context, "nonexistent")
        assertNull(result)
    }

    @Test
    fun `paired resolver returns null when directory exists but is empty`() = runTest {
        val modelDir = File(filesDir, "octomil_models/empty-model/1.0.0")
        modelDir.mkdirs()
        // No files in the directory

        val result = ModelResolver.paired().resolve(context, "empty-model")
        assertNull(result)
    }

    // =========================================================================
    // cache() resolver
    // =========================================================================

    @Test
    fun `cache resolver finds model in cache directory`() = runTest {
        val modelsCache = File(cacheDir, "octomil_models")
        modelsCache.mkdirs()
        val modelFile = File(modelsCache, "mobilenet-v2_1.0.0.tflite")
        modelFile.writeText("cached-model")

        val result = ModelResolver.cache().resolve(context, "mobilenet-v2")

        assertNotNull(result)
        assertTrue(result.name.startsWith("mobilenet-v2"))
    }

    @Test
    fun `cache resolver returns null when cache empty`() = runTest {
        val result = ModelResolver.cache().resolve(context, "nonexistent")
        assertNull(result)
    }

    @Test
    fun `cache resolver returns most recently modified file`() = runTest {
        val modelsCache = File(cacheDir, "octomil_models")
        modelsCache.mkdirs()

        val oldFile = File(modelsCache, "mymodel_v1.tflite")
        oldFile.writeText("old")
        oldFile.setLastModified(1000L)

        val newFile = File(modelsCache, "mymodel_v2.tflite")
        newFile.writeText("new")
        newFile.setLastModified(2000L)

        val result = ModelResolver.cache().resolve(context, "mymodel")

        assertNotNull(result)
        assertEquals("mymodel_v2.tflite", result.name)
    }

    // =========================================================================
    // chain()
    // =========================================================================

    @Test
    fun `chain returns first resolver match`() = runTest {
        val firstFile = File(filesDir, "first.tflite").apply { writeText("first") }
        val secondFile = File(filesDir, "second.tflite").apply { writeText("second") }

        val resolver1 = ModelResolver { _, _ -> firstFile }
        val resolver2 = ModelResolver { _, _ -> secondFile }

        val result = ModelResolver.chain(resolver1, resolver2).resolve(context, "test")

        assertNotNull(result)
        assertEquals("first.tflite", result.name)
    }

    @Test
    fun `chain skips null resolvers and returns first match`() = runTest {
        val modelFile = File(filesDir, "found.tflite").apply { writeText("found") }

        val nullResolver = ModelResolver { _, _ -> null }
        val foundResolver = ModelResolver { _, _ -> modelFile }

        val result = ModelResolver.chain(nullResolver, foundResolver).resolve(context, "test")

        assertNotNull(result)
        assertEquals("found.tflite", result.name)
    }

    @Test
    fun `chain returns null when all resolvers return null`() = runTest {
        val resolver1 = ModelResolver { _, _ -> null }
        val resolver2 = ModelResolver { _, _ -> null }

        val result = ModelResolver.chain(resolver1, resolver2).resolve(context, "test")
        assertNull(result)
    }

    // =========================================================================
    // ModelNotFoundException
    // =========================================================================

    @Test
    fun `ModelNotFoundException has actionable message`() {
        val ex = ModelNotFoundException("phi-4-mini")

        assertTrue(ex.message!!.contains("phi-4-mini"))
        assertTrue(ex.message!!.contains("octomil deploy"))
        assertTrue(ex.message!!.contains("assets"))
        assertEquals("phi-4-mini", ex.modelName)
    }

    // =========================================================================
    // Custom resolver
    // =========================================================================

    @Test
    fun `custom resolver is called`() = runTest {
        val customFile = File(filesDir, "custom.tflite").apply { writeText("custom") }
        val customResolver = ModelResolver { _, name ->
            if (name == "special") customFile else null
        }

        val found = customResolver.resolve(context, "special")
        assertNotNull(found)
        assertEquals("custom.tflite", found.name)

        val notFound = customResolver.resolve(context, "other")
        assertNull(notFound)
    }

    @Test
    fun `default resolver uses paired then assets then cache order`() = runTest {
        // Only set up a cache hit — paired and assets should miss
        val modelsCache = File(cacheDir, "octomil_models")
        modelsCache.mkdirs()
        File(modelsCache, "fallback-model.tflite").writeText("from-cache")

        val result = ModelResolver.default().resolve(context, "fallback-model")

        assertNotNull(result)
        assertEquals("fallback-model.tflite", result.name)
    }
}
