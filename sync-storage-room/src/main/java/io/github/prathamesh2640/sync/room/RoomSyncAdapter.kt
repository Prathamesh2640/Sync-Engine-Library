package io.github.prathamesh2640.sync.room

import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import io.github.prathamesh2640.sync.core.store.LocalSyncStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

/**
 * Room-backed [LocalSyncStore] — the durable, offline-first queue the engine drains.
 *
 * It reads state-scoped slices of the host's own entity table (there is no
 * separate operation queue: the entity's [SyncableEntity.syncState] column is the
 * single source of truth) and writes engine outcomes back. Reads go through the
 * host DAO's `@RawQuery` method, supplied here as a function reference; the
 * state/delete writes that Room cannot express generically are issued as
 * parameterised statements on the database.
 *
 * The host DAO is a **plain `@Dao`** — it does not implement any generic
 * interface (a generic `@Dao` crashes Room's KSP with `unexpected jvm signature V`):
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
 *     rawQuery = db.noteDao()::rawQuery,
 *     upsert = db.noteDao()::upsertAll,
 * )
 * val engine = SyncEngine.create(adapter = noteApiAdapter, store = store)
 * ```
 *
 * ### Column-name contract
 * Queries reference the default Room column names for the four [SyncableEntity]
 * properties: `id`, `syncState`, `isDeleted`, `lastModified`. Do not rename them
 * with `@ColumnInfo`.
 *
 * ### Security
 * - **SEC-05:** [tableName] is validated against a strict SQL-identifier pattern
 *   at construction; every value is bound, never string-concatenated.
 * - **SEC-10:** [purgeExpiredTombstones] hard-deletes failed tombstones once they
 *   reach the retention window (age >= `retentionDays`; `0` purges immediately) for
 *   GDPR erasure hygiene.
 *
 * ### Observability
 * The state/delete writes are raw SQL rather than `@Query` methods, so Room does
 * not know about them statically. They are issued inside a Room transaction, which
 * makes Room refresh its [androidx.room.InvalidationTracker] on commit — a host
 * observing the table with a Room `Flow` therefore sees engine writes just as it
 * would see its own DAO writes.
 *
 * @param database the [RoomDatabase] holding the entity table — used for the
 *   generic UPDATE/DELETE statements.
 * @param tableName the entity's table name (from its `@Entity(tableName = …)`).
 *   Validated as a SQL identifier.
 * @param rawQuery the host DAO's `@RawQuery` method (e.g. `db.noteDao()::rawQuery`)
 *   — maps a [SupportSQLiteQuery] to a list of entities.
 * @param upsert the host DAO's `@Upsert` method taking a list (e.g.
 *   `db.noteDao()::upsertAll`) — the engine calls it to persist pulled remote
 *   changes and reconciled conflict winners. A full-entity write Room cannot
 *   express on a type parameter, so the concrete DAO supplies it (mirrors
 *   [rawQuery]).
 * @param clock supplies "now" in epoch ms for tombstone-age math; injectable for
 *   deterministic tests. Defaults to [System.currentTimeMillis].
 * @param ioDispatcher dispatcher the blocking SQLite calls run on; injectable for
 *   tests. Defaults to [Dispatchers.IO].
 */
public class RoomSyncAdapter<T : SyncableEntity>(
    private val database: RoomDatabase,
    tableName: String,
    private val rawQuery: suspend (SupportSQLiteQuery) -> List<T>,
    private val upsert: suspend (List<T>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalSyncStore<T> {

    private val table: String = validateIdentifier(tableName)

    override suspend fun getPending(): List<T> = getByState(SyncState.PENDING)

    override suspend fun getByState(state: SyncState): List<T> = withContext(ioDispatcher) {
        rawQuery(
            SimpleSQLiteQuery(
                "SELECT * FROM `$table` WHERE $COL_STATE = ?",
                arrayOf<Any?>(state.name),
            ),
        )
    }

    override suspend fun getById(id: String): T? = withContext(ioDispatcher) {
        rawQuery(
            SimpleSQLiteQuery(
                "SELECT * FROM `$table` WHERE $COL_ID = ? LIMIT 1",
                arrayOf<Any?>(id),
            ),
        ).firstOrNull()
    }

    override suspend fun getTombstones(): List<T> = withContext(ioDispatcher) {
        rawQuery(SimpleSQLiteQuery("SELECT * FROM `$table` WHERE $COL_DELETED = 1"))
    }

    override suspend fun upsert(entities: List<T>) {
        if (entities.isEmpty()) return
        withContext(ioDispatcher) { upsert.invoke(entities) }
    }

    override suspend fun markSyncState(id: String, state: SyncState): Unit = withContext(ioDispatcher) {
        inTransaction {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE `$table` SET $COL_STATE = ? WHERE $COL_ID = ?",
                arrayOf<Any?>(state.name, id),
            )
        }
    }

    override suspend fun hardDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(ioDispatcher) {
            inTransaction {
                val placeholders = ids.joinToString(separator = ",") { "?" }
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM `$table` WHERE $COL_ID IN ($placeholders)",
                    Array<Any?>(ids.size) { ids[it] },
                )
            }
        }
    }

    override suspend fun purgeExpiredTombstones(retentionDays: Int): Int = withContext(ioDispatcher) {
        val cutoff = clock() - retentionDays.coerceAtLeast(0).toLong() * MILLIS_PER_DAY
        inTransaction {
            database.openHelper.writableDatabase
                .compileStatement(
                    // Retention semantics: a tombstone is expired once it has existed for
                    // at least `retentionDays` (age >= retention), i.e. lastModified <=
                    // cutoff. Using `<=` (not `<`) makes retentionDays = 0 purge everything
                    // immediately and treats the exact window edge as expired.
                    "DELETE FROM `$table` " +
                        "WHERE $COL_DELETED = 1 AND $COL_STATE = ? AND $COL_MODIFIED <= ?",
                )
                // A compiled statement holds a native SQLite handle; close it, or one
                // leaks per sync run for the life of the process.
                .use { statement ->
                    statement.bindString(1, SyncState.FAILED.name)
                    statement.bindLong(2, cutoff)
                    statement.executeUpdateDelete()
                }
        }
    }

    /**
     * Run a raw-SQL write inside a Room transaction.
     *
     * Room only refreshes its [androidx.room.InvalidationTracker] when a transaction
     * commits. Without this, a host observing the table with a Room `Flow` would
     * never be notified of the engine's state/delete writes, because they are issued
     * through `openHelper` rather than a `@Query` method Room knows about.
     */
    // ponytail: one transaction per call, so a run's per-entity markSyncState writes
    // open N transactions. SQLite wraps every bare statement in an implicit
    // transaction anyway, so this is near-free; batch into a single transaction only
    // if a profiler shows write amplification on large batches.
    private fun <R> inTransaction(body: () -> R): R = database.runInTransaction(Callable(body))

    private companion object {
        const val COL_ID = "id"
        const val COL_STATE = "syncState"
        const val COL_DELETED = "isDeleted"
        const val COL_MODIFIED = "lastModified"
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** SEC-05: reject anything that is not a plain SQL identifier. */
        fun validateIdentifier(name: String): String {
            require(name.matches(IDENTIFIER)) {
                "Invalid table name '$name': must match ${IDENTIFIER.pattern}."
            }
            return name
        }
    }
}
