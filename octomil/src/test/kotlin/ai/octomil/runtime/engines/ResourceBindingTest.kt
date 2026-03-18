package ai.octomil.runtime.engines

import ai.octomil.errors.OctomilException
import ai.octomil.generated.ArtifactResourceKind
import ai.octomil.manifest.ManifestResource
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [LocalFileModelRuntime] resource binding resolution,
 * engine config pass-through, and multi-resource constructors.
 */
class ResourceBindingTest {

    private val packageDir = File("/tmp/test-package")

    // =========================================================================
    // Primary constructor with explicit bindings
    // =========================================================================

    @Test
    fun `fileForKind returns bound file`() {
        val weightsFile = File(packageDir, "model.gguf")
        val projectorFile = File(packageDir, "mmproj.gguf")
        val bindings = mapOf(
            ArtifactResourceKind.WEIGHTS to weightsFile,
            ArtifactResourceKind.PROJECTOR to projectorFile,
        )

        val runtime = LocalFileModelRuntime(
            modelFile = weightsFile,
            resourceBindings = bindings,
        )

        assertEquals(weightsFile, runtime.fileForKind(ArtifactResourceKind.WEIGHTS))
        assertEquals(projectorFile, runtime.fileForKind(ArtifactResourceKind.PROJECTOR))
    }

    @Test
    fun `fileForKind returns null for unbound kind`() {
        val weightsFile = File(packageDir, "model.gguf")
        val runtime = LocalFileModelRuntime(
            modelFile = weightsFile,
            resourceBindings = mapOf(ArtifactResourceKind.WEIGHTS to weightsFile),
        )

        assertNull(runtime.fileForKind(ArtifactResourceKind.PROJECTOR))
        assertNull(runtime.fileForKind(ArtifactResourceKind.TOKENIZER))
    }

    @Test
    fun `engineConfig returns empty map by default`() {
        val runtime = LocalFileModelRuntime(
            modelFile = File(packageDir, "model.gguf"),
        )
        assertTrue(runtime.engineConfig().isEmpty())
    }

    @Test
    fun `engineConfig returns provided config`() {
        val config = mapOf(
            "n_gpu_layers" to "99",
            "ctx_size" to "4096",
            "flash_attn" to "true",
        )
        val runtime = LocalFileModelRuntime(
            modelFile = File(packageDir, "model.gguf"),
            engineConfig = config,
        )

        assertEquals("99", runtime.engineConfig()["n_gpu_layers"])
        assertEquals("4096", runtime.engineConfig()["ctx_size"])
        assertEquals("true", runtime.engineConfig()["flash_attn"])
        assertEquals(3, runtime.engineConfig().size)
    }

    // =========================================================================
    // Secondary constructor from manifest resources
    // =========================================================================

    @Test
    fun `constructor from resources resolves weights from path`() {
        val resources = listOf(
            ManifestResource(
                kind = ArtifactResourceKind.WEIGHTS,
                uri = "hf://model.gguf",
                path = "model.gguf",
            ),
        )

        val runtime = LocalFileModelRuntime(packageDir, resources)
        val weightsFile = runtime.fileForKind(ArtifactResourceKind.WEIGHTS)
        assertNotNull(weightsFile)
        assertEquals(File(packageDir, "model.gguf"), weightsFile)
    }

    @Test
    fun `constructor from resources resolves weights from URI when path is null`() {
        val resources = listOf(
            ManifestResource(
                kind = ArtifactResourceKind.WEIGHTS,
                uri = "hf://org/repo/model.gguf",
            ),
        )

        val runtime = LocalFileModelRuntime(packageDir, resources)
        val weightsFile = runtime.fileForKind(ArtifactResourceKind.WEIGHTS)
        assertNotNull(weightsFile)
        assertEquals(File(packageDir, "model.gguf"), weightsFile)
    }

