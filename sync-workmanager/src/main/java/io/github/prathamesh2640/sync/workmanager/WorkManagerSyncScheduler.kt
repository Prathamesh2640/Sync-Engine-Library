package io.github.prathamesh2640.sync.workmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import io.github.prathamesh2640.sync.core.engine.SyncEngine
import io.github.prathamesh2640.sync.core.scheduler.SyncScheduler
import java.util.concurrent.TimeUnit

/**
 * WorkManager-backed [SyncScheduler].
 *
 * Schedules a single unique periodic [SyncWorker] that calls
 * [SyncEngine.triggerSync] on the host's engine. The engine is supplied through
 * [engineProvider] (WorkManager cannot inject it directly), which the scheduler
 * registers with [SyncEngineRegistry] under [engineKey] at construction — so
 * create the scheduler once, wherever the engine is created (e.g. your
 * `Application`).
 *
 * [engineKey] lets more than one engine run in the same process (e.g. two
 * independent sync targets): give each its own key and each gets its own
 * periodic work, isolated from the other.
 *
 * ```kotlin
 * class App : Application() {
 *     lateinit var engine: SyncEngine
 *     override fun onCreate() {
 *         super.onCreate()
 *         engine = SyncEngine.create(adapter, store = store, resolver = resolver)
 *         WorkManagerSyncScheduler(this, engineProvider = { engine }).schedulePeriodicSync()
 *
 *         // A second, independent engine in the same process:
 *         WorkManagerSyncScheduler(
 *             this,
 *             engineProvider = { remindersEngine },
 *             engineKey = "reminders",
 *         ).schedulePeriodicSync()
 *     }
 * }
 * ```
 *
 * The job requires network connectivity and retries failed runs with exponential
 * backoff by default (see [networkRequirement]/[backoffPolicy]). WorkManager is
 * kept entirely internal: no `androidx.work` type appears in the public API
 * (module-guide).
 *
 * @param context any [Context]; the application context is used internally.
 * @param engineProvider supplies the [SyncEngine] to sync with, invoked per run.
 * @param intervalMinutes how often to sync. WorkManager's minimum periodic
 *   interval is 15 minutes; smaller values are coerced up to it. Defaults to
 *   [DEFAULT_INTERVAL_MINUTES].
 * @param engineKey identifies this scheduler/engine pair in [SyncEngineRegistry]
 *   and scopes the WorkManager unique-work name, so more than one engine can be
 *   scheduled in the same process without colliding. Defaults to
 *   [SyncWorker.DEFAULT_ENGINE_KEY] — hosts with a single engine never need to
 *   set this.
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
    private val engineKey: String = SyncWorker.DEFAULT_ENGINE_KEY,
    private val networkRequirement: SyncNetworkRequirement = SyncNetworkRequirement.CONNECTED,
    private val backoffPolicy: SyncBackoffPolicy = SyncBackoffPolicy.EXPONENTIAL,
    private val backoffDelayMillis: Long = WorkRequest.MIN_BACKOFF_MILLIS,
) : SyncScheduler {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context.applicationContext) }
    private val uniqueWorkName: String = SyncWorker.uniqueWorkName(engineKey)

    init {
        SyncEngineRegistry.register(engineKey, engineProvider)
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
            .setInputData(workDataOf(SyncWorker.KEY_ENGINE_KEY to engineKey))
            .setBackoffCriteria(
                backoffPolicy.toWorkManagerPolicy(),
                backoffDelayMillis,
                TimeUnit.MILLISECONDS,
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancelSync() {
        workManager.cancelUniqueWork(uniqueWorkName)
    }

    public companion object {
        /** Default sync cadence in minutes. */
        public const val DEFAULT_INTERVAL_MINUTES: Long = 15L

        /** WorkManager's hard minimum for periodic work. */
        private const val MIN_INTERVAL_MINUTES: Long = 15L
    }
}
