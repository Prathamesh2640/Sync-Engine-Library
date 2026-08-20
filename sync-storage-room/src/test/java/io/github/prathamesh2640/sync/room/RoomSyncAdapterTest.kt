package io.github.prathamesh2640.sync.room

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.InvalidationTracker
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end integration tests for [RoomSyncAdapter] against a real in-memory
 * Room database. Also the compile-time proof that a plain `@Dao` with a
 * `@RawQuery` method wires cleanly into [RoomSyncAdapter], and that the sync
 * metadata side table (ADL-022) is a real, host-created table this store reads
 * and writes purely via raw SQL — never through a `@Dao` of its own.
 */
@RunWith(AndroidJUnit4::class)
class RoomSyncAdapterTest {

    private lateinit var db: TestDatabase
    private lateinit var store: RoomSyncAdapter<TestNote>

    // Fixed "now" so tombstone-age math is deterministic.
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSyncAdapter(
            database = db,
            tableName = "notes",
            metadataTable = "notes_sync_meta",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(id: String, lastModified: Long = now, title: String = "t-$id") =
        TestNote(id = id, title = title, lastModified = lastModified)

    /** Single-item lookups aren't on the public [RoomSyncAdapter] API (the batch form covers it) — these fixtures use it via [getByIds]/[getMetadataByIds] so the tests below don't need to change shape. */
    private suspend fun <T : SyncableEntity> RoomSyncAdapter<T>.getById(id: String): T? = getByIds(listOf(id))[id]
    private suspend fun <T : SyncableEntity> RoomSyncAdapter<T>.getMetadata(id: String) = getMetadataByIds(listOf(id))[id]

    /** Insert a host row plus arbitrary sync metadata directly — mirrors a pre-existing fixture, not the public API (`markDeleted` always resets state to PENDING, which not every fixture here wants). */
    private suspend fun seed(id: String, state: SyncState, deleted: Boolean = false, lastModified: Long = now) {
        db.noteDao().upsert(note(id, lastModified))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO notes_sync_meta (id, syncState, isDeleted) VALUES (?, ?, ?)",
            arrayOf<Any?>(id, state.name, if (deleted) 1 else 0),
        )
    }

    // --- core lifecycle: PENDING -> SYNCED ------------------------------------

    @Test
    fun pending_to_synced_flow() = runTest {
        seed("a", SyncState.PENDING)

        assertEquals(listOf("a"), store.getPending(limit = 100).map { it.id })

        store.markSyncState("a", SyncState.SYNCED)

        assertTrue("no longer pending", store.getPending(limit = 100).isEmpty())
        assertEquals(SyncState.SYNCED, store.getMetadata("a")?.syncState)
    }

    @Test
    fun getPending_caps_at_limit_and_returns_oldest_first() = runTest {
        // Seeded newest-first so insertion order can't be what makes this pass.
        seed("newest", SyncState.PENDING, lastModified = now + 300)
        seed("middle", SyncState.PENDING, lastModified = now + 200)
        seed("oldest", SyncState.PENDING, lastModified = now + 100)

        assertEquals(listOf("oldest", "middle"), store.getPending(limit = 2).map { it.id })
        assertEquals(listOf("oldest", "middle", "newest"), store.getPending(limit = 10).map { it.id })
    }

    @Test
    fun getPending_returns_nothing_for_a_non_positive_limit() = runTest {
        seed("a", SyncState.PENDING)

        assertTrue(store.getPending(limit = 0).isEmpty())
        assertTrue("a negative limit must not become SQLite's unbounded LIMIT -1", store.getPending(limit = -1).isEmpty())
    }

    @Test
    fun getById_returns_null_on_miss() = runTest {
        assertNull(store.getById("does-not-exist"))
    }

    @Test
    fun getMetadata_returns_null_when_never_enqueued() = runTest {
        db.noteDao().upsert(note("orphan")) // host row exists, but never marked for sync
        assertNull(store.getMetadata("orphan"))
        assertTrue("an un-enqueued row is not pending", store.getPending(limit = 100).isEmpty())
    }

    @Test
    fun getByIds_and_getMetadataByIds_batch_lookup() = runTest {
        seed("a", SyncState.PENDING)
        seed("b", SyncState.SYNCED, deleted = true)

        val entities = store.getByIds(listOf("a", "b", "missing"))
        assertEquals(setOf("a", "b"), entities.keys)

        val metas = store.getMetadataByIds(listOf("a", "b", "missing"))
        assertEquals(SyncState.PENDING, metas["a"]?.syncState)
        assertEquals(SyncState.SYNCED, metas["b"]?.syncState)
        assertTrue(metas["b"]?.isDeleted == true)
        assertTrue("unmatched ids are simply absent", "missing" !in metas)
    }

