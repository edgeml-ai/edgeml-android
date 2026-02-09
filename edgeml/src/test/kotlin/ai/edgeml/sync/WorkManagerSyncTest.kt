package ai.edgeml.sync

import ai.edgeml.testConfig
import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkManagerSyncTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var syncManager: WorkManagerSync

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        workManager = mockk<WorkManager>(relaxed = true)

        // Mock WorkManager.getInstance to return our mock
        every { WorkManager.getInstance(any()) } returns workManager
    }

    // =========================================================================
    // schedulePeriodicSync
    // =========================================================================

    @Test
    fun `schedulePeriodicSync skips when background sync disabled`() {
        val config = testConfig(enableBackgroundSync = false)
        syncManager = WorkManagerSync(context, config)

        syncManager.schedulePeriodicSync()

        // Should not enqueue any work
        verify(exactly = 0) {
            workManager.enqueueUniquePeriodicWork(any(), any(), any())
        }
    }

    @Test
    fun `schedulePeriodicSync enqueues periodic work when enabled`() {
        val config = testConfig(enableBackgroundSync = true)
        syncManager = WorkManagerSync(context, config)

        syncManager.schedulePeriodicSync()

        verify {
            workManager.enqueueUniquePeriodicWork(
                EdgeMLSyncWorker.WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                any<PeriodicWorkRequest>(),
            )
        }
    }

    // =========================================================================
    // triggerImmediateSync
    // =========================================================================

    @Test
    fun `triggerImmediateSync enqueues one-time work`() {
        val config = testConfig()
        syncManager = WorkManagerSync(context, config)

        syncManager.triggerImmediateSync()

        verify {
            workManager.enqueueUniqueWork(
                EdgeMLSyncWorker.WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `triggerImmediateSync accepts custom sync type`() {
        val config = testConfig()
        syncManager = WorkManagerSync(context, config)

        syncManager.triggerImmediateSync(syncType = EdgeMLSyncWorker.SYNC_TYPE_EVENTS)

        verify {
            workManager.enqueueUniqueWork(
                EdgeMLSyncWorker.WORK_NAME_ONE_TIME,
                any(),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    // =========================================================================
    // cancelPeriodicSync
    // =========================================================================

    @Test
    fun `cancelPeriodicSync cancels unique work`() {
        val config = testConfig()
        syncManager = WorkManagerSync(context, config)

        syncManager.cancelPeriodicSync()

        verify { workManager.cancelUniqueWork(EdgeMLSyncWorker.WORK_NAME_PERIODIC) }
    }

    // =========================================================================
    // cancelAllSync
    // =========================================================================

    @Test
    fun `cancelAllSync cancels by tag`() {
        val config = testConfig()
        syncManager = WorkManagerSync(context, config)

        syncManager.cancelAllSync()

        verify { workManager.cancelAllWorkByTag(EdgeMLSyncWorker.TAG) }
    }

    // =========================================================================
    // getSyncStatus
    // =========================================================================

    @Test
    fun `getSyncStatus returns status for both work types`() {
        val config = testConfig()
        syncManager = WorkManagerSync(context, config)

        every {
            workManager.getWorkInfosForUniqueWorkLiveData(any())
        } returns MutableLiveData(emptyList())

        val status = syncManager.getSyncStatus()
        assertNotNull(status.periodicWork)
        assertNotNull(status.oneTimeWork)
    }

    // =========================================================================
    // EdgeMLSyncWorker constants
    // =========================================================================

    @Test
    fun `sync worker constants are defined`() {
        assertNotNull(EdgeMLSyncWorker.TAG)
        assertNotNull(EdgeMLSyncWorker.WORK_NAME_PERIODIC)
        assertNotNull(EdgeMLSyncWorker.WORK_NAME_ONE_TIME)
        assertNotNull(EdgeMLSyncWorker.KEY_CONFIG_JSON)
        assertNotNull(EdgeMLSyncWorker.KEY_SYNC_TYPE)
        assertTrue(EdgeMLSyncWorker.SYNC_TYPE_FULL.isNotEmpty())
        assertTrue(EdgeMLSyncWorker.SYNC_TYPE_MODEL.isNotEmpty())
        assertTrue(EdgeMLSyncWorker.SYNC_TYPE_EVENTS.isNotEmpty())
    }
}
