package io.github.prathamesh2640.sync.room

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
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
 * so Room does not know about them statically. Writes are issued inside a Room
 * transaction (via [androidx.room.withTransaction]), which makes Room refresh
 * its [androidx.room.InvalidationTracker] on commit — a host observing either
 * table with a Room `Flow` therefore sees engine writes just as it would see
 * its own DAO writes.
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
) : LocalSyncStore<T> {

    private val table: String = validateIdentifier(tableName)
    private val metaTable: String = validateIdentifier(metadataTable)
    private val colId: String = validateIdentifier(idColumn)
    private val colModified: String = validateIdentifier(modifiedColumn)

    override suspend fun getPending(): List<T> = withContext(ioDispatcher) {
        rawQuery(
            SimpleSQLiteQuery(
                "SELECT t.* FROM `$table` t JOIN `$metaTable` m ON t.$colId = m.id WHERE m.syncState = ?",
                arrayOf<Any?>(SyncState.PENDING.name),
            ),
        )
    }

    override suspend fun getById(id: String): T? = withContext(ioDispatcher) {
        rawQuery(
            SimpleSQLiteQuery(
                "SELECT * FROM `$table` WHERE $colId = ? LIMIT 1",
                arrayOf<Any?>(id),
            ),
        ).firstOrNull()
    }

    override suspend fun getMetadata(id: String): SyncMetadata? = withContext(ioDispatcher) {
        database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery("SELECT syncState, isDeleted FROM `$metaTable` WHERE id = ? LIMIT 1", arrayOf<Any?>(id)),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            SyncMetadata(
                syncState = SyncState.valueOf(cursor.getString(0)),
                isDeleted = cursor.getInt(1) != 0,
            )
        }
    }

    override suspend fun getTombstones(): List<T> = withContext(ioDispatcher) {
        rawQuery(
            SimpleSQLiteQuery("SELECT t.* FROM `$table` t JOIN `$metaTable` m ON t.$colId = m.id WHERE m.isDeleted = 1"),
        )
    }

    override suspend fun upsert(entities: List<T>) {
        if (entities.isEmpty()) return
        withContext(ioDispatcher) { upsert.invoke(entities) }
    }

    // ponytail: one transaction per call, so a run's per-entity markSyncState writes
    // open N transactions. SQLite wraps every bare statement in an implicit
    // transaction anyway, so this is near-free; batch into a single transaction only
    // if a profiler shows write amplification on large batches.
    //
    // Upsert as INSERT OR IGNORE + UPDATE rather than SQLite's native
    // `ON CONFLICT ... DO UPDATE` (3.24+): Robolectric's shadow SQLite engine
    // (used by these JVM tests) doesn't support that syntax.
    override suspend fun markSyncState(id: String, state: SyncState): Unit = database.withTransaction {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO `$metaTable` (id, syncState, isDeleted) VALUES (?, ?, 0)",
            arrayOf<Any?>(id, state.name),
        )
        db.execSQL("UPDATE `$metaTable` SET syncState = ? WHERE id = ?", arrayOf<Any?>(state.name, id))
    }

    override suspend fun markDeleted(id: String): Unit = database.withTransaction {
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

    override suspend fun hardDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        database.withTransaction {
            val placeholders = ids.joinToString(separator = ",") { "?" }
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM `$table` WHERE $colId IN ($placeholders)",
                Array<Any?>(ids.size) { ids[it] },
            )
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM `$metaTable` WHERE id IN ($placeholders)",
                Array<Any?>(ids.size) { ids[it] },
            )
        }
    }

    override suspend fun purgeExpiredTombstones(retentionDays: Int): Int = database.withTransaction {
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

        val placeholders = expiredIds.joinToString(separator = ",") { "?" }
        db.execSQL("DELETE FROM `$table` WHERE $colId IN ($placeholders)", Array<Any?>(expiredIds.size) { expiredIds[it] })
        db.execSQL("DELETE FROM `$metaTable` WHERE id IN ($placeholders)", Array<Any?>(expiredIds.size) { expiredIds[it] })
        expiredIds.size
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

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