    @Test
    fun getByIds_and_getMetadataByIds_are_no_ops_on_empty_input() = runTest {
        assertTrue(store.getByIds(emptyList()).isEmpty())
        assertTrue(store.getMetadataByIds(emptyList()).isEmpty())
    }

    @Test
    fun markSyncState_creates_the_metadata_row_if_absent() = runTest {
        db.noteDao().upsert(note("a"))
        assertNull(store.getMetadata("a"))

        store.markSyncState("a", SyncState.PENDING)

        assertEquals(SyncState.PENDING, store.getMetadata("a")?.syncState)
        assertEquals(listOf("a"), store.getPending(limit = 100).map { it.id })
    }

    @Test
    fun markDeleted_sets_isDeleted_and_resets_state_to_PENDING() = runTest {
        seed("a", SyncState.SYNCED)

        store.markDeleted("a")

        val meta = store.getMetadata("a")
        assertEquals(SyncState.PENDING, meta?.syncState)
        assertTrue(meta?.isDeleted == true)
        assertEquals(listOf("a"), store.getTombstones().map { it.id })
    }

    @Test
    fun upsert_inserts_then_replaces_by_id() = runTest {
        store.upsert(listOf(note("a"), note("b")))
        assertNotNull(store.getById("a"))
        assertNotNull(store.getById("b"))

        store.upsert(listOf(note("a").copy(title = "updated")))
        assertEquals("updated", store.getById("a")?.title)

        store.upsert(emptyList()) // no-op, must not throw
        assertNotNull(store.getById("b"))
    }

    @Test
    fun enqueue_persists_and_marks_pending_in_one_call() = runTest {
        store.enqueue(note("a"))

        assertNotNull(store.getById("a"))
        assertEquals(SyncState.PENDING, store.getMetadata("a")?.syncState)
        assertEquals(listOf("a"), store.getPending(limit = 100).map { it.id })
    }

    // --- tombstones -----------------------------------------------------------

    @Test
    fun getTombstones_returns_only_soft_deleted_rows() = runTest {
        seed("live", SyncState.PENDING, deleted = false)
        seed("dead", SyncState.PENDING, deleted = true)

        assertEquals(listOf("dead"), store.getTombstones().map { it.id })
    }

    @Test
    fun hardDelete_removes_given_ids_and_ignores_empty() = runTest {
        seed("a", SyncState.PENDING)
        seed("b", SyncState.PENDING)

        store.hardDelete(listOf("a"))
        assertNull(store.getById("a"))
        assertNull("metadata row removed too", store.getMetadata("a"))
        assertNotNull(store.getById("b"))

        store.hardDelete(emptyList()) // no-op, must not throw or delete
        assertNotNull(store.getById("b"))
    }

    // --- SEC-10: retention purge ----------------------------------------------

    @Test
    fun purge_removes_only_expired_failed_tombstones() = runTest {
        val day = 24L * 60L * 60L * 1000L
        seed("old-failed", SyncState.FAILED, deleted = true, lastModified = now - 40 * day)
        seed("recent-failed", SyncState.FAILED, deleted = true, lastModified = now - 10 * day)
        seed("old-pending", SyncState.PENDING, deleted = true, lastModified = now - 40 * day)
        seed("old-failed-live", SyncState.FAILED, deleted = false, lastModified = now - 40 * day)

        val purged = store.purgeExpiredTombstones(retentionDays = 30)

        assertEquals("only the old, failed tombstone is purged", 1, purged)
        assertNull(store.getById("old-failed"))
        assertNull("metadata purged too", store.getMetadata("old-failed"))
        assertNotNull("within retention window", store.getById("recent-failed"))
        assertNotNull("not FAILED", store.getById("old-pending"))
        assertNotNull("not a tombstone", store.getById("old-failed-live"))
    }

    @Test
    fun purge_with_zero_retention_removes_all_failed_tombstones() = runTest {
        seed("t", SyncState.FAILED, deleted = true, lastModified = now)

        assertEquals(1, store.purgeExpiredTombstones(retentionDays = 0))
        assertNull(store.getById("t"))
    }

    // --- Observability ---------------------------------------------------------

    /**
     * The engine's state writes are raw SQL against the metadata table, not
     * `@Query` methods, so Room only learns about them because they run inside a
     * transaction. Without that, a host observing the metadata table with a Room
     * `Flow` would never see a sync-state change.
     */
    @Test
    fun markSyncState_notifies_room_invalidation_observers() = runTest {
        seed("n", SyncState.PENDING)

        val notified = CountDownLatch(1)
        db.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(arrayOf("notes_sync_meta")) {
                override fun onInvalidated(tables: Set<String>) = notified.countDown()
            },
        )

