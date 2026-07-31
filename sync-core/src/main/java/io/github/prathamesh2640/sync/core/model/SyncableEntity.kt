package io.github.prathamesh2640.sync.core.model

/**
 * Contract that every entity participating in offline sync must satisfy.
 *
 * Host app data classes implement this interface. The library uses these
 * properties to track the synchronisation lifecycle. The host app is
 * responsible for persisting them (e.g., as Room entity columns).
 *
 * ## Implementing this interface
 * ```kotlin
 * @Entity(tableName = "notes")
 * data class Note(
 *     @PrimaryKey override val id: String = UUID.randomUUID().toString(),
 *     val title: String,
 *     val body: String,
 *     override val lastModified: Long = System.currentTimeMillis(),
 *     override val syncState: SyncState = SyncState.PENDING,
 *     override val isDeleted: Boolean = false
 * ) : SyncableEntity
 * ```
 *
 * ## Idempotency
 * [id] must be a UUID v4 generated client-side at creation time. It is used
 * as the idempotency key for all network requests, so it must be globally
 * unique and must never change after the entity is created.
 *
 * ## Soft deletes (tombstoning)
 * Do **not** remove entities from the local database when the user deletes them.
 * Set [isDeleted] = `true` and persist the record. The sync engine pushes the
 * tombstone to the server and hard-deletes the local row only after the server
 * confirms receipt. This guarantees deletions are not lost while offline.
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

    /**
     * Current position of this entity in the sync lifecycle.
     *
     * The library manages all transitions. Do not write this field directly
     * from host-app code — always drive state changes through the SyncEngine API.
     *
     * @see SyncState
     */
    public val syncState: SyncState

    /**
     * `true` if this record has been deleted locally and is awaiting tombstone
     * confirmation from the server.
     *
     * Entities with `isDeleted = true` must be excluded from all business-logic
     * queries but must remain in the database until the server confirms deletion.
     *
     * Defaults to `false`. Override in your entity class to opt into soft-delete
     * behaviour.
     *
     * Note: Kotlin exposes this to Java as `isDeleted()` (the `is`-prefix is kept
     * for boolean properties), while the other accessors use the standard
     * `getX()` names — so no `@JvmName` is required on any of them.
     */
    public val isDeleted: Boolean
        get() = false
}
