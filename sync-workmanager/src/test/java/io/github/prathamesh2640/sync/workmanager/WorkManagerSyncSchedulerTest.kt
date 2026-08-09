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
import androidx.work.workDataOf
import io.github.prathamesh2640.sync.core.engine.SyncEngine
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncStats
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
        override val stats: StateFlow<SyncStats> = MutableStateFlow(SyncStats.INITIAL)
        override suspend fun triggerSync(): SyncResult {
            triggered = true
            return result
        }
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SyncEngineRegistry.clear(SyncWorker.DEFAULT_ENGINE_KEY)
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun defaultKeyedWorker(): TestListenableWorkerBuilder<SyncWorker> =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_ENGINE_KEY to SyncWorker.DEFAULT_ENGINE_KEY))

    @Test
    fun worker_triggers_engine_and_maps_success() = runTest {
        val engine = FakeEngine(SyncResult.Success(syncedCount = 1, conflictCount = 0))
        SyncEngineRegistry.register(SyncWorker.DEFAULT_ENGINE_KEY) { engine }

        val worker = defaultKeyedWorker().build()
        val result = worker.doWork()

        assertTrue("engine should have been triggered", engine.triggered)
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun worker_maps_failure_to_retry() = runTest {
        SyncEngineRegistry.register(SyncWorker.DEFAULT_ENGINE_KEY) {
            FakeEngine(SyncResult.Failure(SyncError.NetworkUnavailable))
        }

        val worker = defaultKeyedWorker().build()

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun worker_without_registered_engine_fails_cleanly() = runTest {
        SyncEngineRegistry.clear(SyncWorker.DEFAULT_ENGINE_KEY)

        val worker = defaultKeyedWorker().build()

        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test
    fun schedule_enqueues_unique_periodic_work() {
        val engine = FakeEngine(SyncResult.Success(0, 0))
        WorkManagerSyncScheduler(context, engineProvider = { engine }).schedulePeriodicSync()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.uniqueWorkName(SyncWorker.DEFAULT_ENGINE_KEY))
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
            .getWorkInfosForUniqueWork(SyncWorker.uniqueWorkName(SyncWorker.DEFAULT_ENGINE_KEY))
            .get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun two_engines_under_different_keys_schedule_independently() {
        val notesEngine = FakeEngine(SyncResult.Success(0, 0))
        val remindersEngine = FakeEngine(SyncResult.Success(0, 0))

        WorkManagerSyncScheduler(
            context,
            engineProvider = { notesEngine },
            engineKey = "notes",
        ).schedulePeriodicSync()
        WorkManagerSyncScheduler(
            context,
            engineProvider = { remindersEngine },
            engineKey = "reminders",
        ).schedulePeriodicSync()

        val workManager = WorkManager.getInstance(context)
        val notesInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueWorkName("notes")).get()
        val remindersInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.uniqueWorkName("reminders")).get()

        assertEquals(1, notesInfos.size)
        assertEquals(1, remindersInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, notesInfos.single().state)
        assertEquals(WorkInfo.State.ENQUEUED, remindersInfos.single().state)

        SyncEngineRegistry.clear("notes")
        SyncEngineRegistry.clear("reminders")
    }
}
