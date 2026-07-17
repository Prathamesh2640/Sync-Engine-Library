package io.github.prathamesh2640.sync.workmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.prathamesh2640.sync.core.engine.SyncEngine
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.result.SyncError
import io.github.prathamesh2640.sync.core.result.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JVM tests for the WorkManager scheduler and worker, run under Robolectric with
 * [WorkManagerTestInitHelper] — no device or emulator needed.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerSyncSchedulerTest {

    private lateinit var context: Context

    /** Records whether it was triggered and returns a fixed result. */
    private class FakeEngine(private val result: SyncResult) : SyncEngine {
        var triggered = false
            private set
        override val syncState: StateFlow<SyncState> = MutableStateFlow(SyncState.PENDING)
        override suspend fun triggerSync(): SyncResult {
            triggered = true
            return result
        }
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SyncEngineRegistry.clear()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun worker_triggers_engine_and_maps_success() = runTest {
        val engine = FakeEngine(SyncResult.Success(syncedCount = 1, conflictCount = 0))
        SyncEngineRegistry.register { engine }

        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        val result = worker.doWork()

        assertTrue("engine should have been triggered", engine.triggered)
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun worker_maps_failure_to_retry() = runTest {
        SyncEngineRegistry.register { FakeEngine(SyncResult.Failure(SyncError.NetworkUnavailable)) }

        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun worker_without_registered_engine_fails_cleanly() = runTest {
        SyncEngineRegistry.clear()

        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test
    fun schedule_enqueues_unique_periodic_work() {
        val engine = FakeEngine(SyncResult.Success(0, 0))
        WorkManagerSyncScheduler(context, engineProvider = { engine }).schedulePeriodicSync()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.UNIQUE_WORK_NAME)
            .get()

        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun cancel_removes_the_scheduled_work() {
        val engine = FakeEngine(SyncResult.Success(0, 0))
        val scheduler = WorkManagerSyncScheduler(context, engineProvider = { engine })
        scheduler.schedulePeriodicSync()

        scheduler.cancelSync()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.UNIQUE_WORK_NAME)
            .get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }
}
