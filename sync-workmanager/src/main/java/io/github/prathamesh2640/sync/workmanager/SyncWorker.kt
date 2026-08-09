package io.github.prathamesh2640.sync.workmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.prathamesh2640.sync.core.result.SyncResult

/**
 * The background job that runs one sync.
 *
 * Internal by design (module-guide): host apps schedule sync through
 * [WorkManagerSyncScheduler] and never reference or subclass this worker. Its
 * `inputData` carries which registered engine to use ([KEY_ENGINE_KEY]), so it
 * resolves the right [io.github.prathamesh2640.sync.core.engine.SyncEngine] from
 * [SyncEngineRegistry] and maps the [SyncResult] onto a WorkManager [Result]:
 *
 * - [SyncResult.Success] → [Result.success]
 * - [SyncResult.PartialFailure] / [SyncResult.Failure] → [Result.retry] (WorkManager
 *   applies the request's exponential backoff)
 *
 * If no engine is registered for the run's key (e.g. the app process was
 * recreated without re-registering), it returns [Result.failure] rather than
 * crashing.
 *
 * As a [CoroutineWorker], `doWork` already runs off the main thread and honours
 * cancellation; besides the engine key it carries no payload, so no credentials
 * or entity data ever enter a WorkManager `Data` object (SEC-01).
 */
internal class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_ENGINE_KEY) ?: DEFAULT_ENGINE_KEY
        val engine = SyncEngineRegistry.acquire(key) ?: return Result.failure()
        return when (engine.triggerSync()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.PartialFailure -> Result.retry()
            is SyncResult.Failure -> Result.retry()
        }
    }

    companion object {
        /**
         * Unique-work-name prefix; the real unique name is scoped per engine key
         * via [uniqueWorkName] so more than one engine can be scheduled in the
         * same process without colliding.
         */
        const val UNIQUE_WORK_NAME_PREFIX: String = "io.github.prathamesh2640.sync.periodic"

        /** `inputData` key carrying which registered engine this run should use. */
        const val KEY_ENGINE_KEY: String = "io.github.prathamesh2640.sync.engineKey"

        /** [SyncEngineRegistry] key used when a host schedules only one engine. */
        const val DEFAULT_ENGINE_KEY: String = "default"

        /** The WorkManager unique-work name for the periodic job scheduled under [engineKey]. */
        fun uniqueWorkName(engineKey: String): String = "$UNIQUE_WORK_NAME_PREFIX:$engineKey"
    }
}