        store.markSyncState("n", SyncState.SYNCED)

        assertTrue(
            "Room was not notified of the raw-SQL write — host Flows would go stale",
            notified.await(5, TimeUnit.SECONDS),
        )
    }

    /**
     * Empirical proof (not just documentation) that a host's own plain `@Query`
     * `Flow` re-emits after RoomSyncAdapter's raw-SQL write, thanks to the
     * explicit `refreshVersionsAsync()` call after each write's transaction.
     */
    @Test
    fun markSyncState_is_observable_via_a_plain_room_flow_query() = runTest {
        seed("n", SyncState.PENDING)

        val emissions = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val firstEmission = CountDownLatch(1)
        val secondEmission = CountDownLatch(1)
        val collector = Thread {
            kotlinx.coroutines.runBlocking {
                db.noteDao().observeSyncState("n").collect {
                    emissions += it
                    if (emissions.size == 1) firstEmission.countDown()
                    if (emissions.size >= 2) secondEmission.countDown()
                }
            }
        }.apply { isDaemon = true; start() }

        assertTrue("Flow never even produced its initial value", firstEmission.await(5, TimeUnit.SECONDS))
        store.markSyncState("n", SyncState.SYNCED)

        assertTrue(
            "Flow query never re-emitted after the raw-SQL write",
            secondEmission.await(5, TimeUnit.SECONDS),
        )
        assertEquals(listOf(SyncState.PENDING.name, SyncState.SYNCED.name), emissions)
        collector.interrupt()
    }

    // --- SEC-05: identifier validation ----------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_table_name() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes; DROP TABLE notes",
            metadataTable = "notes_sync_meta",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_metadata_table() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes",
            metadataTable = "notes_sync_meta; DROP TABLE notes",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_id_column() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes",
            metadataTable = "notes_sync_meta",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
            idColumn = "id; DROP TABLE notes",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_modified_column() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes",
            metadataTable = "notes_sync_meta",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
            modifiedColumn = "lastModified; DROP TABLE notes",
        )
    }

    // --- LOCK-003: custom host-table column names + a custom metadata table name --

    /**
     * End-to-end proof the host-table column-name parameters, and a
     * non-default [RoomSyncAdapter.metadataTable] name, reach every raw-SQL
     * site. The metadata table's own columns (`id`/`syncState`/`isDeleted`) are
     * always fixed — only its table name is configurable.
     */
    @Test
    fun custom_column_names_are_honored_by_every_query() = runTest {
        val renamedStore = RoomSyncAdapter<RenamedNote>(
            database = db,
            tableName = "renamed_notes",
            metadataTable = "renamed_notes_sync_meta",
            rawQuery = db.renamedNoteDao()::rawQuery,
            upsert = db.renamedNoteDao()::upsertAll,
            idColumn = "note_id",
            modifiedColumn = "modified_at",
            clock = { now },
        )
        val day = 24L * 60L * 60L * 1000L

        renamedStore.upsert(
            listOf(
                RenamedNote(id = "live", lastModified = now),
                RenamedNote(id = "old-tombstone", lastModified = now - 40 * day),
            ),
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO renamed_notes_sync_meta (id, syncState, isDeleted) VALUES (?, ?, 0)",
            arrayOf<Any?>("live", SyncState.PENDING.name),
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO renamed_notes_sync_meta (id, syncState, isDeleted) VALUES (?, ?, 1)",
            arrayOf<Any?>("old-tombstone", SyncState.FAILED.name),
        )

        assertEquals(listOf("live"), renamedStore.getPending(limit = 100).map { it.id })

        renamedStore.markSyncState("live", SyncState.SYNCED)
        assertEquals(SyncState.SYNCED, renamedStore.getMetadata("live")?.syncState)

        assertEquals(listOf("old-tombstone"), renamedStore.getTombstones().map { it.id })

        assertEquals(1, renamedStore.purgeExpiredTombstones(retentionDays = 30))
        assertNull(renamedStore.getById("old-tombstone"))

        renamedStore.hardDelete(listOf("live"))
        assertNull(renamedStore.getById("live"))
    }

    // --- SQLite bind-variable ceiling ----------------------------------------

    /**
     * An `IN (...)` list wider than SQLite's `SQLITE_MAX_VARIABLE_NUMBER` must not
     * blow up. The id lists these methods take are sized by the *server's* pull
     * response and by the tombstone count, so nothing in the library bounds them:
     * a first sync, or the full re-pull that follows every process restart,
     * routinely exceeds the 999-variable ceiling that Android below API 33 has.
     *
     * The statement's width comes from the id list, not the row count, so a
     * handful of real rows is enough to prove it — no need to seed 40k of them.
     */
    @Test
    fun batch_lookups_survive_an_oversized_id_list() = runTest {
        seed("a", SyncState.PENDING)
        seed("b", SyncState.SYNCED)
        val ids = listOf("a", "b") + (0 until 40_000).map { "absent-$it" }

        assertEquals(setOf("a", "b"), store.getByIds(ids).keys)
        assertEquals(setOf("a", "b"), store.getMetadataByIds(ids).keys)

        store.hardDelete(ids)
        assertTrue("both rows gone", store.getByIds(listOf("a", "b")).isEmpty())
        assertTrue("metadata gone", store.getMetadataByIds(listOf("a", "b")).isEmpty())
    }

    /** The purge path shares [RoomSyncAdapter]'s chunked delete; check it still purges. */
    @Test
    fun purge_deletes_every_expired_tombstone() = runTest {
        val ids = (0 until 50).map { "old-$it" }
        db.noteDao().upsertAll(ids.map { note(it, lastModified = now - 100L * MILLIS_PER_DAY) })
        for (id in ids) {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO notes_sync_meta (id, syncState, isDeleted) VALUES (?, ?, 1)",
                arrayOf<Any?>(id, SyncState.FAILED.name),
            )
        }

        assertEquals(ids.size, store.purgeExpiredTombstones(retentionDays = 30))
        assertTrue("all tombstones purged", store.getTombstones().isEmpty())
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

