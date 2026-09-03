package io.github.prathamesh2640.sync.core.store

import io.github.prathamesh2640.sync.core.model.SyncCounts
import io.github.prathamesh2640.sync.core.model.SyncMetadata
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity

/**
 * The engine's view of local persistence — the durable source of pending work.
 *
 * A [io.github.prathamesh2640.sync.core.engine.SyncEngine] configured with a
 * store drains it at the start of every run (so pending work survives process
 * death) and writes each entity's outcome back to it. The store is the single
 * source of truth for an entity's [SyncState] — held in a [SyncMetadata] record
 * keyed by id, separate from the host's own entity table (ADL-022) — so there is
 * no separate operation queue to keep in sync.
 *
 * ## Why this lives in `:sync-core`
 * This interface is deliberately framework-free — it names only [SyncableEntity],
 * [SyncMetadata] and [SyncState], never Room or `android.*`. That keeps
 * `:sync-core` fully JVM-testable and lets any persistence layer back the
 * engine. The Room-backed implementation (`RoomSyncAdapter`) lives in
 * `:sync-storage-room`; tests can supply a trivial in-memory fake.
 *
 * ## Contract
 * - **Must not throw for ordinary outcomes.** An absent row, an empty result, "no
 *   metadata yet" — these are not exceptional and must come back as an empty/null
 *   result, never a thrown exception. A genuine storage/IO failure (a corrupt
 *   database, a locked file) is a different matter: such an exception may
 *   propagate, and the engine — same as it does for a misbehaving adapter — catches
 *   it at the [io.github.prathamesh2640.sync.core.engine.SyncEngine.triggerSync]
 *   boundary and converts it to [io.github.prathamesh2640.sync.core.result.SyncError.StorageError]
 *   (SEC-12) rather than crashing the host app. `RoomSyncAdapter` follows exactly
 *   this shape: it lets `SQLiteException` escape rather than silently returning an
 *   empty list that would look identical to "nothing pending."
 * - **Suspending, dispatcher-agnostic.** Every method must be safe to call from
 *   any dispatcher and should switch to its own I/O dispatcher internally.
 * - **No metadata row means "not enqueued".** A host row with no [SyncMetadata]
 *   entry has no sync history and never appears in [getPending]/[getTombstones]
 *   — see [markSyncState].
 *
 * @param T the [SyncableEntity] subtype this store persists.
 */
public interface LocalSyncStore<T : SyncableEntity> {

    /**
     * Up to [limit] entities awaiting their first successful sync — i.e. those
     * whose [SyncMetadata.syncState] is [SyncState.PENDING].
     *
     * The engine seeds its in-flight batch from this list, so returning stale or
     * duplicate ids is harmless (the queue coalesces by [SyncableEntity.id]), but
     * returning entities in a state other than `PENDING` is a contract violation
     * — **including a tombstoned entity** ([SyncMetadata.isDeleted] `true`),
     * even though [markDeleted] leaves its `syncState` as `PENDING`. A deletion
     * is confirmed exclusively through [getTombstones]; the engine's push adapter
     * is documented as never seeing a tombstone as content, and that guarantee
     * depends on this method excluding them.
     *
     * **[limit] is not advisory.** The engine passes its
     * [io.github.prathamesh2640.sync.core.engine.SyncEngineConfig.batchSize] — the
     * most it can push in one run — precisely so a host that has been offline long
     * enough to accumulate a large backlog does not load the whole backlog into
     * memory on every run just to send a batch of it. An implementation that
     * ignores [limit] reintroduces that cost. Returning *fewer* than [limit] is
     * always fine; a run drains what it gets and picks up the rest next time.
     *
     * Ordering decides which slice of the backlog a run sees, so return the
     * oldest-modified entities first where the backing store can express it.
     * Progress does not depend on it — a pushed entity leaves `PENDING` either
     * way, so the next run advances to different rows — but oldest-first is what
     * makes a long backlog drain in the order the user created it.
     *
     * @param limit the maximum number of entities to return. Values `<= 0` return
     *   nothing (matching the engine queue's own drain semantics).
     * @return at most [limit] pending entities, oldest-first where the backing
     *   store can preserve order; never `null` (empty when nothing is pending).
     */
    public suspend fun getPending(limit: Int): List<T>

