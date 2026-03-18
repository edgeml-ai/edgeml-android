package ai.octomil.sync

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactMetadataStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: ArtifactMetadataStore

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "octomil_metadata_test_${System.nanoTime()}")
        tempDir.mkdirs()
        store = ArtifactMetadataStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // upsert + entry
    // =========================================================================

    @Test
    fun `upsert stores entry and entry retrieves it`() {
        val entry = makeEntry("art-1", "1.0", "model-a", "v1")
        store.upsert(entry)

        val retrieved = store.entry("art-1", "1.0")
        assertNotNull(retrieved)
        assertEquals("art-1", retrieved.artifactId)
        assertEquals("1.0", retrieved.artifactVersion)
        assertEquals("model-a", retrieved.modelId)
        assertEquals("v1", retrieved.modelVersion)
    }

    @Test
    fun `upsert overwrites existing entry with same key`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", status = ArtifactSyncStatus.VERIFIED))
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", status = ArtifactSyncStatus.ACTIVE))

        val retrieved = store.entry("art-1", "1.0")
        assertNotNull(retrieved)
        assertEquals(ArtifactSyncStatus.ACTIVE, retrieved.status)
    }

    @Test
    fun `entry returns null for missing key`() {
        assertNull(store.entry("nonexistent", "1.0"))
    }

    // =========================================================================
    // activeEntry + installedVersion
    // =========================================================================

    @Test
    fun `activeEntry returns null when nothing active`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = false))
        assertNull(store.activeEntry("model-a"))
    }

    @Test
    fun `activeEntry returns the active entry`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2", active = false))

        val active = store.activeEntry("model-a")
        assertNotNull(active)
        assertEquals("v1", active.modelVersion)
        assertEquals(true, active.active)
    }

    @Test
    fun `installedVersion returns active model version`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))
        assertEquals("v1", store.installedVersion("model-a"))
    }

    @Test
    fun `installedVersion returns null when no active entry`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = false))
        assertNull(store.installedVersion("model-a"))
    }

    // =========================================================================
    // activate
    // =========================================================================

    @Test
    fun `activate sets target active and deactivates siblings`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2", active = false))

        store.activate("model-a", "art-1", "2.0")

        val oldEntry = store.entry("art-1", "1.0")
        val newEntry = store.entry("art-1", "2.0")
        assertNotNull(oldEntry)
        assertNotNull(newEntry)
        assertEquals(false, oldEntry.active)
        assertEquals(true, newEntry.active)
        assertEquals(ArtifactSyncStatus.ACTIVE, newEntry.status)
    }

    @Test
    fun `activate does not affect entries of other models`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))
        store.upsert(makeEntry("art-2", "1.0", "model-b", "v1", active = true))

        store.activate("model-a", "art-1", "1.0")

        val otherModel = store.entry("art-2", "1.0")
        assertNotNull(otherModel)
        assertEquals(true, otherModel.active)
    }

    // =========================================================================
    // markFailed
    // =========================================================================

    @Test
    fun `markFailed sets status and deactivates entry`() {
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2", active = true))

        store.markFailed("art-1", "2.0", "checksum_mismatch")

        val entry = store.entry("art-1", "2.0")
        assertNotNull(entry)
        assertEquals(ArtifactSyncStatus.FAILED, entry.status)
        assertEquals(false, entry.active)
        assertEquals("checksum_mismatch", entry.errorCode)
    }

    @Test
    fun `markFailed does nothing for missing entry`() {
        // Should not throw
        store.markFailed("nonexistent", "1.0", "test_error")
    }

    // =========================================================================
    // markPendingActivation
    // =========================================================================

    @Test
    fun `markPendingActivation sets status to STAGED`() {
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2", status = ArtifactSyncStatus.VERIFIED))

        store.markPendingActivation("art-1", "2.0")

        val entry = store.entry("art-1", "2.0")
        assertNotNull(entry)
        assertEquals(ArtifactSyncStatus.STAGED, entry.status)
    }

    // =========================================================================
    // entriesForModel
    // =========================================================================

    @Test
    fun `entriesForModel returns entries sorted by installedAt descending`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", installedAt = 1000))
        store.upsert(makeEntry("art-1", "2.0", "model-a", "v2", installedAt = 3000))
        store.upsert(makeEntry("art-1", "1.5", "model-a", "v1.5", installedAt = 2000))

        val entries = store.entriesForModel("model-a")
        assertEquals(3, entries.size)
        assertEquals("v2", entries[0].modelVersion)
        assertEquals("v1.5", entries[1].modelVersion)
        assertEquals("v1", entries[2].modelVersion)
    }

    @Test
    fun `entriesForModel returns empty for unknown model`() {
        assertTrue(store.entriesForModel("unknown").isEmpty())
    }

    // =========================================================================
    // remove
    // =========================================================================

    @Test
    fun `remove deletes entry and returns file path`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", filePath = "/tmp/model.bin"))

        val path = store.remove("art-1", "1.0")
        assertEquals("/tmp/model.bin", path)
        assertNull(store.entry("art-1", "1.0"))
    }

    @Test
    fun `remove returns null for missing entry`() {
        assertNull(store.remove("nonexistent", "1.0"))
    }

    // =========================================================================
    // gc
    // =========================================================================

    @Test
    fun `gc removes entries not in retain set`() {
        store.upsert(makeEntry("art-keep", "1.0", "model-a", "v1", filePath = "/keep"))
        store.upsert(makeEntry("art-remove", "1.0", "model-b", "v1", filePath = "/remove"))

        val removed = store.gc(setOf("art-keep"))
        assertEquals(listOf("/remove"), removed)
        assertNotNull(store.entry("art-keep", "1.0"))
        assertNull(store.entry("art-remove", "1.0"))
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    @Test
    fun `data persists across store instances`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1", active = true))

        // Create a new store instance pointing to the same directory
        val store2 = ArtifactMetadataStore(tempDir)

        val entry = store2.entry("art-1", "1.0")
        assertNotNull(entry)
        assertEquals("model-a", entry.modelId)
        assertEquals(true, entry.active)
    }

    @Test
    fun `allEntries returns all stored entries`() {
        store.upsert(makeEntry("art-1", "1.0", "model-a", "v1"))
        store.upsert(makeEntry("art-2", "1.0", "model-b", "v1"))

        assertEquals(2, store.allEntries().size)
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
        filePath: String? = null,
        installedAt: Long = System.currentTimeMillis(),
    ) = ArtifactMetadataEntry(
        artifactId = artifactId,
        artifactVersion = artifactVersion,
        modelId = modelId,
        modelVersion = modelVersion,
        installedAt = installedAt,
        status = status,
        active = active,
        filePath = filePath,
    )
}
