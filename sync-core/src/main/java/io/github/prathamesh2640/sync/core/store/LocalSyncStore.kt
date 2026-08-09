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
 * - **Never throw across the boundary.** Wrap storage failures; the engine treats
 *   a store call the same way it treats an adapter — a thrown exception must not
 *   crash the host app. Implementations should surface I/O problems by returning
 *   empty/no-op results, not by propagating raw `SQLiteException`s.
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
     * All entities awaiting their first successful sync — i.e. those whose
     * [SyncMetadata.syncState] is [SyncState.PENDING].
     *
     * The engine seeds its in-flight batch from this list, so returning stale or
     * duplicate ids is harmless (the queue coalesces by [SyncableEntity.id]), but
     * returning entities in a state other than `PENDING` is a contract violation.
     *
     * @return the pending entities, oldest-first where the backing store can
     *   preserve order; never `null` (empty when nothing is pending).
     */
    public suspend fun getPending(): List<T>

    /**
     * Look up a single entity by its [SyncableEntity.id].
     *
     * @param id the stable UUID-v4 identifier.
     * @return the entity, or `null` if no row has that id (never throws on a miss).
     */
    public suspend fun getById(id: String): T?

    /**
     * Look up the [SyncMetadata] for the entity identified by [id].
     *
     * @param id the [SyncableEntity.id] of the row to look up.
     * @return the metadata, or `null` if [id] has never been enqueued (i.e.
     *   [markSyncState] or [markDeleted] has never been called for it).
     */
    public suspend fun getMetadata(id: String): SyncMetadata?

    /**
     * Look up several entities at once by [SyncableEntity.id].
     *
     * The batched counterpart to [getById] — the engine's pull phase uses this
     * (once per pull, not once per entity) to avoid an N+1 query pattern when
     * reconciling a batch of remote changes.
     *
     * @param ids the ids to look up.
     * @return a map from id to entity, containing only the ids that matched a
     *   row; never `null` (empty when none matched).
     */
    public suspend fun getByIds(ids: List<String>): Map<String, T>

    /**
     * Look up several entities' [SyncMetadata] at once by id.
     *
     * The batched counterpart to [getMetadata] — see [getByIds].
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
     * This is both the write-back path the engine uses after a run (`SYNCED` on
     * success, `FAILED` on a failed push) **and** the call a host makes after
     * every local insert/update to enqueue that entity for sync (typically
     * `markSyncState(id, SyncState.PENDING)`) — a row's own default state no
     * longer does this implicitly, since sync state does not live on the entity.
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
