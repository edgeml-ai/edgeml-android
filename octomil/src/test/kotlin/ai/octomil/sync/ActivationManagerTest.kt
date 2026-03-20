package ai.octomil.sync

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActivationManagerTest {

    private lateinit var tempDir: File
    private lateinit var store: ArtifactMetadataStore
    private lateinit var manager: ActivationManager

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "octomil_activation_test_${System.nanoTime()}")
        tempDir.mkdirs()
        store = ArtifactMetadataStore(tempDir)
        manager = ActivationManager(store)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // activate — immediate policy
    // =========================================================================

    @Test
    fun `immediate activation marks entry as active`() {
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2"))

        val result = manager.activate("model-a", "art-1", "2.0", ActivationPolicy.IMMEDIATE)

        assertTrue(result.success)
        assertEquals("v2", result.newVersion)

        val entry = store.entry("art-1", "2.0")
        assertNotNull(entry)
        assertTrue(entry.active)
        assertEquals(ArtifactSyncStatus.ACTIVE, entry.status)
    }

    @Test
    fun `immediate activation deactivates previous active version`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2"))

        val result = manager.activate("model-a", "art-1", "2.0", ActivationPolicy.IMMEDIATE)

        assertTrue(result.success)
        assertEquals("v1", result.previousVersion)
        assertEquals("v2", result.newVersion)

        val oldEntry = store.entry("art-1", "1.0")
        assertNotNull(oldEntry)
        assertEquals(false, oldEntry.active)
    }

    @Test
    fun `immediate activation returns null previousVersion when no prior active`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1"))

        val result = manager.activate("model-a", "art-1", "1.0", ActivationPolicy.IMMEDIATE)

        assertTrue(result.success)
        assertEquals(null, result.previousVersion)
    }

    // =========================================================================
    // activate — next_launch policy
    // =========================================================================

    @Test
    fun `next_launch policy stages entry without activating`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2"))

        val result = manager.activate("model-a", "art-1", "2.0", ActivationPolicy.NEXT_LAUNCH)

        assertTrue(result.success)

        // Old version should still be active
        val oldEntry = store.entry("art-1", "1.0")
        assertNotNull(oldEntry)
        assertTrue(oldEntry.active)

        // New version should be staged, not active
        val newEntry = store.entry("art-1", "2.0")
        assertNotNull(newEntry)
        assertEquals(false, newEntry.active)
        assertEquals(ArtifactSyncStatus.STAGED, newEntry.status)
    }

    // =========================================================================
    // activate — manual policy
    // =========================================================================

    @Test
    fun `manual policy stages entry without activating`() {
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2"))

        val result = manager.activate("model-a", "art-1", "2.0", ActivationPolicy.MANUAL)

        assertTrue(result.success)

        val entry = store.entry("art-1", "2.0")
        assertNotNull(entry)
        assertEquals(false, entry.active)
        assertEquals(ArtifactSyncStatus.STAGED, entry.status)
    }

    // =========================================================================
    // activate — when_idle policy
    // =========================================================================

    @Test
    fun `when_idle policy stages entry without activating`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2"))

        val result = manager.activate("model-a", "art-1", "2.0", ActivationPolicy.WHEN_IDLE)

        assertTrue(result.success)

        // Old version should still be active
        val oldEntry = store.entry("art-1", "1.0")
        assertNotNull(oldEntry)
        assertTrue(oldEntry.active)

        // New version should be staged
        val newEntry = store.entry("art-1", "2.0")
        assertNotNull(newEntry)
        assertEquals(false, newEntry.active)
        assertEquals(ArtifactSyncStatus.STAGED, newEntry.status)
    }

    // =========================================================================
    // activatePending
    // =========================================================================

    @Test
    fun `activatePending activates staged entries`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))
        store.upsert(
            makeEntry(
                "art-1", "2.0", "model-a", "v2",
                status = ArtifactSyncStatus.STAGED,
            ),
        )

        val results = manager.activatePending("model-a")

        assertEquals(1, results.size)
        assertTrue(results[0].success)

        val newActive = store.activeEntry("model-a")
        assertNotNull(newActive)
        assertEquals("v2", newActive.modelVersion)
    }

    @Test
    fun `activatePending returns empty when nothing staged`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))

        val results = manager.activatePending("model-a")
        assertTrue(results.isEmpty())
    }

    // =========================================================================
    // rollback
    // =========================================================================

    @Test
    fun `rollback marks failed version and re-activates previous`() {
        // v1 was active, then v2 was activated but needs rollback
        store.upsert(
            makeEntry("art-1", "1.0", "model-a", "v1",
                status = ArtifactSyncStatus.ACTIVE, installedAt = 1000),
        )
        store.upsert(
            makeEntry("art-1", "2.0", "model-a", "v2",
                active = true, status = ArtifactSyncStatus.ACTIVE, installedAt = 2000),
        )

        val result = manager.rollback("model-a", "art-1", "2.0", "healthcheck_failed")

        assertTrue(result.success)
        assertEquals("v1", result.previousVersion)

        // v2 should be marked failed and inactive
        val failedEntry = store.entry("art-1", "2.0")
        assertNotNull(failedEntry)
        assertEquals(ArtifactSyncStatus.FAILED, failedEntry.status)
        assertEquals(false, failedEntry.active)

        // v1 should be re-activated
        val activeEntry = store.activeEntry("model-a")
        assertNotNull(activeEntry)
        assertEquals("v1", activeEntry.modelVersion)
        assertTrue(activeEntry.active)
    }

    @Test
    fun `rollback returns failure when no fallback version available`() {
        store.upsert(
            makeEntry("art-1", "1.0", "model-a", "v1",
                active = true, status = ArtifactSyncStatus.ACTIVE),
        )

        val result = manager.rollback("model-a", "art-1", "1.0", "warmup_crashed")

        assertEquals(false, result.success)
        assertEquals("no_fallback_available", result.errorCode)
    }

    @Test
    fun `rollback skips failed entries when finding fallback`() {
        store.upsert(
            makeEntry("art-1", "1.0", "model-a", "v1",
                status = ArtifactSyncStatus.FAILED, installedAt = 1000),
        )
        store.upsert(
            makeEntry("art-1", "1.5", "model-a", "v1.5",
                status = ArtifactSyncStatus.ACTIVE, installedAt = 1500),
        )
        store.upsert(
            makeEntry("art-1", "2.0", "model-a", "v2",
                active = true, status = ArtifactSyncStatus.ACTIVE, installedAt = 2000),
        )

        val result = manager.rollback("model-a", "art-1", "2.0", "error")

        assertTrue(result.success)
        // Should fall back to v1.5, not v1 (which is FAILED)
        assertEquals("v1.5", result.previousVersion)
    }

    // =========================================================================
    // ActivationPolicy.fromCode
    // =========================================================================

    @Test
    fun `ActivationPolicy fromCode parses known values`() {
        assertEquals(ActivationPolicy.IMMEDIATE, ActivationPolicy.fromCode("immediate"))
        assertEquals(ActivationPolicy.NEXT_LAUNCH, ActivationPolicy.fromCode("next_launch"))
        assertEquals(ActivationPolicy.MANUAL, ActivationPolicy.fromCode("manual"))
        assertEquals(ActivationPolicy.WHEN_IDLE, ActivationPolicy.fromCode("when_idle"))
    }

    @Test
    fun `ActivationPolicy fromCode defaults to IMMEDIATE for unknown`() {
        assertEquals(ActivationPolicy.IMMEDIATE, ActivationPolicy.fromCode("unknown_policy"))
        assertEquals(ActivationPolicy.IMMEDIATE, ActivationPolicy.fromCode(null))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeEntry(
        artifactId: String,
        artifactVersion: String,
        modelId: String,
        modelVersion: String,
        active: Boolean = false,
        status: ArtifactSyncStatus = ArtifactSyncStatus.VERIFIED,
        installedAt: Long = System.currentTimeMillis(),
    ) = ArtifactMetadataEntry(
        artifactId = artifactId,
        artifactVersion = artifactVersion,
        modelId = modelId,
        modelVersion = modelVersion,
        installedAt = installedAt,
        status = status,
        active = active,
    )
}
