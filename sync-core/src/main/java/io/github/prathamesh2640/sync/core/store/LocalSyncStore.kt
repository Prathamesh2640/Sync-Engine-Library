package io.github.prathamesh2640.sync.core.store

import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity

/**
 * The engine's view of local persistence — the durable source of pending work.
 *
 * A [io.github.prathamesh2640.sync.core.engine.SyncEngine] configured with a
 * store drains it at the start of every run (so pending work survives process
 * death) and writes each entity's outcome back to it. The store is the single
 * source of truth for an entity's [SyncState]; there is no separate operation
 * queue to keep in sync.
 *
 * ## Why this lives in `:sync-core`
 * This interface is deliberately framework-free — it names only [SyncableEntity]
 * and [SyncState], never Room or `android.*`. That keeps `:sync-core` fully
 * JVM-testable and lets any persistence layer back the engine. The Room-backed
 * implementation (`RoomSyncAdapter`) lives in `:sync-storage-room`; tests can
 * supply a trivial in-memory fake.
 *
 * ## Contract
 * - **Never throw across the boundary.** Wrap storage failures; the engine treats
 *   a store call the same way it treats an adapter — a thrown exception must not
 *   crash the host app. Implementations should surface I/O problems by returning
 *   empty/no-op results, not by propagating raw `SQLiteException`s.
 * - **Suspending, dispatcher-agnostic.** Every method must be safe to call from
 *   any dispatcher and should switch to its own I/O dispatcher internally.
 * - **The four sync columns keep their default names.** Implementations that
 *   build queries over the host entity table rely on [SyncableEntity]'s
 *   properties (`id`, `lastModified`, `syncState`, `isDeleted`) mapping to
 *   identically-named columns.
 *
 * @param T the [SyncableEntity] subtype this store persists.
 */
public interface LocalSyncStore<T : SyncableEntity> {

    /**
     * All entities awaiting their first successful sync — i.e. those in
     * [SyncState.PENDING].
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
     * Every soft-deleted entity still held locally — those with
     * [SyncableEntity.isDeleted] = `true`, awaiting tombstone confirmation from
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
     * full entity — every column, not just the sync fields — is written.
     *
     * Passing an empty list is a no-op. Upsert is keyed on [SyncableEntity.id], so
     * re-applying the same remote entity is idempotent.
     *
     * @param entities the entities to insert or replace.
     */
    public suspend fun upsert(entities: List<T>)

    /**
     * Persist a new [SyncState] for the entity identified by [id].
     *
     * This is the write-back path the engine uses after a run: `SYNCED` on
     * success, `FAILED` on a failed push. A no-op if no row has that id.
     *
     * @param id the [SyncableEntity.id] of the row to update.
     * @param state the state to persist.
     */
    public suspend fun markSyncState(id: String, state: SyncState)

    /**
     * Permanently remove the rows for [ids] from local storage.
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
     * A row is purged when it is a tombstone ([SyncableEntity.isDeleted] = `true`)
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
