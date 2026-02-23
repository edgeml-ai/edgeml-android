package ai.octomil.sync

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [WorkManagerSync] and [OctomilSyncWorker] constants.
 *
 * WorkManager.getInstance() is a static factory that cannot be reliably
 * mocked across MockK + AGP versions. Tests for scheduling/cancellation
 * behavior are covered by integration tests instead.
 */
class WorkManagerSyncTest {

    @Test
    fun `sync worker tag is defined`() {
        assertNotNull(OctomilSyncWorker.TAG)
        assertTrue(OctomilSyncWorker.TAG.isNotEmpty())
    }

    @Test
    fun `sync worker work names are defined`() {
        assertNotNull(OctomilSyncWorker.WORK_NAME_PERIODIC)
        assertNotNull(OctomilSyncWorker.WORK_NAME_ONE_TIME)
        assertTrue(OctomilSyncWorker.WORK_NAME_PERIODIC.isNotEmpty())
        assertTrue(OctomilSyncWorker.WORK_NAME_ONE_TIME.isNotEmpty())
    }

    @Test
    fun `sync worker data keys are defined`() {
        assertNotNull(OctomilSyncWorker.KEY_CONFIG_JSON)
        assertNotNull(OctomilSyncWorker.KEY_SYNC_TYPE)
    }

    @Test
    fun `sync types are defined`() {
        assertTrue(OctomilSyncWorker.SYNC_TYPE_FULL.isNotEmpty())
        assertTrue(OctomilSyncWorker.SYNC_TYPE_MODEL.isNotEmpty())
        assertTrue(OctomilSyncWorker.SYNC_TYPE_EVENTS.isNotEmpty())
    }

    @Test
    fun `sync types are distinct`() {
        val types = setOf(
            OctomilSyncWorker.SYNC_TYPE_FULL,
            OctomilSyncWorker.SYNC_TYPE_MODEL,
            OctomilSyncWorker.SYNC_TYPE_EVENTS,
        )
        assertTrue(types.size == 3, "Sync types should be distinct values")
    }
}