    @Test
    fun `constructor from resources resolves multiple resource kinds`() {
        val resources = listOf(
            ManifestResource(
                kind = ArtifactResourceKind.WEIGHTS,
                uri = "hf://model.gguf",
                path = "model.gguf",
                loadOrder = 0,
            ),
            ManifestResource(
                kind = ArtifactResourceKind.PROJECTOR,
                uri = "hf://mmproj.gguf",
                path = "mmproj.gguf",
                loadOrder = 1,
            ),
            ManifestResource(
                kind = ArtifactResourceKind.TOKENIZER,
                uri = "hf://tokenizer.json",
                path = "tokenizer.json",
                loadOrder = 2,
            ),
        )

        val runtime = LocalFileModelRuntime(packageDir, resources)

        assertNotNull(runtime.fileForKind(ArtifactResourceKind.WEIGHTS))
        assertNotNull(runtime.fileForKind(ArtifactResourceKind.PROJECTOR))
        assertNotNull(runtime.fileForKind(ArtifactResourceKind.TOKENIZER))
        assertEquals(File(packageDir, "mmproj.gguf"), runtime.fileForKind(ArtifactResourceKind.PROJECTOR))
    }

    @Test
    fun `constructor from resources passes engine config through`() {
        val resources = listOf(
            ManifestResource(
                kind = ArtifactResourceKind.WEIGHTS,
                uri = "hf://model.gguf",
                path = "model.gguf",
            ),
        )
        val config = mapOf("n_gpu_layers" to "99")

        val runtime = LocalFileModelRuntime(packageDir, resources, config)
        assertEquals("99", runtime.engineConfig()["n_gpu_layers"])
    }

    @Test
    fun `constructor from resources throws when no weights resource present`() {
        val resources = listOf(
            ManifestResource(
                kind = ArtifactResourceKind.TOKENIZER,
                uri = "hf://tokenizer.json",
            ),
        )

        val ex = assertFailsWith<OctomilException> {
            LocalFileModelRuntime(packageDir, resources)
        }
        assertTrue(ex.message!!.contains("No weights resource"))
    }

    @Test
    fun `constructor from resources throws for empty resource list`() {
        assertFailsWith<OctomilException> {
            LocalFileModelRuntime(packageDir, emptyList())
        }
    }

    // =========================================================================
    // Resource binding edge cases
    // =========================================================================

    @Test
    fun `empty resource bindings map is valid`() {
        val runtime = LocalFileModelRuntime(
            modelFile = File(packageDir, "model.gguf"),
            resourceBindings = emptyMap(),
        )
        assertNull(runtime.fileForKind(ArtifactResourceKind.WEIGHTS))
    }

    @Test
    fun `all ArtifactResourceKind values can be bound`() {
        val bindings = ArtifactResourceKind.entries.associateWith { kind ->
            File(packageDir, "${kind.code}.bin")
        }
        val runtime = LocalFileModelRuntime(
            modelFile = File(packageDir, "model.gguf"),
            resourceBindings = bindings,
        )

        ArtifactResourceKind.entries.forEach { kind ->
            assertNotNull(runtime.fileForKind(kind), "Kind $kind should be bound")
        }
    }

    @Test
    fun `resource binding with loadOrder sorts correctly`() {
        val resources = listOf(
            ManifestResource(
                kind = ArtifactResourceKind.PROJECTOR,
                uri = "hf://mmproj.gguf",
                path = "mmproj.gguf",
                loadOrder = 1,
            ),
            ManifestResource(
                kind = ArtifactResourceKind.WEIGHTS,
                uri = "hf://model.gguf",
                path = "model.gguf",
                loadOrder = 0,
            ),
        )

        // Constructor should not fail regardless of input order
        val runtime = LocalFileModelRuntime(packageDir, resources)
        assertNotNull(runtime.fileForKind(ArtifactResourceKind.WEIGHTS))
        assertNotNull(runtime.fileForKind(ArtifactResourceKind.PROJECTOR))
    }
}