// --- test-only Room schema ----------------------------------------------------

/**
 * Concrete [SyncableEntity] backing the tests — just `id`/`lastModified`; sync
 * lifecycle lives in [TestSyncMetaRow] (a separate table), not on this class.
 */
@Entity(tableName = "notes")
internal data class TestNote(
    @PrimaryKey override val id: String,
    val title: String,
    override val lastModified: Long,
) : SyncableEntity

/**
 * The `notes_sync_meta` side table [RoomSyncAdapter] reads/writes via raw SQL.
 * Declared as a Room `@Entity` only so the in-memory test database creates its
 * schema automatically — a real host creates this table via a `Migration`, and
 * `RoomSyncAdapter` never generates a `@Dao` for it (ADL-013).
 */
@Entity(tableName = "notes_sync_meta")
internal data class TestSyncMetaRow(
    @PrimaryKey val id: String,
    val syncState: SyncState,
    val isDeleted: Boolean = false,
)

/**
 * Plain `@Dao` — no generic supertype (implementing a generic interface makes
 * Kotlin emit synthetic bridge methods that crash Room's KSP with
 * `unexpected jvm signature V`). `RoomSyncAdapter` is wired to `::rawQuery`.
 */
@Dao
internal interface TestNoteDao {
    @Upsert suspend fun upsert(entity: TestNote)
    @Upsert suspend fun upsertAll(entities: List<TestNote>)
    @RawQuery suspend fun rawQuery(query: SupportSQLiteQuery): List<TestNote>

    /** A plain `@Query` (not `@RawQuery`) so Room statically knows it reads `notes_sync_meta`. */
    @Query("SELECT syncState FROM notes_sync_meta WHERE id = :id")
    fun observeSyncState(id: String): kotlinx.coroutines.flow.Flow<String?>
}

/** Backs the LOCK-003 custom-column-name test — host-table columns deliberately renamed. */
@Entity(tableName = "renamed_notes")
internal data class RenamedNote(
    @PrimaryKey @ColumnInfo(name = "note_id") override val id: String,
    @ColumnInfo(name = "modified_at") override val lastModified: Long,
) : SyncableEntity

/** Metadata side table for [RenamedNote], under a non-default table name — see [TestSyncMetaRow]. */
@Entity(tableName = "renamed_notes_sync_meta")
internal data class RenamedSyncMetaRow(
    @PrimaryKey val id: String,
    val syncState: SyncState,
    val isDeleted: Boolean = false,
)

@Dao
internal interface RenamedNoteDao {
    @Upsert suspend fun upsertAll(entities: List<RenamedNote>)
    @RawQuery suspend fun rawQuery(query: SupportSQLiteQuery): List<RenamedNote>
}

@Database(
    entities = [TestNote::class, TestSyncMetaRow::class, RenamedNote::class, RenamedSyncMetaRow::class],
    version = 1,
    exportSchema = false,
)
internal abstract class TestDatabase : RoomDatabase() {
    abstract fun noteDao(): TestNoteDao
    abstract fun renamedNoteDao(): RenamedNoteDao
}
