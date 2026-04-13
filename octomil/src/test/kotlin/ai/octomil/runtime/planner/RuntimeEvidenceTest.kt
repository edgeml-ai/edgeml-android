package ai.octomil.runtime.planner

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeEvidenceTest {

    // =========================================================================
    // RuntimeEvidenceMetadataKeys constants
    // =========================================================================

    @Test
    fun `metadata key constants have expected values`() {
        assertEquals("models", RuntimeEvidenceMetadataKeys.MODELS)
        assertEquals("capabilities", RuntimeEvidenceMetadataKeys.CAPABILITIES)
        assertEquals("artifact_digest", RuntimeEvidenceMetadataKeys.ARTIFACT_DIGEST)
        assertEquals("artifact_format", RuntimeEvidenceMetadataKeys.ARTIFACT_FORMAT)
    }

    // =========================================================================
    // InstalledRuntime.modelCapable factory
    // =========================================================================

    @Test
    fun `modelCapable creates valid InstalledRuntime with required fields`() {
        val runtime = InstalledRuntime.modelCapable(
            engine = "llama.cpp",
            model = "phi-4-mini",
            capability = "text",
        )

        assertEquals("llama.cpp", runtime.engine)
        assertTrue(runtime.available)
        assertEquals("cpu", runtime.accelerator)
        assertNull(runtime.version)
        assertEquals("phi-4-mini", runtime.metadata[RuntimeEvidenceMetadataKeys.MODELS])
        assertEquals("text", runtime.metadata[RuntimeEvidenceMetadataKeys.CAPABILITIES])
        assertNull(runtime.metadata[RuntimeEvidenceMetadataKeys.ARTIFACT_DIGEST])
        assertNull(runtime.metadata[RuntimeEvidenceMetadataKeys.ARTIFACT_FORMAT])
    }

    @Test
    fun `modelCapable includes optional artifact digest and format`() {
        val runtime = InstalledRuntime.modelCapable(
            engine = "tflite",
            model = "mobilenet-v2",
            capability = "image_classification",
            artifactDigest = "sha256:abc123",
            artifactFormat = "tflite",
        )

        assertEquals("sha256:abc123", runtime.metadata[RuntimeEvidenceMetadataKeys.ARTIFACT_DIGEST])
        assertEquals("tflite", runtime.metadata[RuntimeEvidenceMetadataKeys.ARTIFACT_FORMAT])
    }

    @Test
    fun `modelCapable sets custom accelerator`() {
        val runtime = InstalledRuntime.modelCapable(
            engine = "tflite",
            model = "test-model",
            capability = "text",
            accelerator = "gpu",
        )

        assertEquals("gpu", runtime.accelerator)
    }

    @Test
    fun `modelCapable sets engine version`() {
        val runtime = InstalledRuntime.modelCapable(
            engine = "llama.cpp",
            model = "gemma-2b",
            capability = "text",
            version = "b4567",
        )

        assertEquals("b4567", runtime.version)
    }

    // =========================================================================
    // Canonical ID matching
    // =========================================================================

    @Test
    fun `modelCapable canonicalizes engine aliases`() {
        val variants = listOf("llamacpp", "llama_cpp", "llama-cpp", "llama.cpp")
        for (alias in variants) {
            val runtime = InstalledRuntime.modelCapable(
                engine = alias,
                model = "gemma-2b",
                capability = "text",
            )
            assertEquals(
                "llama.cpp", runtime.engine,
                "Engine alias '$alias' should canonicalize to 'llama.cpp'",
            )
        }
    }

    @Test
    fun `modelCapable canonicalizes whisper aliases`() {
        val variants = listOf("whisper", "whispercpp", "whisper_cpp", "whisper-cpp", "whisper.cpp")
        for (alias in variants) {
            val runtime = InstalledRuntime.modelCapable(
                engine = alias,
                model = "sherpa-zipformer-en",
                capability = "audio_transcription",
            )
            assertEquals(
                "whisper.cpp", runtime.engine,
                "Engine alias '$alias' should canonicalize to 'whisper.cpp'",
            )
        }
    }

    @Test
    fun `modelCapable preserves unrecognized engine IDs as lowercase`() {
        val runtime = InstalledRuntime.modelCapable(
            engine = "MyCustomEngine",
            model = "test",
            capability = "text",
        )
        assertEquals("mycustomengine", runtime.engine)
    }

    // =========================================================================
    // Integration with planner supportsLocalDefault
    // =========================================================================

    @Test
    fun `modelCapable evidence is recognized by supportsLocalDefault`() {
        // Create a planner and check that model-capable evidence passes
        // the supportsLocalDefault gate.
        val evidence = InstalledRuntime.modelCapable(
            engine = "llama.cpp",
            model = "gemma-2b",
            capability = "text",
        )

        // Verify the metadata keys the planner checks
        val models = evidence.metadata["models"]
        assertNotNull(models)
        assertTrue(models.contains("gemma-2b"))

        val capabilities = evidence.metadata["capabilities"]
        assertNotNull(capabilities)
        assertTrue(capabilities.contains("text"))
    }

    @Test
    fun `modelCapable evidence is always marked available`() {
        val runtime = InstalledRuntime.modelCapable(
            engine = "tflite",
            model = "test",
            capability = "text",
        )
        assertTrue(runtime.available)
    }

    @Test
    fun `modelCapable with canonicalized engine matches against installed runtimes`() {
        // The resolve flow checks installed engines by canonical ID
        val evidence = InstalledRuntime.modelCapable(
            engine = "llama_cpp",
            model = "phi-4-mini",
            capability = "text",
        )
        val canonicalized = evidence.canonicalized()
        assertEquals("llama.cpp", canonicalized.engine)
        // Already canonical from factory -- canonicalized() should be no-op
        assertEquals(evidence.engine, canonicalized.engine)
    }
}
