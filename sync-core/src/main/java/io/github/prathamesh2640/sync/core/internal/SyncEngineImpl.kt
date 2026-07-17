package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.core.engine.SyncEngine
import io.github.prathamesh2640.sync.core.engine.SyncEngineConfig
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import io.github.prathamesh2640.sync.core.result.SyncError
import io.github.prathamesh2640.sync.core.result.SyncResult
import io.github.prathamesh2640.sync.core.store.LocalSyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [SyncEngine] implementation.
 *
 * Deliberately `internal`: host apps depend only on the [SyncEngine] interface,
 * obtained from [SyncEngine.create]. This class is free to change without
 * breaking consumers.
 *
 * ### What this version does
 * It owns the sync lifecycle for the entities held in its in-memory [SyncQueue]:
 * on [triggerSync] it drains a batch, pushes each entity through the injected
 * [SyncNetworkAdapter], and drives its observable [syncState] through a
 * [SyncStateMachine]. Batch items are pushed inside a [supervisorScope] so one
 * item's failure never cancels its siblings (F-08). Failed items are re-queued
 * for the next run.
 *
 * ### Storage-backed queue (Commit 7)
 * When constructed with a [LocalSyncStore], each run seeds the in-memory queue
 * from [LocalSyncStore.getPending] (so pending work survives process death),
 * writes every entity's outcome back to the store (`SYNCED` on success, `FAILED`
 * on a rejected push), and purges expired tombstones once per run (SEC-10).
 * Constructed without a store, the queue is fed internally/in tests exactly as
 * before — the store path is fully additive and defaulted off.
 *
 * ### What is intentionally deferred
 * Pull/merge and conflict resolution remain deferred:
 * [SyncResult.Success.conflictCount] is always `0` and no
 * [io.github.prathamesh2640.sync.core.adapter.ConflictResolver] is wired yet.
 *
 * ### Concurrency & lifecycle guarantees
 * - **Single in-flight run** (SEC-11): [triggerSync] is guarded by [syncMutex];
 *   a call made while a run is already in progress is a no-op returning
 *   `Success(0, 0)` rather than starting a second concurrent run.
 * - **No leaks** (SEC-04): all work runs on [engineScope], which [close] cancels.
 * - **Never throws across the boundary** (SEC-12): every outcome — including a
 *   misbehaving adapter that throws, or the engine being closed mid-run — is
 *   returned as a [SyncResult].
 *
 * @param adapter moves entities across the network.
 * @param config engine tuning (batch size, etc.).
 * @param dispatcher the dispatcher the engine's scope runs on. Injectable so
 *   tests can supply a deterministic test dispatcher; defaults to
 *   [Dispatchers.Default].
 * @param queue the pending-entity queue. Injectable for tests; defaults to a
 *   fresh in-memory [SyncQueue].
 * @param stateMachine the guarded state holder. Injectable for tests; defaults
 *   to a fresh machine starting at [SyncState.PENDING].
 * @param store optional durable local store. When non-null the engine seeds its
 *   queue from it and writes outcomes back; when null (the default) the engine
 *   behaves exactly as the pre-storage push-only implementation. Declared last
 *   so existing positional constructions stay source-compatible.
 */
