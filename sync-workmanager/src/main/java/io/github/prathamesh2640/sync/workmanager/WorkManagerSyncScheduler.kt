package io.github.prathamesh2640.sync.workmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
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
 * @param networkRequirement network condition required before a run. Defaults
 *   to [SyncNetworkRequirement.CONNECTED] (today's fixed behavior).
 * @param backoffPolicy retry backoff strategy after a failed run. Defaults to
 *   [SyncBackoffPolicy.EXPONENTIAL] (today's fixed behavior).
 * @param backoffDelayMillis initial backoff delay in milliseconds. Defaults to
 *   [WorkRequest.MIN_BACKOFF_MILLIS] (today's fixed behavior).
 */
public class WorkManagerSyncScheduler @JvmOverloads constructor(
    context: Context,
    engineProvider: () -> SyncEngine,
    private val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
    private val networkRequirement: SyncNetworkRequirement = SyncNetworkRequirement.CONNECTED,
    private val backoffPolicy: SyncBackoffPolicy = SyncBackoffPolicy.EXPONENTIAL,
    private val backoffDelayMillis: Long = WorkRequest.MIN_BACKOFF_MILLIS,
) : SyncScheduler {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context.applicationContext) }

    init {
        SyncEngineRegistry.register(engineProvider)
    }

    override fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkRequirement.toWorkManagerType())
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES),
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                backoffPolicy.toWorkManagerPolicy(),
                backoffDelayMillis,
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
