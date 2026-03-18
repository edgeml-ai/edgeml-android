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

class AppManifestTest {

    private val chatEntry = AppModelEntry(
        id = "phi-4-mini",
        capability = ModelCapability.CHAT,
        delivery = DeliveryMode.MANAGED,
        inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.TEXT),
    )

    private val predictionEntry = AppModelEntry(
        id = "smollm2-135m",
        capability = ModelCapability.KEYBOARD_PREDICTION,
        delivery = DeliveryMode.MANAGED,
        inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.TEXT),
    )

    private val transcriptionEntry = AppModelEntry(
        id = "whisper-small",
        capability = ModelCapability.TRANSCRIPTION,
        delivery = DeliveryMode.BUNDLED,
        inputModalities = listOf(Modality.AUDIO),
        outputModalities = listOf(Modality.TEXT),
        bundledPath = "whisper-small.bin",
    )

    // =========================================================================
    // entryFor
    // =========================================================================

    @Test
    fun `entryFor returns matching entry`() {
        val manifest = AppManifest(models = listOf(chatEntry, predictionEntry))

        val result = manifest.entryFor(ModelCapability.CHAT)
        assertNotNull(result)
        assertEquals("phi-4-mini", result.id)
    }

    @Test
    fun `entryFor returns first match when multiple entries have same capability`() {
        val second = chatEntry.copy(id = "phi-4-mini-v2")
        val manifest = AppManifest(models = listOf(chatEntry, second))

        val result = manifest.entryFor(ModelCapability.CHAT)
        assertNotNull(result)
        assertEquals("phi-4-mini", result.id)
    }

    @Test
    fun `entryFor returns null when no match`() {
        val manifest = AppManifest(models = listOf(chatEntry))
        assertNull(manifest.entryFor(ModelCapability.TRANSCRIPTION))
    }

    @Test
    fun `entryFor returns null for empty manifest`() {
        val manifest = AppManifest(models = emptyList())
        assertNull(manifest.entryFor(ModelCapability.CHAT))
    }

    // =========================================================================
    // entriesFor
    // =========================================================================

    @Test
    fun `entriesFor returns all matching entries`() {
        val second = chatEntry.copy(id = "phi-4-mini-v2")
        val manifest = AppManifest(models = listOf(chatEntry, predictionEntry, second))

        val results = manifest.entriesFor(ModelCapability.CHAT)
        assertEquals(2, results.size)
        assertEquals("phi-4-mini", results[0].id)
        assertEquals("phi-4-mini-v2", results[1].id)
    }

    @Test
    fun `entriesFor returns empty when no match`() {
        val manifest = AppManifest(models = listOf(chatEntry))
        assertTrue(manifest.entriesFor(ModelCapability.TRANSCRIPTION).isEmpty())
    }

    // =========================================================================
    // AppModelEntry.effectiveRoutingPolicy
    // =========================================================================

    @Test
    fun `effectiveRoutingPolicy uses explicit policy when set`() {
        val entry = chatEntry.copy(routingPolicy = RoutingPolicy.CLOUD_ONLY)
        assertEquals(RoutingPolicy.CLOUD_ONLY, entry.effectiveRoutingPolicy)
    }

    @Test
    fun `effectiveRoutingPolicy infers LOCAL_ONLY for BUNDLED`() {
        assertEquals(RoutingPolicy.LOCAL_ONLY, transcriptionEntry.effectiveRoutingPolicy)
    }

    @Test
    fun `effectiveRoutingPolicy infers LOCAL_FIRST for MANAGED`() {
        assertEquals(RoutingPolicy.LOCAL_FIRST, chatEntry.effectiveRoutingPolicy)
    }

    @Test
    fun `effectiveRoutingPolicy infers CLOUD_ONLY for CLOUD`() {
        val cloudEntry = AppModelEntry(
            id = "gpt-4",
            capability = ModelCapability.CHAT,
            delivery = DeliveryMode.CLOUD,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
        )
        assertEquals(RoutingPolicy.CLOUD_ONLY, cloudEntry.effectiveRoutingPolicy)
    }

    // =========================================================================
    // Multimodal properties
    // =========================================================================

    @Test
    fun `isVisionModel returns true for text+image input`() {
        val vlEntry = chatEntry.copy(
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        assertTrue(vlEntry.isVisionModel)
        assertTrue(vlEntry.isMultimodal)
    }

    @Test
    fun `isVisionModel returns false for text-only`() {
        assertFalse(chatEntry.isVisionModel)
        assertFalse(chatEntry.isMultimodal)
    }

    @Test
    fun `isAudioModel returns true for audio input`() {
        assertTrue(transcriptionEntry.isAudioModel)
    }

    @Test
    fun `isMultimodal returns true when input has non-text modality`() {
        val audioEntry = chatEntry.copy(
            inputModalities = listOf(Modality.AUDIO),
        )
        assertTrue(audioEntry.isMultimodal)
    }

    // =========================================================================
    // Resource bindings
    // =========================================================================

    @Test
    fun `resourceForKind returns matching resource`() {
        val entry = chatEntry.copy(
            resources = listOf(
                ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://model.gguf"),
                ManifestResource(kind = ArtifactResourceKind.PROJECTOR, uri = "hf://mmproj.gguf"),
            ),
        )
        val weights = entry.resourceForKind(ArtifactResourceKind.WEIGHTS)
        assertNotNull(weights)
        assertEquals("hf://model.gguf", weights.uri)
    }

    @Test
    fun `resourceForKind returns null when no match`() {
        val entry = chatEntry.copy(
            resources = listOf(
                ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://model.gguf"),
            ),
        )
        assertNull(entry.resourceForKind(ArtifactResourceKind.PROJECTOR))
    }

    @Test
    fun `resourcesForKind returns all matching resources`() {
        val entry = chatEntry.copy(
            resources = listOf(
                ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://shard1.gguf"),
                ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://shard2.gguf"),
                ManifestResource(kind = ArtifactResourceKind.TOKENIZER, uri = "hf://tokenizer.json"),
            ),
        )
        val weights = entry.resourcesForKind(ArtifactResourceKind.WEIGHTS)
        assertEquals(2, weights.size)
    }

    // =========================================================================
    // Engine config
    // =========================================================================

    @Test
    fun `engineConfig defaults to empty map`() {
        assertTrue(chatEntry.engineConfig.isEmpty())
    }

    @Test
    fun `engineConfig carries executor hints`() {
        val entry = chatEntry.copy(
            engineConfig = mapOf("n_gpu_layers" to "99", "ctx_size" to "4096"),
        )
        assertEquals("99", entry.engineConfig["n_gpu_layers"])
        assertEquals("4096", entry.engineConfig["ctx_size"])
    }
}
