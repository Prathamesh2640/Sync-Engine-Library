package com.yourlibrary.sync.core.result

/**
 * The outcome of a whole sync run, returned by `SyncEngine.triggerSync()`.
 *
 * A run touches a batch of entities, so the result distinguishes three cases:
 * everything succeeded, some items failed, or the run could not proceed at all.
 * Errors are always returned as values ([SyncError]) — the engine never throws
 * across the public API.
 *
 * ```kotlin
 * when (val result = engine.triggerSync()) {
 *     is SyncResult.Success        -> render(result.syncedCount, result.conflictCount)
 *     is SyncResult.PartialFailure -> retryLater(result.errors)
 *     is SyncResult.Failure        -> show(result.error)
 * }
 * ```
 */
public sealed class SyncResult {

    /**
     * Every entity in the run was processed without failure.
     *
     * @property syncedCount how many entities reached
     *   [com.yourlibrary.sync.core.model.SyncState.SYNCED].
     * @property conflictCount how many entities were reconciled by a
     *   [com.yourlibrary.sync.core.adapter.ConflictResolver] during the run.
     *   Zero when there were no conflicts; these still count as synced.
     */
    public data class Success(
        val syncedCount: Int,
        val conflictCount: Int,
    ) : SyncResult()

    /**
     * The run completed but some entities failed while others succeeded. The
     * successful writes are durable; the failed ones remain queued for retry.
     *
     * @property syncedCount how many entities synced successfully.
     * @property failedCount how many entities failed.
     * @property errors the failures collected during the run, one per failed
     *   entity where available.
     */
    public data class PartialFailure(
        val syncedCount: Int,
        val failedCount: Int,
        val errors: List<SyncError>,
    ) : SyncResult()

    /**
     * The run could not proceed at all — nothing was synced (for example, the
     * network was unavailable or the local store could not be read).
     *
     * @property error the reason the run failed.
     */
    public data class Failure(val error: SyncError) : SyncResult()
}
