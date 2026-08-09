package io.github.prathamesh2640.sync.core.model

/**
 * Contract that every entity participating in offline sync must satisfy.
 *
 * Host app data classes implement this interface. The sync lifecycle itself —
 * [SyncState] and the soft-delete tombstone flag — is **not** a field on this
 * interface; it lives in a library-owned [SyncMetadata] record, keyed by [id],
 * that a [io.github.prathamesh2640.sync.core.store.LocalSyncStore] tracks
 * separately from the host's own entity table. This is deliberate (see
 * ADL-022 in `memory.md`): a host adopting SyncEngine only needs to add
 * [id]/[lastModified] to an existing data class, not sync-state columns.
 *
 * ## Implementing this interface
 * ```kotlin
 * @Entity(tableName = "notes")
 * data class Note(
 *     @PrimaryKey override val id: String = UUID.randomUUID().toString(),
 *     val title: String,
 *     val body: String,
 *     override val lastModified: Long = System.currentTimeMillis(),
 * ) : SyncableEntity
 * ```
 *
 * ## Idempotency
 * [id] must be a UUID v4 generated client-side at creation time. It is used
 * as the idempotency key for all network requests, so it must be globally
 * unique and must never change after the entity is created.
 *
 * ## Enqueueing for sync
 * Creating or updating a row is not, by itself, enough to sync it — there is no
 * column default doing that implicitly anymore. After every local insert/update
 * call `store.markSyncState(id, SyncState.PENDING)` (an upsert: it creates the
 * metadata row if one doesn't exist yet). For a local delete, call
 * `store.markDeleted(id)` instead of setting a field.
 *
 * @see SyncState for the full state transition graph.
 */
public interface SyncableEntity {

    /**
     * Stable, globally unique identifier — must be a UUID v4 string.
     *
     * Generated client-side at creation; never changes after that. Used as the
     * idempotency key for network operations so retried requests do not create
     * duplicate records on the server.
     *
     * (Kotlin already exposes this to Java as `getId()`; no `@JvmName` is needed —
     * and `@JvmName` is not permitted on abstract interface accessors.)
     */
    public val id: String

    /**
     * Unix epoch milliseconds of the last local modification.
     *
     * Set this to `System.currentTimeMillis()` whenever any field changes.
     * The default Last-Write-Wins conflict resolver compares this value —
     * whichever version has the higher timestamp wins.
     */
    public val lastModified: Long
}
