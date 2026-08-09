package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.adapter.ConflictResolver
import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.core.engine.LogLevel
import io.github.prathamesh2640.sync.core.engine.SyncEngine
import io.github.prathamesh2640.sync.core.engine.SyncEngineConfig
import io.github.prathamesh2640.sync.core.model.SyncMetadata
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Default [SyncEngine] implementation.
 *
 * Deliberately `internal`: host apps depend only on the [SyncEngine] interface,
 * obtained from [SyncEngine.create]. This class is free to change without
 * breaking consumers.
 *
 * ### What a run does
 * On [triggerSync] the engine, in order:
 * 1. **Pulls** remote changes (storage-backed engines only). It calls
 *    [SyncNetworkAdapter.pull] since the last watermark and reconciles each remote
 *    entity with local state (see [reconcile]). A brand-new or non-dirty remote
 *    entity is applied and marked `SYNCED`; when a locally-pending entity also
 *    changed remotely the [ConflictResolver] picks the winner, which is persisted
 *    `PENDING` so the push phase sends it back so both sides converge; a conflict
 *    with no resolver leaves the entity `CONFLICT`.
 * 2. **Pushes** the pending batch. It drains the in-memory [SyncQueue] (seeded
 *    from the [LocalSyncStore] when one is configured, including any conflict
 *    winners from step 1) and pushes each entity through the injected
 *    [SyncNetworkAdapter], at most [SyncEngineConfig.maxConcurrentPushes] at a time. Items are
 *    pushed inside a [supervisorScope] so one item's failure never cancels its
 *    siblings (F-08); a failed item is re-queued and written back as `FAILED`,
 *    and retried on subsequent runs up to [SyncEngineConfig.maxRetries]
 *    consecutive failures, after which it is left `FAILED` for good rather than
 *    retried forever.
 * 3. **Confirms deletions** (storage-backed engines only). Tombstones that pushed
 *    successfully are hard-deleted locally once [SyncNetworkAdapter.delete]
 *    confirms them on the remote.
 * 4. **Purges** expired tombstones once per run (SEC-10 / GDPR).
 *
 * Without a [store] the engine is push-only and framework-free exactly as before —
 * steps 1, 3 and 4 are skipped because there is nowhere durable to pull into.
 *
 * ### Concurrency & lifecycle guarantees
 * - **Single in-flight run** (SEC-11): [triggerSync] is guarded by [syncMutex];
 *   a call made while a run is already in progress is a no-op returning
 *   `Success(0, 0)` rather than starting a second concurrent run.
 * - **No leaks** (SEC-04): all work runs on [engineScope], which [close] cancels.
 * - **Never throws across the boundary** (SEC-12): every outcome — including a
 *   misbehaving adapter/store/resolver that throws, or the engine being closed
 *   mid-run — is returned as a [SyncResult].
 *
 * @param adapter moves entities across the network.
 * @param config engine tuning (batch size, retention, etc.).
 * @param dispatcher the dispatcher the engine's scope runs on. Injectable so
 *   tests can supply a deterministic test dispatcher; defaults to
 *   [Dispatchers.Default].
 * @param queue the pending-entity queue. Injectable for tests; defaults to a
 *   fresh in-memory [SyncQueue].
 * @param stateMachine the guarded state holder. Injectable for tests; defaults
 *   to a fresh machine starting at [SyncState.PENDING].
 * @param store optional durable local store. When non-null the engine seeds its
 *   queue from it, writes outcomes back, and performs the pull/delete/purge
 *   phases; when null (the default) the engine is push-only. Declared before
 *   [resolver] so existing positional constructions stay source-compatible.
 * @param resolver optional conflict-resolution strategy for the pull phase.
 */