    /**
     * Look up several entities at once by [SyncableEntity.id].
     *
     * The engine's pull phase uses this (once per pull, not once per entity) to
     * avoid an N+1 query pattern when reconciling a batch of remote changes.
     *
     * @param ids the ids to look up.
     * @return a map from id to entity, containing only the ids that matched a
     *   row; never `null` (empty when none matched).
     */
    public suspend fun getByIds(ids: List<String>): Map<String, T>

    /**
     * Look up several entities' [SyncMetadata] at once by id.
     *
     * See [getByIds].
     *
     * @param ids the ids to look up.
     * @return a map from id to metadata, containing only the ids that have been
     *   enqueued before; never `null` (empty when none matched).
     */
    public suspend fun getMetadataByIds(ids: List<String>): Map<String, SyncMetadata>

    /**
     * Counts of entities in each "not yet done" [SyncState] — what
     * [io.github.prathamesh2640.sync.core.engine.SyncEngine.stats] reports.
     *
     * @return the counts; never `null`.
     */
    public suspend fun counts(): SyncCounts

    /**
     * Every soft-deleted entity still held locally — those whose
     * [SyncMetadata.isDeleted] is `true`, awaiting tombstone confirmation from
     * the server before their local row is removed.
     *
     * @return the tombstoned entities; never `null`.
     */
    public suspend fun getTombstones(): List<T>

    /**
     * Up to [limit] tombstoned entities — the same set as [getTombstones], but
     * bounded the way [getPending] bounds the pending backlog: a host that has
     * accumulated a large number of offline deletions should not load all of
     * them into memory just to confirm one batch.
     *
     * Default implementation delegates to [getTombstones] and truncates in
     * memory, so an implementation that doesn't override this gets correct but
     * unbounded-load behavior — override when the backing store can express
     * `LIMIT` directly (see `RoomSyncAdapter`).
     *
     * @param limit the maximum number of entities to return. Values `<= 0`
     *   return nothing.
     * @return at most [limit] tombstoned entities; never `null`.
     */
    public suspend fun getTombstones(limit: Int): List<T> = getTombstones().take(limit.coerceAtLeast(0))

    /**
     * Insert or replace [entities] in local storage.
     *
     * This is the write path the engine uses when applying **pulled** remote
     * changes (and reconciled conflict winners): the authoritative version from
     * the server is persisted so the local store converges on it. Implementations
     * back this with the host DAO's own upsert (e.g. Room's `@Upsert`), so the
     * full entity — every column, not just the id/lastModified — is written.
     *
     * Passing an empty list is a no-op. Upsert is keyed on [SyncableEntity.id], so
     * re-applying the same remote entity is idempotent.
     *
     * @param entities the entities to insert or replace.
     */
    public suspend fun upsert(entities: List<T>)

    /**
     * Persist a new [SyncState] for the entity identified by [id] — **an upsert**:
     * it creates the [SyncMetadata] row if [id] has none yet.
     *
     * This is both a write-back path the engine uses after a run (`FAILED` on a
     * failed push or delete confirmation, `CONFLICT` on an unresolvable pull
     * conflict — see [markSyncedIfUnchanged] for the successful-push case)
     * **and** the call a host makes after every local insert/update to enqueue
     * that entity for sync (typically `markSyncState(id, SyncState.PENDING)`) —
     * a row's own default state no longer does this implicitly, since sync
     * state does not live on the entity.
     *
     * @param id the [SyncableEntity.id] of the row to update.
     * @param state the state to persist.
     */
    public suspend fun markSyncState(id: String, state: SyncState)

    /**
     * Persist [entity] and enqueue it for sync in one call — `upsert(listOf(entity))`
     * followed by `markSyncState(entity.id, SyncState.PENDING)`.
     *
     * This is the call a host makes after every local insert/update (see
     * [markSyncState]'s "how do I enqueue an entity" note): calling `upsert` alone
     * without a matching `markSyncState` leaves the entity silently un-enqueued —
     * no error, nothing in [getPending], it just never syncs. [enqueue] collapses
     * the two-call pattern so that mistake isn't possible to make by omission.
     *
     * A default method built only from [upsert] and [markSyncState], so existing
     * implementations get it for free.
     *
     * @param entity the entity to persist and mark [SyncState.PENDING].
     */
    public suspend fun enqueue(entity: T) {
        upsert(listOf(entity))
        markSyncState(entity.id, SyncState.PENDING)
    }

