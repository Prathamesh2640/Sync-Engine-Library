package com.yourlibrary.sync.core.result

/**
 * A specific reason a sync operation did not succeed.
 *
 * Carried inside [SyncResult.Failure] and [SyncResult.PartialFailure]. The
 * library never throws these — they are values the host app inspects to decide
 * what to show the user or whether to retry. The set of branches is finite and
 * exhaustive; adding one is a source-breaking change for every `when` over it.
 *
 * ```kotlin
 * when (error) {
 *     SyncError.NetworkUnavailable      -> showOfflineBanner()
 *     is SyncError.HttpError            -> log("HTTP ${error.code}")
 *     is SyncError.ConflictUnresolvable -> flagForManualReview(error.entityId)
 *     is SyncError.StorageError         -> report(error.cause)
 * }
 * ```
 */
public sealed class SyncError {

    /**
     * No usable network connection was available, so the sync never left the
     * device. Safe to retry once connectivity returns.
     */
    public data object NetworkUnavailable : SyncError()

    /**
     * The remote was reached but rejected the request with a non-success status.
     *
     * @property code the HTTP status code (e.g. `401`, `500`).
     */
    public data class HttpError(val code: Int) : SyncError()

    /**
     * A conflict between the local and remote versions of an entity could not be
     * resolved (for example, the host app's [com.yourlibrary.sync.core.adapter.ConflictResolver]
     * threw or declined to pick a winner). The entity is left in the
     * [com.yourlibrary.sync.core.model.SyncState.CONFLICT] state for manual handling.
     *
     * @property entityId the [com.yourlibrary.sync.core.model.SyncableEntity.id]
     *   of the entity that could not be reconciled.
     */
    public data class ConflictUnresolvable(val entityId: String) : SyncError()

    /**
     * A local persistence operation failed (database read/write error).
     *
     * @property cause the underlying exception from the storage layer.
     */
    public data class StorageError(val cause: Throwable) : SyncError()
}
