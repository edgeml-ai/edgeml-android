package ai.edgeml.sync

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [WorkManagerSync] and [EdgeMLSyncWorker] constants.
 *
 * WorkManager.getInstance() is a static factory that cannot be reliably
 * mocked across MockK + AGP versions. Tests for scheduling/cancellation
 * behavior are covered by integration tests instead.
 */
class WorkManagerSyncTest {

    @Test
    fun `sync worker tag is defined`() {
        assertNotNull(EdgeMLSyncWorker.TAG)
        assertTrue(EdgeMLSyncWorker.TAG.isNotEmpty())
    }

    @Test
    fun `sync worker work names are defined`() {
        assertNotNull(EdgeMLSyncWorker.WORK_NAME_PERIODIC)
        assertNotNull(EdgeMLSyncWorker.WORK_NAME_ONE_TIME)
        assertTrue(EdgeMLSyncWorker.WORK_NAME_PERIODIC.isNotEmpty())
        assertTrue(EdgeMLSyncWorker.WORK_NAME_ONE_TIME.isNotEmpty())
    }

    @Test
    fun `sync worker data keys are defined`() {
        assertNotNull(EdgeMLSyncWorker.KEY_CONFIG_JSON)
        assertNotNull(EdgeMLSyncWorker.KEY_SYNC_TYPE)
    }

    @Test
    fun `sync types are defined`() {
        assertTrue(EdgeMLSyncWorker.SYNC_TYPE_FULL.isNotEmpty())
        assertTrue(EdgeMLSyncWorker.SYNC_TYPE_MODEL.isNotEmpty())
        assertTrue(EdgeMLSyncWorker.SYNC_TYPE_EVENTS.isNotEmpty())
    }

    @Test
    fun `sync types are distinct`() {
        val types = setOf(
            EdgeMLSyncWorker.SYNC_TYPE_FULL,
            EdgeMLSyncWorker.SYNC_TYPE_MODEL,
            EdgeMLSyncWorker.SYNC_TYPE_EVENTS,
        )
        assertTrue(types.size == 3, "Sync types should be distinct values")
    }
}