    /**
     * Mark the entity identified by [id] [SyncState.SYNCED] — but only if [lastModified]
     * still matches the row's current value.
     *
     * The engine calls this instead of [markSyncState] after a successful push. Between
     * the push starting and this write-back landing, a host can edit the same entity —
     * which re-marks it `PENDING` with a newer `lastModified` — and an unconditional
     * `SYNCED` write would silently clobber that edit, permanently losing it (it would
     * never be pushed again). Comparing [lastModified] closes that window: a store
     * backed by real storage (e.g. `RoomSyncAdapter`) can express this as one guarded
     * `UPDATE ... WHERE lastModified = ?` with no read-then-write gap.
     *
     * A default method that ignores the guard and always calls
     * `markSyncState(id, SyncState.SYNCED)`, so existing implementations keep today's
     * behavior until they override it with a real check.
     *
     * @param id the [SyncableEntity.id] of the row that was just pushed.
     * @param lastModified the [SyncableEntity.lastModified] value that was actually
     *   pushed — the write-back applies only if the row hasn't changed since.
     */
    public suspend fun markSyncedIfUnchanged(id: String, lastModified: Long) {
        markSyncState(id, SyncState.SYNCED)
    }

    /**
     * The epoch-ms watermark of the last successful pull, persisted across process
     * restarts.
     *
     * The engine seeds its in-memory watermark from this once per instance and calls
     * [setWatermark] after every pull that advances it. A default method returning `0L`,
     * so an implementation that doesn't override it behaves exactly as before this was
     * added: every fresh process re-pulls everything from the start. That's always
     * correct — [upsert] is idempotent on [SyncableEntity.id] — just not bandwidth-free,
     * which is what overriding this (e.g. `RoomSyncAdapter`'s optional `watermarkTable`)
     * buys back.
     *
     * @return the persisted watermark, or `0L` if none has been persisted (or this
     *   method isn't overridden).
     */
    public suspend fun getWatermark(): Long = 0L

    /**
     * Persist [value] as the new pull watermark. See [getWatermark].
     *
     * A default no-op, so an implementation that doesn't override this pair simply
     * never persists the watermark — the safe, pre-existing behavior.
     *
     * @param value the new watermark to persist.
     */
    public suspend fun setWatermark(value: Long) {
        // No-op default: see getWatermark.
    }

    /**
     * Mark the entity identified by [id] as a tombstone awaiting sync: sets
     * [SyncMetadata.isDeleted] = `true` and [SyncMetadata.syncState] =
     * [SyncState.PENDING] — an upsert, like [markSyncState]. Call this instead of
     * setting a field when the host deletes a record locally.
     *
     * @param id the [SyncableEntity.id] of the row being deleted.
     */
    public suspend fun markDeleted(id: String)

    /**
     * Permanently remove the rows for [ids] from local storage — both the host's
     * own entity row and its [SyncMetadata].
     *
     * Called only after the remote has confirmed a tombstone's deletion, so the
     * record is safe to drop. Passing an empty list is a no-op. Ids that match no
     * row are ignored.
     *
     * @param ids the [SyncableEntity.id] values to hard-delete.
     */
    public suspend fun hardDelete(ids: List<String>)

    /**
     * Hard-delete tombstones that have outlived their retention window, for
     * GDPR "right to erasure" hygiene (SEC-10).
     *
     * A row is purged when it is a tombstone ([SyncMetadata.isDeleted] = `true`)
     * **and** in [SyncState.FAILED] **and** its [SyncableEntity.lastModified] is
     * older than [retentionDays] before now. Never-synced deletions therefore do
     * not linger on disk indefinitely.
     *
     * @param retentionDays how many days a failed tombstone may persist before it
     *   is purged. `0` purges every failed tombstone immediately; negative values
     *   are treated as `0`.
     * @return the number of rows deleted.
     */
    public suspend fun purgeExpiredTombstones(retentionDays: Int): Int
}