internal class SyncEngineImpl<T : SyncableEntity>(
    private val adapter: SyncNetworkAdapter<T>,
    private val config: SyncEngineConfig,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val queue: SyncQueue<T> = SyncQueue(),
    private val stateMachine: SyncStateMachine = SyncStateMachine(),
    private val store: LocalSyncStore<T>? = null,
) : SyncEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Guards against concurrent runs — held for the whole duration of a run. */
    private val syncMutex = Mutex()

    private val closed = AtomicBoolean(false)

    override val syncState: StateFlow<SyncState> get() = stateMachine.state

    /**
     * Enqueue an entity for the next run. Internal seam used by tests and, in a
     * later commit, by the storage adapter. Not part of the public API.
     */
    internal suspend fun enqueue(entity: T) {
        queue.enqueue(entity)
    }

    override suspend fun triggerSync(): SyncResult {
        if (closed.get()) return closedFailure()

        // SEC-11: only one run at a time. A second concurrent call is a no-op,
        // not a second sync. tryLock never suspends, so this stays cheap.
        if (!syncMutex.tryLock()) return SyncResult.Success(syncedCount = 0, conflictCount = 0)

        return try {
            // Run the batch on the engine's own scope so close() can cancel it.
            engineScope.async { runOnce() }.await()
        } catch (cancellation: CancellationException) {
            // If we were closed, the cancellation is expected — report a clean
            // failure instead of crashing the caller. Otherwise the *caller's*
            // coroutine was cancelled: honour structured concurrency and rethrow.
            if (closed.get()) closedFailure() else throw cancellation
        } catch (throwable: Throwable) {
            // SEC-12: the LocalSyncStore contract forbids throwing, but defend the
            // boundary — a misbehaving store must not crash the host app. Surface
            // it as a clean Failure carrying the cause.
            SyncResult.Failure(SyncError.StorageError(throwable))
        } finally {
            syncMutex.unlock()
        }
    }

    override fun close() {
        // Idempotent: only the first close cancels the scope (SEC-04).
        if (closed.getAndSet(true)) return
        engineScope.cancel()
    }

    private suspend fun runOnce(): SyncResult {
        moveToSyncing()

        // Storage-backed engines (Commit 7): seed the in-memory queue from the
        // durable store so pending work survives process death. Coalescing by id
        // makes re-seeding an already-queued entity harmless. No-op without a store.
        store?.let { queue.enqueueAll(it.getPending()) }

        val batch = queue.drainBatch(config.batchSize)
        if (batch.isEmpty()) {
            stateMachine.transitionTo(SyncState.SYNCED)
            // Nothing to push, but still honour tombstone retention (SEC-10).
            store?.purgeExpiredTombstones(config.tombstoneRetentionDays)
            return SyncResult.Success(syncedCount = 0, conflictCount = 0)
        }

        // Push each item concurrently. supervisorScope means one failing child
        // does not cancel the others; pushOne never throws, so awaitAll() only
        // ever unwraps a real CancellationException (engine closed / caller
        // cancelled), which triggerSync handles.
        val outcomes: List<Pair<T, NetworkResult<Unit>>> = supervisorScope {
            batch.map { entity -> async { entity to pushOne(entity) } }.awaitAll()
        }

        val failures = outcomes.filter { it.second !is NetworkResult.Success }
        val syncedCount = outcomes.size - failures.size

        // Re-queue failed items so they are retried on the next run.
        failures.forEach { queue.enqueue(it.first) }

        // Persist each entity's outcome to the durable store (Commit 7): SYNCED
        // for accepted items, FAILED for rejected ones. The store is the single
        // source of truth for entity state; the in-memory re-queue above drives
        // the immediate retry. No-op without a store.
        store?.let { s ->
            val failedIds = failures.mapTo(HashSet(failures.size)) { it.first.id }
            for ((entity, _) in outcomes) {
                s.markSyncState(
                    entity.id,
                    if (entity.id in failedIds) SyncState.FAILED else SyncState.SYNCED,
                )
            }
            // Tombstone retention hygiene, once per run (SEC-10 / GDPR).
            s.purgeExpiredTombstones(config.tombstoneRetentionDays)
        }

        val errors = failures.map { it.second.toSyncError() }

        return when {
            failures.isEmpty() -> {
                stateMachine.transitionTo(SyncState.SYNCED)
                SyncResult.Success(syncedCount = syncedCount, conflictCount = 0)
            }

            syncedCount == 0 -> {
                stateMachine.transitionTo(SyncState.FAILED)
                SyncResult.Failure(errors.first())
            }

            else -> {
                stateMachine.transitionTo(SyncState.FAILED)
                SyncResult.PartialFailure(
                    syncedCount = syncedCount,
                    failedCount = failures.size,
                    errors = errors,
                )
            }
        }
    }

    /**
     * Normalise the state to [SyncState.SYNCING] using only legal transitions:
     * a terminal state (SYNCED/FAILED/CONFLICT) first returns to PENDING, then
     * PENDING advances to SYNCING. Because [syncMutex] serialises runs, the
     * machine is never already in SYNCING when this is called.
     */
    private suspend fun moveToSyncing() {
        when (stateMachine.current) {
            SyncState.SYNCED, SyncState.FAILED, SyncState.CONFLICT ->
                stateMachine.transitionTo(SyncState.PENDING)
            else -> Unit
        }
        if (stateMachine.current == SyncState.PENDING) {
            stateMachine.transitionTo(SyncState.SYNCING)
        }
    }

    private suspend fun pushOne(entity: T): NetworkResult<Unit> =
        try {
            adapter.push(listOf(entity))
        } catch (cancellation: CancellationException) {
            throw cancellation // never swallow cancellation — structured concurrency
        } catch (throwable: Throwable) {
            // The adapter contract forbids throwing, but defend the boundary:
            // a misbehaving adapter must not crash the host app.
            NetworkResult.UnknownError(throwable)
        }

    private fun closedFailure(): SyncResult =
        SyncResult.Failure(SyncError.StorageError(IllegalStateException("SyncEngine has been closed")))
}

/**
 * Map a non-success [NetworkResult] onto the public [SyncError] vocabulary.
 *
 * Note the imperfect fit for [NetworkResult.UnknownError]: the locked public
 * [SyncError] has no "unexpected" branch, so it is reported as
 * [SyncError.StorageError] carrying the original cause. See memory.md
 * ISSUE-014 — adding a dedicated branch is a future, source-breaking change.
 */
private fun NetworkResult<Unit>.toSyncError(): SyncError = when (this) {
    is NetworkResult.Success -> error("Success is not an error outcome")
    is NetworkResult.HttpError -> SyncError.HttpError(code)
    is NetworkResult.NetworkError -> SyncError.NetworkUnavailable
    is NetworkResult.UnknownError -> SyncError.StorageError(cause)
}
