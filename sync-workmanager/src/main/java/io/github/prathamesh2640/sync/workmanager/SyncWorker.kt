package io.github.prathamesh2640.sync.workmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.prathamesh2640.sync.core.result.SyncResult

/**
 * The background job that runs one sync.
 *
 * Internal by design (module-guide): host apps schedule sync through
 * [WorkManagerSyncScheduler] and never reference or subclass this worker. It
 * resolves the host [io.github.prathamesh2640.sync.core.engine.SyncEngine] from
 * [SyncEngineRegistry] and maps the [SyncResult] onto a WorkManager [Result]:
 *
 * - [SyncResult.Success] → [Result.success]
 * - [SyncResult.PartialFailure] / [SyncResult.Failure] → [Result.retry] (WorkManager
 *   applies the request's exponential backoff)
 *
 * If no engine is registered (e.g. the app process was recreated without
 * re-registering), it returns [Result.failure] rather than crashing.
 *
 * As a [CoroutineWorker], `doWork` already runs off the main thread and honours
 * cancellation; it carries no payload, so no credentials or entity data ever
 * enter a WorkManager `Data` object (SEC-01).
 */
internal class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = SyncEngineRegistry.acquire() ?: return Result.failure()
        return when (engine.triggerSync()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.PartialFailure -> Result.retry()
            is SyncResult.Failure -> Result.retry()
        }
    }

    companion object {
        /** Unique name so repeated scheduling replaces rather than stacks the job. */
        const val UNIQUE_WORK_NAME: String = "io.github.prathamesh2640.sync.periodic"
    }
}
