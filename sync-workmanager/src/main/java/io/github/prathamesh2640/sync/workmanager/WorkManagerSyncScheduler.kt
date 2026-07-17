package io.github.prathamesh2640.sync.workmanager

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import io.github.prathamesh2640.sync.core.engine.SyncEngine
import io.github.prathamesh2640.sync.core.scheduler.SyncScheduler
import java.util.concurrent.TimeUnit

/**
 * WorkManager-backed [SyncScheduler].
 *
 * Schedules a single unique periodic [SyncWorker] that calls
 * [SyncEngine.triggerSync] on the host's engine. The engine is supplied through
 * [engineProvider] (WorkManager cannot inject it directly), which the scheduler
 * registers with [SyncEngineRegistry] at construction — so create the scheduler
 * once, wherever the engine is created (e.g. your `Application`).
 *
 * The job requires network connectivity and retries failed runs with exponential
 * backoff. WorkManager is kept entirely internal: the constructor takes only a
 * [Context] and the engine provider, so no `androidx.work` type appears in the
 * public API (module-guide).
 *
 * ```kotlin
 * class App : Application() {
 *     lateinit var engine: SyncEngine
 *     override fun onCreate() {
 *         super.onCreate()
 *         engine = SyncEngine.create(adapter, store = store, resolver = resolver)
 *         WorkManagerSyncScheduler(this, engineProvider = { engine }).schedulePeriodicSync()
 *     }
 * }
 * ```
 *
 * @param context any [Context]; the application context is used internally.
 * @param engineProvider supplies the [SyncEngine] to sync with, invoked per run.
 * @param intervalMinutes how often to sync. WorkManager's minimum periodic
 *   interval is 15 minutes; smaller values are coerced up to it. Defaults to
 *   [DEFAULT_INTERVAL_MINUTES].
 */
public class WorkManagerSyncScheduler @JvmOverloads constructor(
    context: Context,
    engineProvider: () -> SyncEngine,
    private val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
) : SyncScheduler {

    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext)

    init {
        SyncEngineRegistry.register(engineProvider)
    }

    override fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES),
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancelSync() {
        workManager.cancelUniqueWork(SyncWorker.UNIQUE_WORK_NAME)
    }

    public companion object {
        /** Default sync cadence in minutes. */
        public const val DEFAULT_INTERVAL_MINUTES: Long = 15L

        /** WorkManager's hard minimum for periodic work. */
        private const val MIN_INTERVAL_MINUTES: Long = 15L
    }
}
