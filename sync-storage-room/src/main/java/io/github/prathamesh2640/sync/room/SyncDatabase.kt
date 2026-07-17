package io.github.prathamesh2640.sync.room

import androidx.room.RoomDatabase

/**
 * Optional base class a host app's Room database can extend to signal that it
 * participates in SyncEngine persistence.
 *
 * In the single-source design there is no library-owned table: an entity's
 * [io.github.prathamesh2640.sync.core.model.SyncableEntity.syncState] column is
 * the queue, and `RoomSyncAdapter` reads/writes the host's own entity tables.
 * This class therefore adds no schema — it is a documented extension point and a
 * home for shared migration utilities in future versions.
 *
 * ```kotlin
 * @Database(entities = [Note::class], version = 1, exportSchema = true)
 * abstract class AppDatabase : SyncDatabase() {
 *     abstract fun noteDao(): NoteDao
 * }
 * ```
 *
 * ## Migration safety (contract)
 * Never configure `fallbackToDestructiveMigration()` on a database that holds
 * syncable data — it drops every table on a version bump, silently destroying
 * unsynced local changes and tombstones. Always ship an explicit [androidx.room.migration.Migration],
 * and keep migrations additive (no column/table drops in a patch release).
 *
 * Extending this class is not required to use [RoomSyncAdapter]; any
 * [RoomDatabase] works. It exists so host databases can share a common, clearly
 * documented supertype.
 */
public abstract class SyncDatabase : RoomDatabase()
