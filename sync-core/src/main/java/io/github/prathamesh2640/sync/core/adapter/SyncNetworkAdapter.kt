package io.github.prathamesh2640.sync.core.adapter

import io.github.prathamesh2640.sync.core.model.SyncableEntity

/**
 * The bridge between the sync engine and a remote backend.
 *
 * Host apps implement this once for their API (or use a provided implementation
 * such as `RetrofitSyncAdapter`). The engine calls these methods to move data
 * across the network; it never talks to the network directly. This is what lets
 * the same engine drive Retrofit, Ktor, gRPC, or a fake in tests.
 *
 * ## Contract
 * - **Never throw.** Every outcome — success, HTTP error, lost connectivity —
 *   must be returned as a [NetworkResult]. An exception thrown here crosses the
 *   library boundary and crashes the host app with the library on the stack.
 * - **Idempotent.** Requests may be retried after a failure. Key server-side
 *   writes on [SyncableEntity.id] (a client-generated UUID) so a retried push
 *   does not create duplicates.
 * - **Suspending, dispatcher-agnostic.** Implementations must be safe to call
 *   from any dispatcher and should switch to their own I/O dispatcher internally.
 *
 * @param T the [SyncableEntity] subtype this adapter transfers.
 */
public interface SyncNetworkAdapter<T : SyncableEntity> {

    /**
     * Push locally-changed entities to the remote.
     *
     * The engine calls this once per entity (a single-element list) so that one
     * item's failure is attributed to that item alone. The parameter is a list so
     * hosts can also drive the adapter with real batches directly.
     *
     * @param payload the entities to upload. May include tombstones
     *   ([SyncableEntity.isDeleted] = `true`) that represent pending deletions.
     * @return [NetworkResult.Success] with [Unit] when the server accepted the
     *   batch, otherwise the matching error branch.
     */
    public suspend fun push(payload: List<T>): NetworkResult<Unit>

    /**
     * Pull entities changed on the remote since a given point in time.
     *
     * @param since Unix epoch milliseconds of the last successful pull. Pass `0`
     *   to fetch everything (initial sync).
     * @return [NetworkResult.Success] wrapping the changed entities, otherwise
     *   the matching error branch.
     */
    public suspend fun pull(since: Long): NetworkResult<List<T>>

    /**
     * Confirm hard-deletion of tombstoned entities on the remote.
     *
     * @param ids the [SyncableEntity.id] values to delete server-side.
     * @return [NetworkResult.Success] with [Unit] when the server confirmed the
     *   deletions, otherwise the matching error branch.
     */
    public suspend fun delete(ids: List<String>): NetworkResult<Unit>
}
