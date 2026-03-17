package ai.octomil.manifest

import ai.octomil.generated.DeliveryMode
import ai.octomil.generated.ModelCapability
import ai.octomil.generated.RoutingPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppManifestTest {

    private val chatEntry = AppModelEntry(
        id = "phi-4-mini",
        capability = ModelCapability.CHAT,
        delivery = DeliveryMode.MANAGED,
    )

    private val predictionEntry = AppModelEntry(
        id = "smollm2-135m",
        capability = ModelCapability.KEYBOARD_PREDICTION,
        delivery = DeliveryMode.MANAGED,
    )

    private val transcriptionEntry = AppModelEntry(
        id = "whisper-small",
        capability = ModelCapability.TRANSCRIPTION,
        delivery = DeliveryMode.BUNDLED,
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
        )
        assertEquals(RoutingPolicy.CLOUD_ONLY, cloudEntry.effectiveRoutingPolicy)
    }
}
