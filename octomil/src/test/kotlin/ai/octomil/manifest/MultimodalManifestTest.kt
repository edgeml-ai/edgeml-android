package ai.octomil.manifest

import ai.octomil.generated.ArtifactResourceKind
import ai.octomil.generated.DeliveryMode
import ai.octomil.generated.Modality
import ai.octomil.generated.ModelCapability
import ai.octomil.generated.RoutingPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for multimodal manifest entries: VL models, audio models,
 * resource bindings, engine config, and multi-resource packages.
 */
class MultimodalManifestTest {

    // =========================================================================
    // VL (Vision-Language) model entries
    // =========================================================================

    @Test
    fun `VL model has text and image input modalities`() {
        val entry = vlEntry()
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), entry.inputModalities)
        assertEquals(listOf(Modality.TEXT), entry.outputModalities)
    }

    @Test
    fun `VL model isVisionModel returns true`() {
        assertTrue(vlEntry().isVisionModel)
    }

    @Test
    fun `VL model isMultimodal returns true`() {
        assertTrue(vlEntry().isMultimodal)
    }

    @Test
    fun `VL model isAudioModel returns false`() {
        assertFalse(vlEntry().isAudioModel)
    }

    @Test
    fun `VL model uses CHAT capability not a special vision capability`() {
        assertEquals(ModelCapability.CHAT, vlEntry().capability)
    }

    @Test
    fun `VL model has weights and projector resources`() {
        val entry = vlEntry()
        assertNotNull(entry.resourceForKind(ArtifactResourceKind.WEIGHTS))
        assertNotNull(entry.resourceForKind(ArtifactResourceKind.PROJECTOR))
    }

    @Test
    fun `VL model weights resource has correct URI`() {
        val weights = vlEntry().resourceForKind(ArtifactResourceKind.WEIGHTS)
        assertNotNull(weights)
        assertEquals("hf://smolvlm2-256m-instruct-Q8_0.gguf", weights.uri)
    }

    @Test
    fun `VL model projector resource has correct URI`() {
        val projector = vlEntry().resourceForKind(ArtifactResourceKind.PROJECTOR)
        assertNotNull(projector)
        assertEquals("hf://smolvlm2-256m-mmproj-f16.gguf", projector.uri)
    }

    // =========================================================================
    // Audio model entries
    // =========================================================================

    @Test
    fun `audio model has audio input and text output`() {
        val entry = audioEntry()
        assertEquals(listOf(Modality.AUDIO), entry.inputModalities)
        assertEquals(listOf(Modality.TEXT), entry.outputModalities)
    }

    @Test
    fun `audio model isAudioModel returns true`() {
        assertTrue(audioEntry().isAudioModel)
    }

    @Test
    fun `audio model isMultimodal returns true for non-text input`() {
        assertTrue(audioEntry().isMultimodal)
    }

    @Test
    fun `audio model isVisionModel returns false`() {
        assertFalse(audioEntry().isVisionModel)
    }

    // =========================================================================
    // Text-only model entries
    // =========================================================================

    @Test
    fun `text-only model has text input and text output`() {
        val entry = textEntry()
        assertEquals(listOf(Modality.TEXT), entry.inputModalities)
        assertEquals(listOf(Modality.TEXT), entry.outputModalities)
    }

    @Test
    fun `text-only model isVisionModel returns false`() {
        assertFalse(textEntry().isVisionModel)
    }

    @Test
    fun `text-only model isAudioModel returns false`() {
        assertFalse(textEntry().isAudioModel)
    }

    @Test
    fun `text-only model isMultimodal returns false`() {
        assertFalse(textEntry().isMultimodal)
    }

    // =========================================================================
    // Resource bindings
    // =========================================================================

    @Test
    fun `resourceForKind returns null for missing kind`() {
        val entry = textEntry()
        assertNull(entry.resourceForKind(ArtifactResourceKind.PROJECTOR))
    }

    @Test
    fun `resourcesForKind returns empty for missing kind`() {
        val entry = textEntry()
        assertTrue(entry.resourcesForKind(ArtifactResourceKind.PROJECTOR).isEmpty())
    }

    @Test
    fun `resourcesForKind returns all matching resources`() {
        val entry = AppModelEntry(
            id = "sharded-model",
            capability = ModelCapability.CHAT,
            delivery = DeliveryMode.MANAGED,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            resources = listOf(
                ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://shard-00001.gguf"),
                ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://shard-00002.gguf"),
                ManifestResource(kind = ArtifactResourceKind.TOKENIZER, uri = "hf://tokenizer.json"),
            ),
        )
        val weights = entry.resourcesForKind(ArtifactResourceKind.WEIGHTS)
        assertEquals(2, weights.size)
    }

    @Test
    fun `resource with path and checksum`() {
        val resource = ManifestResource(
            kind = ArtifactResourceKind.WEIGHTS,
            uri = "hf://model.gguf",
            path = "models/model.gguf",
            sizeBytes = 256_000_000L,
            checksumSha256 = "abc123def456",
            required = true,
            loadOrder = 0,
        )
        assertEquals("models/model.gguf", resource.path)
        assertEquals(256_000_000L, resource.sizeBytes)
        assertEquals("abc123def456", resource.checksumSha256)
        assertTrue(resource.required)
        assertEquals(0, resource.loadOrder)
    }

    @Test
    fun `resource defaults`() {
        val resource = ManifestResource(
            kind = ArtifactResourceKind.WEIGHTS,
            uri = "hf://model.gguf",
        )
        assertNull(resource.path)
        assertNull(resource.sizeBytes)
        assertNull(resource.checksumSha256)
        assertTrue(resource.required)
        assertNull(resource.loadOrder)
    }

    // =========================================================================
    // Engine config
    // =========================================================================

    @Test
    fun `engine config carries llamacpp hints`() {
        val entry = textEntry().copy(
            engineConfig = mapOf(
                "n_gpu_layers" to "99",
                "ctx_size" to "4096",
                "flash_attn" to "true",
            ),
        )
        assertEquals("99", entry.engineConfig["n_gpu_layers"])
        assertEquals("4096", entry.engineConfig["ctx_size"])
        assertEquals("true", entry.engineConfig["flash_attn"])
    }

    @Test
    fun `engine config defaults to empty`() {
        assertTrue(textEntry().engineConfig.isEmpty())
    }

    // =========================================================================
    // Multi-resource packages
    // =========================================================================

    @Test
    fun `multi-resource package preserves resource order`() {
        val entry = vlEntry()
        val resources = entry.resources
        assertEquals(ArtifactResourceKind.WEIGHTS, resources[0].kind)
        assertEquals(ArtifactResourceKind.PROJECTOR, resources[1].kind)
    }

    @Test
    fun `multi-resource package with tokenizer and config`() {
        val entry = AppModelEntry(
            id = "full-package",
            capability = ModelCapability.CHAT,
            delivery = DeliveryMode.MANAGED,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            resources = listOf(
                ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://model.gguf", loadOrder = 0),
                ManifestResource(kind = ArtifactResourceKind.TOKENIZER, uri = "hf://tokenizer.json", loadOrder = 1),
                ManifestResource(kind = ArtifactResourceKind.MODEL_CONFIG, uri = "hf://config.json", loadOrder = 2),
                ManifestResource(kind = ArtifactResourceKind.GENERATION_CONFIG, uri = "hf://gen_config.json", loadOrder = 3),
            ),
        )
        assertEquals(4, entry.resources.size)
        assertNotNull(entry.resourceForKind(ArtifactResourceKind.TOKENIZER))
        assertNotNull(entry.resourceForKind(ArtifactResourceKind.MODEL_CONFIG))
        assertNotNull(entry.resourceForKind(ArtifactResourceKind.GENERATION_CONFIG))
    }

    // =========================================================================
    // Manifest-level queries with multimodal entries
    // =========================================================================

    @Test
    fun `manifest entryFor finds VL model via CHAT capability`() {
        val manifest = AppManifest(models = listOf(vlEntry()))
        val entry = manifest.entryFor(ModelCapability.CHAT)
        assertNotNull(entry)
        assertTrue(entry.isVisionModel)
    }

    @Test
    fun `manifest entriesFor returns both text and VL chat models`() {
        val manifest = AppManifest(models = listOf(textEntry(), vlEntry()))
        val chatModels = manifest.entriesFor(ModelCapability.CHAT)
        assertEquals(2, chatModels.size)
    }

    @Test
    fun `manifest entriesFor separates capabilities correctly`() {
        val manifest = AppManifest(
            models = listOf(textEntry(), vlEntry(), audioEntry()),
        )
        assertEquals(2, manifest.entriesFor(ModelCapability.CHAT).size)
        assertEquals(1, manifest.entriesFor(ModelCapability.TRANSCRIPTION).size)
    }

    // =========================================================================
    // Routing policy inference with multimodal entries
    // =========================================================================

    @Test
    fun `VL managed model infers LOCAL_FIRST routing`() {
        assertEquals(RoutingPolicy.LOCAL_FIRST, vlEntry().effectiveRoutingPolicy)
    }

    @Test
    fun `audio bundled model infers LOCAL_ONLY routing`() {
        assertEquals(RoutingPolicy.LOCAL_ONLY, audioEntry().effectiveRoutingPolicy)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun textEntry() = AppModelEntry(
        id = "phi-4-mini",
        capability = ModelCapability.CHAT,
        delivery = DeliveryMode.MANAGED,
        inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.TEXT),
        resources = listOf(
            ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://phi-4-mini-Q4_K_M.gguf"),
        ),
    )

    private fun vlEntry() = AppModelEntry(
        id = "smolvlm2-256m",
        capability = ModelCapability.CHAT,
        delivery = DeliveryMode.MANAGED,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.TEXT),
        resources = listOf(
            ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://smolvlm2-256m-instruct-Q8_0.gguf"),
            ManifestResource(kind = ArtifactResourceKind.PROJECTOR, uri = "hf://smolvlm2-256m-mmproj-f16.gguf"),
        ),
    )

    private fun audioEntry() = AppModelEntry(
        id = "whisper-small",
        capability = ModelCapability.TRANSCRIPTION,
        delivery = DeliveryMode.BUNDLED,
        inputModalities = listOf(Modality.AUDIO),
        outputModalities = listOf(Modality.TEXT),
        bundledPath = "whisper-small.bin",
        resources = listOf(
            ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "file://whisper-small.bin"),
        ),
    )
}
