package ai.edgeml.sync

import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.testConfig
import android.content.Context
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [WorkManagerSync].
 *
 * [WorkManager.getInstance] is a static method — we use [mockkStatic] to
 * intercept it and return a relaxed mock, avoiding the
 * [AbstractMethodError] / [ClassCastException] that occurs when
 * WorkManager tries to call real abstract methods on a plain mock.
 */
class WorkManagerSyncTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        // Mock the static WorkManager.getInstance call
        mockkStatic(WorkManager::class)
        val workManager = mockk<WorkManager>(relaxed = true)
        every { WorkManager.getInstance(any()) } returns workManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // schedulePeriodicSync
    // =========================================================================

    @Test
    fun `schedulePeriodicSync skips when background sync disabled`() {
        val config = testConfig(enableBackgroundSync = false)
        val syncManager = WorkManagerSync(context, config)

        syncManager.schedulePeriodicSync()

        // Should not enqueue any work — verify via WorkManager mock
        verify(exactly = 0) {
            WorkManager.getInstance(any()).enqueueUniquePeriodicWork(
                any(), any(), any()
            )
        }
    }

    @Test
    fun `schedulePeriodicSync enqueues periodic work when enabled`() {
        val config = testConfig(enableBackgroundSync = true)
        val syncManager = WorkManagerSync(context, config)

        syncManager.schedulePeriodicSync()

        verify {
            WorkManager.getInstance(any()).enqueueUniquePeriodicWork(
                EdgeMLSyncWorker.WORK_NAME_PERIODIC,
                any(),
                any(),
            )
        }
    }

    // =========================================================================
    // triggerImmediateSync
    // =========================================================================

    @Test
    fun `triggerImmediateSync enqueues one-time work`() {
        val config = testConfig()
        val syncManager = WorkManagerSync(context, config)

        syncManager.triggerImmediateSync()

        verify {
            WorkManager.getInstance(any()).enqueueUniqueWork(
                EdgeMLSyncWorker.WORK_NAME_ONE_TIME,
                any(),
                any<androidx.work.OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `triggerImmediateSync accepts custom sync type`() {
        val config = testConfig()
        val syncManager = WorkManagerSync(context, config)

        syncManager.triggerImmediateSync(syncType = EdgeMLSyncWorker.SYNC_TYPE_EVENTS)

        verify {
            WorkManager.getInstance(any()).enqueueUniqueWork(
                EdgeMLSyncWorker.WORK_NAME_ONE_TIME,
                any(),
                any<androidx.work.OneTimeWorkRequest>(),
            )
        }
    }

    // =========================================================================
    // cancelPeriodicSync
    // =========================================================================

    @Test
    fun `cancelPeriodicSync cancels unique work`() {
        val config = testConfig()
        val syncManager = WorkManagerSync(context, config)

        syncManager.cancelPeriodicSync()

        verify {
            WorkManager.getInstance(any())
                .cancelUniqueWork(EdgeMLSyncWorker.WORK_NAME_PERIODIC)
        }
    }

    // =========================================================================
    // cancelAllSync
    // =========================================================================

    @Test
    fun `cancelAllSync cancels by tag`() {
        val config = testConfig()
        val syncManager = WorkManagerSync(context, config)

        syncManager.cancelAllSync()

        verify {
            WorkManager.getInstance(any())
                .cancelAllWorkByTag(EdgeMLSyncWorker.TAG)
        }
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
