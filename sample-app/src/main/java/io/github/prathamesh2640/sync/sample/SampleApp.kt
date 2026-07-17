package io.github.prathamesh2640.sync.sample

import android.app.Application
import io.github.prathamesh2640.sync.workmanager.WorkManagerSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires the whole library stack together for the sample:
 * engine + Room store + fake network + WorkManager scheduling + (debug) dashboard.
 */
class SampleApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: NotesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = NotesRepository(this, appScope)

        // Periodic background sync (every 15 min, when online).
        WorkManagerSyncScheduler(this, engineProvider = { repository.engine })
            .schedulePeriodicSync()

        // Debug dashboard wiring — a no-op in release builds (see src/release).
        installDashboard(
            scope = appScope,
            snapshots = repository.snapshot,
            onTriggerSync = { appScope.launch { repository.syncNow() } },
        )
    }
}