internal class SyncEngineImpl<T : SyncableEntity>(
    private val adapter: SyncNetworkAdapter<T>,
    private val config: SyncEngineConfig,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val queue: SyncQueue<T> = SyncQueue(),
    private val stateMachine: SyncStateMachine = SyncStateMachine(),
    private val store: LocalSyncStore<T>? = null,
    private val resolver: ConflictResolver<T>? = null,
) : SyncEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Guards against concurrent runs — held for the whole duration of a run. */
    private val syncMutex = Mutex()

    /**
     * Caps how many pushes are in flight at once within a batch. Without this, a
     * large [SyncEngineConfig.batchSize] would fan out one concurrent network call
     * per entity — a thundering herd against the host's own backend and a socket/
     * thread-exhaustion risk on the device.
     */
    private val pushSemaphore = Semaphore(config.maxConcurrentPushes)

    /**
     * Consecutive-failure count per entity id, since the last success. Only ever
     * read/written from [pushPhase], which only runs while [syncMutex] is held, so
     * no extra synchronisation is needed. Backs [SyncEngineConfig.maxRetries]: once
     * an id's count exceeds the configured limit it is no longer re-queued and is
     * left `FAILED` rather than retried forever.
     *
     * In-memory by design, like [pullSince]: it resets on process death, so a
     * long-lived engine instance (e.g. reused across periodic WorkManager runs,
     * as [io.github.prathamesh2640.sync.core.scheduler.SyncScheduler]
     * implementations are expected to do) is what actually bounds the retries.
     * A `FAILED` entity is not automatically resumed after a fresh process starts
     * a new engine instance — it stays `FAILED` in the store until the host
     * changes it back to `PENDING` (e.g. the user edits it again).
     */
    private val retryCounts = HashMap<String, Int>()

    private val closed = AtomicBoolean(false)

    /**
     * Epoch-ms watermark of the last successful pull. In-memory by design: after
     * process death it resets to 0, so the next run re-pulls everything — safe
     * because [LocalSyncStore.upsert] is idempotent (keyed on id).
     */
    private val pullSince = AtomicLong(0L)

    override val syncState: StateFlow<SyncState> get() = stateMachine.state

    /**
     * Enqueue an entity for the next run. Internal seam used by tests to seed the
     * queue without a [LocalSyncStore]. Not part of the public API.
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
            // SEC-12: the store/adapter contracts forbid throwing, but defend the
            // boundary — a misbehaving collaborator must not crash the host app.
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
        log(LogLevel.DEBUG) { "sync run starting (store=${store != null})" }
        moveToSyncing()

        // Pull first so conflicts are detected while the local copy is still
        // PENDING; resolved winners are re-queued PENDING and pushed below.
        val pull = if (store != null) pullPhase() else PullOutcome.EMPTY
        val push = pushPhase()
        if (store != null) {
            confirmDeletions(store)
            store.purgeExpiredTombstones(config.tombstoneRetentionDays)
        }

        // Push errors are surfaced before pull/conflict errors for callers that
        // inspect errors.first().
        val errors = push.errors + pull.errors
        val syncedCount = push.syncedCount + pull.downloadedCount

        return finish(errors = errors, syncedCount = syncedCount, conflictCount = pull.conflictCount)
    }

    // --- Push phase -----------------------------------------------------------

    private class PushOutcome(val syncedCount: Int, val errors: List<SyncError>)

    private suspend fun pushPhase(): PushOutcome {
        // Storage-backed engines: seed the in-memory queue from the durable store
        // so pending work survives process death. Coalescing by id makes
        // re-seeding an already-queued entity harmless. No-op without a store.
        store?.let { queue.enqueueAll(it.getPending()) }

        val batch = queue.drainBatch(config.batchSize)
        if (batch.isEmpty()) return PushOutcome(syncedCount = 0, errors = emptyList())

        // Push each item concurrently, capped at config.maxConcurrentPushes in flight.
        // supervisorScope means one failing child does not cancel the others;
        // pushOne never throws, so awaitAll() only ever unwraps a real
        // CancellationException (engine closed / caller cancelled), which
        // triggerSync handles.
        val outcomes: List<Pair<T, NetworkResult<Unit>>> = supervisorScope {
            batch.map { entity -> async { entity to pushSemaphore.withPermit { pushOne(entity) } } }.awaitAll()
        }

        val failures = outcomes.filter { it.second !is NetworkResult.Success }
        val successes = outcomes.filter { it.second is NetworkResult.Success }

        // A success clears any accumulated retry count for that id.
        successes.forEach { retryCounts.remove(it.first.id) }

        // Re-queue failed items so they are retried on the next run — unless
        // they have now failed more than maxRetries times in a row, in which
        // case they are left FAILED for good rather than retried forever.
        val exhausted = HashSet<String>()
        failures.forEach { (entity, _) ->
            val attempts = (retryCounts[entity.id] ?: 0) + 1
            if (attempts <= config.maxRetries) {
                retryCounts[entity.id] = attempts
                queue.enqueue(entity)
            } else {
                retryCounts.remove(entity.id) // give up; a future edit starts fresh
                exhausted += entity.id
            }
        }
        if (exhausted.isNotEmpty()) {
            log(LogLevel.WARN) { "giving up after ${config.maxRetries} retries: ${exhausted.size} entit${if (exhausted.size == 1) "y" else "ies"}" }
        }

        // Persist each entity's outcome to the durable store: SYNCED for accepted
        // items, FAILED for rejected ones. No-op without a store.
        store?.let { s ->
            val failedIds = failures.mapTo(HashSet(failures.size)) { it.first.id }
            for ((entity, _) in outcomes) {
                s.markSyncState(entity.id, if (entity.id in failedIds) SyncState.FAILED else SyncState.SYNCED)
            }
        }

        return PushOutcome(
            syncedCount = outcomes.size - failures.size,
            errors = failures.map { it.second.toSyncError() },
        )
    }

    private suspend fun pushOne(entity: T): NetworkResult<Unit> =
        try {
            adapter.push(listOf(entity))
        } catch (cancellation: CancellationException) {
            throw cancellation // never swallow cancellation — structured concurrency
        } catch (throwable: Throwable) {
            // The adapter contract forbids throwing, but defend the boundary.
            NetworkResult.UnknownError(throwable)
        }

    // --- Pull phase -----------------------------------------------------------

    private class PullOutcome(
        val downloadedCount: Int,
        val conflictCount: Int,
        val errors: List<SyncError>,
    ) {
        companion object {
            val EMPTY = PullOutcome(downloadedCount = 0, conflictCount = 0, errors = emptyList())
        }
    }

    /**
     * Pull remote changes since the watermark and reconcile them into [store]
     * (guaranteed non-null by the caller).
     *
     * Downloaded entities (no local conflict) are persisted `SYNCED` and counted
     * as downloaded. Conflict winners are persisted `PENDING` so the following
     * push phase sends them back, converging both sides; they are counted via
     * [PullOutcome.conflictCount] and will be tallied as synced once pushed.
     */
    private suspend fun pullPhase(): PullOutcome {
        val store = this.store ?: return PullOutcome.EMPTY

        val remote = when (val result = pullRemote(pullSince.get())) {
            is NetworkResult.Success -> result.data
            else -> return PullOutcome(downloadedCount = 0, conflictCount = 0, errors = listOf(result.toListSyncError()))
        }
        if (remote.isEmpty()) return PullOutcome.EMPTY

        val downloads = ArrayList<T>(remote.size)
        val winners = ArrayList<T>()
        val conflictErrors = ArrayList<SyncError>()
        var maxSeen = pullSince.get()

        for (entity in remote) {
            maxSeen = maxOf(maxSeen, entity.lastModified)
            val localEntity = store.getById(entity.id)
            val localMeta = store.getMetadata(entity.id)
            when (val decision = reconcile(localEntity, localMeta, entity)) {
                is Reconciliation.Apply -> downloads += decision.winner
                is Reconciliation.Resolved -> winners += decision.winner
                is Reconciliation.Unresolvable -> {
                    store.markSyncState(entity.id, SyncState.CONFLICT)
                    conflictErrors += SyncError.ConflictUnresolvable(entity.id)
                }
                Reconciliation.Skip -> Unit
            }
        }

        if (downloads.isNotEmpty()) {
            store.upsert(downloads)
            for (entity in downloads) store.markSyncState(entity.id, SyncState.SYNCED)
        }
        if (winners.isNotEmpty()) {
            store.upsert(winners)
            for (entity in winners) store.markSyncState(entity.id, SyncState.PENDING)
        }

        pullSince.set(maxSeen)
        return PullOutcome(downloadedCount = downloads.size, conflictCount = winners.size, errors = conflictErrors)
    }

    private suspend fun pullRemote(since: Long): NetworkResult<List<T>> =
        try {
            adapter.pull(since)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            NetworkResult.UnknownError(throwable)
        }

    /**
     * Decide what to do with a single remote entity given its local counterpart.
     *
     * Conflict rule (generic, content-agnostic): a local row is "clean" only when
     * its [SyncMetadata.syncState] is [SyncState.SYNCED]; any other state
     * (PENDING/FAILED/CONFLICT) means it holds local changes the server has not
     * accepted. A remote change for a dirty local row is a genuine conflict,
     * resolved by the [resolver] if one is set. A clean local row (or none at all,
     * or one with no sync history yet) lets the remote copy win.
     */
    private fun reconcile(local: T?, localMeta: SyncMetadata?, remote: T): Reconciliation<T> = when {
        local == null || localMeta == null -> Reconciliation.Apply(remote)
        // A local, unconfirmed deletion wins until the server confirms it.
        localMeta.isDeleted -> Reconciliation.Skip
        // Clean local row → remote is authoritative.
        localMeta.syncState == SyncState.SYNCED -> Reconciliation.Apply(remote)
        // Dirty local row + remote change → conflict.
        else -> resolveConflict(local, remote)
    }

    private fun resolveConflict(local: T, remote: T): Reconciliation<T> {
        val resolver = this.resolver ?: return Reconciliation.Unresolvable
        return try {
            Reconciliation.Resolved(resolver.resolve(local, remote))
        } catch (throwable: Throwable) {
            // A resolver that throws or declines is treated as unresolvable — the
            // entity is flagged for manual handling rather than crashing the run.
            Reconciliation.Unresolvable
        }
    }

    private sealed interface Reconciliation<out T> {
        /** Apply [winner] as the authoritative version (no conflict). */
        data class Apply<out T>(val winner: T) : Reconciliation<T>
        /** A conflict the resolver settled; [winner] is the reconciled version. */
        data class Resolved<out T>(val winner: T) : Reconciliation<T>
        /** A conflict with no resolver (or the resolver failed). */
        data object Unresolvable : Reconciliation<Nothing>
        /** Leave local state untouched (e.g. a pending local deletion). */
        data object Skip : Reconciliation<Nothing>
    }

    // --- Delete-confirmation phase -------------------------------------------

    /**
     * Confirm and finalise deletions: tombstones that pushed successfully (now
     * [SyncState.SYNCED]) are hard-deleted locally once the remote confirms them.
     */
    private suspend fun confirmDeletions(store: LocalSyncStore<T>) {
        val tombstones = store.getTombstones()
        val confirmable = tombstones.filter { store.getMetadata(it.id)?.syncState == SyncState.SYNCED }
        if (confirmable.isEmpty()) return

        val ids = confirmable.map { it.id }
        val result = try {
            adapter.delete(ids)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            NetworkResult.UnknownError(throwable)
        }
        // Only drop local rows the server actually confirmed. A failed confirmation
        // leaves the tombstones in place to retry next run — deletions are never lost.
        if (result is NetworkResult.Success) store.hardDelete(ids)
    }

    // --- Result & state -------------------------------------------------------

    private suspend fun finish(errors: List<SyncError>, syncedCount: Int, conflictCount: Int): SyncResult {
        errors.forEach { log(LogLevel.ERROR) { "sync error: ${describe(it)}" } }
        return when {
            errors.isEmpty() -> {
                stateMachine.transitionTo(SyncState.SYNCED)
                log(LogLevel.INFO) { "sync finished: synced=$syncedCount conflicts=$conflictCount" }
                SyncResult.Success(syncedCount = syncedCount, conflictCount = conflictCount)
            }

            // Failures are all conflicts (nothing else went wrong) → CONFLICT,
            // which the host can surface distinctly from a transport/HTTP failure.
            errors.all { it is SyncError.ConflictUnresolvable } -> {
                stateMachine.transitionTo(SyncState.CONFLICT)
                log(LogLevel.INFO) { "sync finished: synced=$syncedCount conflicts=$conflictCount (unresolved conflicts present)" }
                if (syncedCount == 0) SyncResult.Failure(errors.first())
                else SyncResult.PartialFailure(syncedCount, errors.size, errors)
            }

            syncedCount == 0 -> {
                stateMachine.transitionTo(SyncState.FAILED)
                log(LogLevel.INFO) { "sync finished: synced=0 failed=${errors.size}" }
                SyncResult.Failure(errors.first())
            }

            else -> {
                stateMachine.transitionTo(SyncState.FAILED)
                log(LogLevel.INFO) { "sync finished: synced=$syncedCount failed=${errors.size}" }
                SyncResult.PartialFailure(syncedCount = syncedCount, failedCount = errors.size, errors = errors)
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

    private fun closedFailure(): SyncResult =
        SyncResult.Failure(SyncError.StorageError(IllegalStateException("SyncEngine has been closed")))

    // --- Diagnostics (SEC-06) --------------------------------------------------

    /**
     * Emit a diagnostic line iff [config]'s configured [LogLevel] is at least as
     * verbose as [level]. Never called with entity content, server response
     * bodies, or auth material — only sync-job identifiers (UUIDs), state names,
     * and error codes/types, per SEC-06. [message] is lazy so nothing is built
     * when logging is disabled (the default, [LogLevel.NONE]).
     */
    private inline fun log(level: LogLevel, message: () -> String) {
        if (level != LogLevel.NONE && config.logLevel.ordinal >= level.ordinal) {
            println("[SyncEngine] $level: ${message()}")
        }
    }

    /** A safe, non-PII description of [error] — codes/types/ids only, never a raw message or cause. */
    private fun describe(error: SyncError): String = when (error) {
        SyncError.NetworkUnavailable -> "NetworkUnavailable"
        is SyncError.HttpError -> "HttpError(code=${error.code})"
        is SyncError.ConflictUnresolvable -> "ConflictUnresolvable(id=${error.entityId})"
        is SyncError.StorageError -> "StorageError(${error.cause::class.simpleName})"
    }
}

/**
 * Map a non-success push [NetworkResult] onto the public [SyncError] vocabulary.
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

/** As [toSyncError] but for the list-returning pull result. */
private fun NetworkResult<List<*>>.toListSyncError(): SyncError = when (this) {
    is NetworkResult.Success -> error("Success is not an error outcome")
    is NetworkResult.HttpError -> SyncError.HttpError(code)
    is NetworkResult.NetworkError -> SyncError.NetworkUnavailable
    is NetworkResult.UnknownError -> SyncError.StorageError(cause)
}
