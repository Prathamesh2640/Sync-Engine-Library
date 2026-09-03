package io.github.prathamesh2640.sync.room

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.prathamesh2640.sync.core.model.SyncCounts
import io.github.prathamesh2640.sync.core.model.SyncMetadata
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import io.github.prathamesh2640.sync.core.store.LocalSyncStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed [LocalSyncStore] — the durable, offline-first queue the engine drains.
 *
 * Sync lifecycle ([SyncMetadata] — [SyncState] + the tombstone flag) is **not**
 * a column on the host's own entity table (ADL-022 in `memory.md`); it lives in
 * a small, library-shaped side table you create via migration and name via
 * [metadataTable]. `getPending`/`getTombstones` join it against the host table
 * through the same host-supplied [rawQuery] function reference used for every
 * other read — there is still no generic base DAO (ADL-013).
 *
 * ```kotlin
 * @Dao
 * interface NoteDao {
 *     @Upsert   suspend fun upsertAll(entities: List<Note>)
 *     @RawQuery suspend fun rawQuery(query: SupportSQLiteQuery): List<Note>
 * }
 *
 * val db = Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()
 * val store = RoomSyncAdapter<Note>(
 *     database = db,
 *     tableName = "notes",
 *     metadataTable = "notes_sync_meta",
 *     rawQuery = db.noteDao()::rawQuery,
 *     upsert = db.noteDao()::upsertAll,
 * )
 * val engine = SyncEngine.create(adapter = noteApiAdapter, store = store)
 * ```
 *
 * ### The metadata table
 * [metadataTable] must exist with exactly this shape (create it in a Room
 * `Migration`, never `fallbackToDestructiveMigration()`):
 * ```sql
 * CREATE TABLE notes_sync_meta (
 *     id TEXT NOT NULL PRIMARY KEY,
 *     syncState TEXT NOT NULL,
 *     isDeleted INTEGER NOT NULL DEFAULT 0
 * )
 * ```
 * Its column names (`id`/`syncState`/`isDeleted`) are fixed, not configurable —
 * unlike [tableName], this is a new table the library defines the shape of, so
 * there is no existing host naming convention to accommodate.
 *
 * ### The watermark table (optional)
 * Pass [watermarkTable] to persist the pull watermark across process restarts
 * (otherwise every fresh process re-pulls everything from the start — safe, since
 * [upsert] is idempotent, just not bandwidth-free). When supplied it must exist
 * with exactly this shape (create it in the same `Migration` as [metadataTable]):
 * ```sql
 * CREATE TABLE notes_sync_watermark (
 *     id INTEGER NOT NULL PRIMARY KEY,
 *     value INTEGER NOT NULL
 * )
 * ```
 * It always holds exactly one row (`id = 0`). Leaving [watermarkTable] `null` (the
 * default) requires no schema change at all — existing hosts are unaffected.
 *
 * ### Column-name contract (host entity table only)
 * Queries reference the Room column names for [SyncableEntity.id] and
 * [SyncableEntity.lastModified] — `id`/`lastModified` by default. If your entity
 * renames either with `@ColumnInfo`, pass the real name via
 * [idColumn]/[modifiedColumn].
 *
 * ### Security
 * - **SEC-05:** [tableName] and [metadataTable], plus every column-name
 *   parameter, are validated against a strict SQL-identifier pattern at
 *   construction (identifiers are interpolated into raw SQL — SQLite cannot
 *   `?`-bind an identifier, only a value); every value is bound, never
 *   string-concatenated.
 * - **SEC-10:** [purgeExpiredTombstones] hard-deletes failed tombstones (from
 *   both tables) once they reach the retention window (age >= `retentionDays`;
 *   `0` purges immediately) for GDPR erasure hygiene.
 *
 * ### Observability
 * All metadata reads/writes are raw SQL rather than `@Query`/`@Upsert` methods,
 * so Room does not know about them statically — a bare [androidx.room.withTransaction]
 * around raw `execSQL` does not by itself guarantee Room's
 * [androidx.room.InvalidationTracker] notices the write. Every write here
 * therefore calls [androidx.room.InvalidationTracker.refreshVersionsAsync]
 * after its transaction commits, so Room checks and notifies observers of the
 * touched tables — a host observing either table with a Room `Flow` sees
 * engine writes just as it would see its own DAO writes.
 *
 * @param database the [RoomDatabase] holding both tables.
 * @param tableName the host entity's table name (from its `@Entity(tableName =
 *   …)`). Validated as a SQL identifier.
 * @param metadataTable the sync-metadata side table's name. Validated as a SQL
 *   identifier.
 * @param rawQuery the host DAO's `@RawQuery` method (e.g. `db.noteDao()::rawQuery`)
 *   — maps a [SupportSQLiteQuery] to a list of entities.
 * @param upsert the host DAO's `@Upsert` method taking a list (e.g.
 *   `db.noteDao()::upsertAll`) — the engine calls it to persist pulled remote
 *   changes and reconciled conflict winners. A full-entity write Room cannot
 *   express on a type parameter, so the concrete DAO supplies it (mirrors
 *   [rawQuery]).
 * @param idColumn the [SyncableEntity.id] column name on the host table.
 *   Defaults to `"id"`.
 * @param modifiedColumn the [SyncableEntity.lastModified] column name on the
 *   host table. Defaults to `"lastModified"`.
 * @param clock supplies "now" in epoch ms for tombstone-age math; injectable for
 *   deterministic tests. Defaults to [System.currentTimeMillis].
 * @param ioDispatcher dispatcher the blocking SQLite calls run on; injectable for
 *   tests. Defaults to [Dispatchers.IO].
 * @param watermarkTable optional pull-watermark table name (see "The watermark
 *   table" below). `null` (the default) means this store does not persist the
 *   watermark — [getWatermark]/[setWatermark] behave exactly as
 *   [io.github.prathamesh2640.sync.core.store.LocalSyncStore]'s no-op defaults,
 *   with zero schema requirement. Validated as a SQL identifier when supplied.
 */
public class RoomSyncAdapter<T : SyncableEntity> @JvmOverloads constructor(
    private val database: RoomDatabase,
    tableName: String,
    metadataTable: String,
    private val rawQuery: suspend (SupportSQLiteQuery) -> List<T>,
    private val upsert: suspend (List<T>) -> Unit,
    idColumn: String = "id",
    modifiedColumn: String = "lastModified",
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    watermarkTable: String? = null,
) : LocalSyncStore<T> {

    private val table: String = validateIdentifier(tableName)
    private val metaTable: String = validateIdentifier(metadataTable)
    private val colId: String = validateIdentifier(idColumn)
    private val colModified: String = validateIdentifier(modifiedColumn)
    private val watermarkTableName: String? = watermarkTable?.let { validateIdentifier(it) }

    override suspend fun getPending(limit: Int): List<T> {
        if (limit <= 0) return emptyList()
        return withContext(ioDispatcher) {
            // ORDER BY is what makes the LIMIT meaningful: without it SQLite returns
            // an arbitrary slice of the backlog, and the interface promises the
            // oldest-modified entities first.
            rawQuery(
                SimpleSQLiteQuery(
                    "SELECT t.* FROM `$table` t JOIN `$metaTable` m ON t.$colId = m.id " +
                        "WHERE m.syncState = ? AND m.isDeleted = 0 ORDER BY t.$colModified ASC LIMIT ?",
                    arrayOf<Any?>(SyncState.PENDING.name, limit),
                ),
            )
        }
    }

    override suspend fun getByIds(ids: List<String>): Map<String, T> {
        if (ids.isEmpty()) return emptyMap()
        return withContext(ioDispatcher) {
            val result = LinkedHashMap<String, T>(ids.size)
            for ((placeholders, args) in chunkedIn(ids)) {
                rawQuery(
                    SimpleSQLiteQuery("SELECT * FROM `$table` WHERE $colId IN ($placeholders)", args),
                ).associateByTo(result) { it.id }
            }
            result
        }
    }

    override suspend fun getMetadataByIds(ids: List<String>): Map<String, SyncMetadata> {
        if (ids.isEmpty()) return emptyMap()
        return withContext(ioDispatcher) {
            val result = LinkedHashMap<String, SyncMetadata>(ids.size)
            for ((placeholders, args) in chunkedIn(ids)) {
                database.openHelper.readableDatabase.query(
                    SimpleSQLiteQuery(
                        "SELECT id, syncState, isDeleted FROM `$metaTable` WHERE id IN ($placeholders)",
                        args,
                    ),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        // A syncState string matching no known SyncState (a downgrade, a
                        // hand-edited DB) must not throw out of a store method — skip the
                        // row instead (LocalSyncStore's "must not throw" contract).
                        val state = SyncState.entries.firstOrNull { it.name == cursor.getString(1) } ?: continue
                        result[cursor.getString(0)] = SyncMetadata(syncState = state, isDeleted = cursor.getInt(2) != 0)
                    }
                }
            }
            result
        }
    }

    override suspend fun counts(): SyncCounts = withContext(ioDispatcher) {
        var pending = 0
        var failed = 0
        var conflict = 0
        database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery("SELECT syncState, COUNT(*) FROM `$metaTable` GROUP BY syncState"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                // See getMetadataByIds: an unrecognized syncState string is skipped, not thrown.
                val state = SyncState.entries.firstOrNull { it.name == cursor.getString(0) } ?: continue
                when (state) {
                    SyncState.PENDING -> pending = cursor.getInt(1)
                    SyncState.FAILED -> failed = cursor.getInt(1)
                    SyncState.CONFLICT -> conflict = cursor.getInt(1)
                    else -> Unit
                }
            }
        }
        SyncCounts(pending = pending, failed = failed, conflict = conflict)
    }

    override suspend fun getTombstones(): List<T> = withContext(ioDispatcher) {
        rawQuery(
            SimpleSQLiteQuery("SELECT t.* FROM `$table` t JOIN `$metaTable` m ON t.$colId = m.id WHERE m.isDeleted = 1"),
        )
    }

    override suspend fun getTombstones(limit: Int): List<T> {
        if (limit <= 0) return emptyList()
        return withContext(ioDispatcher) {
            rawQuery(
                SimpleSQLiteQuery(
                    "SELECT t.* FROM `$table` t JOIN `$metaTable` m ON t.$colId = m.id " +
                        "WHERE m.isDeleted = 1 ORDER BY t.$colModified ASC LIMIT ?",
                    arrayOf<Any?>(limit),
                ),
            )
        }
    }

    override suspend fun upsert(entities: List<T>) {
        if (entities.isEmpty()) return
        withContext(ioDispatcher) { upsert.invoke(entities) }
    }

    // Overrides the LocalSyncStore default (upsert then markSyncState as two separate
    // calls) with one transaction, closing the window where a reader (e.g. the pull
    // phase's getMetadataByIds) could see the row upserted but still metadata-less/stale
    // between the two statements.
    override suspend fun enqueue(entity: T) {
        database.withTransaction {
            upsert.invoke(listOf(entity))
            val db = database.openHelper.writableDatabase
            db.execSQL(
                "INSERT OR IGNORE INTO `$metaTable` (id, syncState, isDeleted) VALUES (?, ?, 0)",
                arrayOf<Any?>(entity.id, SyncState.PENDING.name),
            )
            db.execSQL("UPDATE `$metaTable` SET syncState = ? WHERE id = ?", arrayOf<Any?>(SyncState.PENDING.name, entity.id))
        }
        database.invalidationTracker.refreshVersionsAsync()
    }

    // One transaction per call, so a run's per-entity markSyncState writes open N
    // transactions. SQLite wraps every bare statement in an implicit transaction anyway,
    // so this is near-free; revisit only if a profiler shows write amplification on
    // large batches.
    //
    // Upsert as INSERT OR IGNORE + UPDATE rather than SQLite's native
    // `ON CONFLICT ... DO UPDATE` (3.24+): Robolectric's shadow SQLite engine
    // (used by these JVM tests) doesn't support that syntax.
    override suspend fun markSyncState(id: String, state: SyncState) {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            db.execSQL(
                "INSERT OR IGNORE INTO `$metaTable` (id, syncState, isDeleted) VALUES (?, ?, 0)",
                arrayOf<Any?>(id, state.name),
            )
            db.execSQL("UPDATE `$metaTable` SET syncState = ? WHERE id = ?", arrayOf<Any?>(state.name, id))
        }
        database.invalidationTracker.refreshVersionsAsync()
    }

    // Guarded write-back: only stamps SYNCED if the host row's lastModified still
    // matches what was actually pushed — closing the window where a concurrent edit
    // (which bumps lastModified and re-marks PENDING) would otherwise get silently
    // overwritten by a push that started before the edit landed. One statement, no
    // read-then-write gap.
    override suspend fun markSyncedIfUnchanged(id: String, lastModified: Long) {
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE `$metaTable` SET syncState = ? WHERE id = ? AND EXISTS " +
                    "(SELECT 1 FROM `$table` t WHERE t.$colId = ? AND t.$colModified = ?)",
                arrayOf<Any?>(SyncState.SYNCED.name, id, id, lastModified),
            )
        }
        database.invalidationTracker.refreshVersionsAsync()
    }

    override suspend fun markDeleted(id: String) {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            db.execSQL(
                "INSERT OR IGNORE INTO `$metaTable` (id, syncState, isDeleted) VALUES (?, ?, 1)",
                arrayOf<Any?>(id, SyncState.PENDING.name),
            )
            db.execSQL(
                "UPDATE `$metaTable` SET syncState = ?, isDeleted = 1 WHERE id = ?",
                arrayOf<Any?>(SyncState.PENDING.name, id),
            )
        }
        database.invalidationTracker.refreshVersionsAsync()
    }

    override suspend fun hardDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        database.withTransaction { deleteRows(ids) }
        database.invalidationTracker.refreshVersionsAsync()
    }

    override suspend fun purgeExpiredTombstones(retentionDays: Int): Int {
        val purged = database.withTransaction {
            val cutoff = clock() - retentionDays.coerceAtLeast(0).toLong() * MILLIS_PER_DAY
            val db = database.openHelper.writableDatabase

            // Retention semantics: a tombstone is expired once it has existed for at
            // least `retentionDays` (age >= retention), i.e. lastModified <= cutoff.
            // Using `<=` (not `<`) makes retentionDays = 0 purge everything
            // immediately and treats the exact window edge as expired.
            val expiredIds = ArrayList<String>()
            db.query(
                SimpleSQLiteQuery(
                    "SELECT m.id FROM `$metaTable` m JOIN `$table` t ON t.$colId = m.id " +
                        "WHERE m.isDeleted = 1 AND m.syncState = ? AND t.$colModified <= ?",
                    arrayOf<Any?>(SyncState.FAILED.name, cutoff),
                ),
            ).use { cursor ->
                while (cursor.moveToNext()) expiredIds += cursor.getString(0)
            }
            if (expiredIds.isEmpty()) return@withTransaction 0

            deleteRows(expiredIds)
            expiredIds.size
        }
        if (purged > 0) database.invalidationTracker.refreshVersionsAsync()
        return purged
    }

    // Both no-op (falling back to the LocalSyncStore defaults: 0L / does nothing) when
    // watermarkTable wasn't supplied — a store built without it never touches a table
    // that may not exist.
    override suspend fun getWatermark(): Long {
        val watermarkTable = watermarkTableName ?: return 0L
        return withContext(ioDispatcher) {
            var value = 0L
            database.openHelper.readableDatabase.query(
                SimpleSQLiteQuery("SELECT value FROM `$watermarkTable` WHERE id = 0"),
            ).use { cursor -> if (cursor.moveToFirst()) value = cursor.getLong(0) }
            value
        }
    }

    override suspend fun setWatermark(value: Long) {
        val watermarkTable = watermarkTableName ?: return
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO `$watermarkTable` (id, value) VALUES (0, ?)",
                arrayOf<Any?>(value),
            )
        }
        database.invalidationTracker.refreshVersionsAsync()
    }

    /**
     * Split [ids] into `IN (...)` clauses no wider than [MAX_BIND_ARGS], yielding the
     * placeholder string and bound arguments for each. Every id list this store
     * receives is sized by something outside the library's control — the server's
     * pull response, the tombstone count — so no call site may assume it fits in a
     * single statement.
     */
    private fun chunkedIn(ids: List<String>): List<Pair<String, Array<Any?>>> =
        ids.chunked(MAX_BIND_ARGS).map { chunk ->
            chunk.joinToString(separator = ",") { "?" } to Array<Any?>(chunk.size) { chunk[it] }
        }

    /**
     * Delete [ids] from both the host table and the metadata table. The caller
     * supplies the transaction, so a chunked delete still commits atomically.
     */
    private fun deleteRows(ids: List<String>) {
        val db = database.openHelper.writableDatabase
        for ((placeholders, args) in chunkedIn(ids)) {
            db.execSQL("DELETE FROM `$table` WHERE $colId IN ($placeholders)", args)
            db.execSQL("DELETE FROM `$metaTable` WHERE id IN ($placeholders)", args)
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /**
         * Widest `IN (...)` list this store will compile into one statement.
         *
         * SQLite's `SQLITE_MAX_VARIABLE_NUMBER` is **999** on the SQLite bundled with
         * Android below API 33 (it only rose to 32766 with SQLite 3.32); this module's
         * `minSdk` is 24, so the low ceiling is the one that matters. Exceeding it
         * throws `SQLiteException: too many SQL variables`. 900 leaves headroom for the
         * non-id arguments a statement may also bind.
         */
        const val MAX_BIND_ARGS = 900

        val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** SEC-05: reject anything that is not a plain SQL identifier. */
        fun validateIdentifier(name: String): String {
            require(name.matches(IDENTIFIER)) {
                "Invalid identifier '$name': must match ${IDENTIFIER.pattern}."
            }
            return name
        }
    }
}
