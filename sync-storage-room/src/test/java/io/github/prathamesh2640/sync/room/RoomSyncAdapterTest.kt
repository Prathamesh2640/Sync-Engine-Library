package io.github.prathamesh2640.sync.room

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.InvalidationTracker
import androidx.room.PrimaryKey
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
 * `@RawQuery` method wires cleanly into [RoomSyncAdapter].
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
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(
        id: String,
        state: SyncState,
        deleted: Boolean = false,
        lastModified: Long = now,
    ) = TestNote(id = id, title = "t-$id", lastModified = lastModified, syncState = state, isDeleted = deleted)

    // --- core lifecycle: PENDING -> SYNCED ------------------------------------

    @Test
    fun pending_to_synced_flow() = runTest {
        db.noteDao().upsert(note("a", SyncState.PENDING))

        assertEquals(listOf("a"), store.getPending().map { it.id })

        store.markSyncState("a", SyncState.SYNCED)

        assertTrue("no longer pending", store.getPending().isEmpty())
        assertEquals(SyncState.SYNCED, store.getById("a")?.syncState)
    }

    @Test
    fun getById_returns_null_on_miss() = runTest {
        assertNull(store.getById("does-not-exist"))
    }

    @Test
    fun upsert_inserts_then_replaces_by_id() = runTest {
        store.upsert(listOf(note("a", SyncState.SYNCED), note("b", SyncState.SYNCED)))
        assertEquals(SyncState.SYNCED, store.getById("a")?.syncState)
        assertEquals(SyncState.SYNCED, store.getById("b")?.syncState)

        store.upsert(listOf(note("a", SyncState.SYNCED).copy(title = "updated")))
        assertEquals("updated", store.getById("a")?.title)

        store.upsert(emptyList()) // no-op, must not throw
        assertNotNull(store.getById("b"))
    }

    // --- tombstones -----------------------------------------------------------

    @Test
    fun getTombstones_returns_only_soft_deleted_rows() = runTest {
        val dao = db.noteDao()
        dao.upsert(note("live", SyncState.PENDING, deleted = false))
        dao.upsert(note("dead", SyncState.PENDING, deleted = true))

        assertEquals(listOf("dead"), store.getTombstones().map { it.id })
    }

    @Test
    fun hardDelete_removes_given_ids_and_ignores_empty() = runTest {
        val dao = db.noteDao()
        dao.upsert(note("a", SyncState.PENDING))
        dao.upsert(note("b", SyncState.PENDING))

        store.hardDelete(listOf("a"))
        assertNull(store.getById("a"))
        assertNotNull(store.getById("b"))

        store.hardDelete(emptyList()) // no-op, must not throw or delete
        assertNotNull(store.getById("b"))
    }

    // --- SEC-10: retention purge ----------------------------------------------

    @Test
    fun purge_removes_only_expired_failed_tombstones() = runTest {
        val dao = db.noteDao()
        val day = 24L * 60L * 60L * 1000L
        dao.upsert(note("old-failed", SyncState.FAILED, deleted = true, lastModified = now - 40 * day))
        dao.upsert(note("recent-failed", SyncState.FAILED, deleted = true, lastModified = now - 10 * day))
        dao.upsert(note("old-pending", SyncState.PENDING, deleted = true, lastModified = now - 40 * day))
        dao.upsert(note("old-failed-live", SyncState.FAILED, deleted = false, lastModified = now - 40 * day))

        val purged = store.purgeExpiredTombstones(retentionDays = 30)

        assertEquals("only the old, failed tombstone is purged", 1, purged)
        assertNull(store.getById("old-failed"))
        assertNotNull("within retention window", store.getById("recent-failed"))
        assertNotNull("not FAILED", store.getById("old-pending"))
        assertNotNull("not a tombstone", store.getById("old-failed-live"))
    }

    @Test
    fun purge_with_zero_retention_removes_all_failed_tombstones() = runTest {
        db.noteDao().upsert(note("t", SyncState.FAILED, deleted = true, lastModified = now))

        assertEquals(1, store.purgeExpiredTombstones(retentionDays = 0))
        assertNull(store.getById("t"))
    }

    // --- Observability ---------------------------------------------------------

    /**
     * The engine's state writes are raw SQL, not `@Query` methods, so Room only
     * learns about them because they run inside a transaction. Without that, a host
     * observing the table with a Room `Flow` would never see a sync-state change.
     */
    @Test
    fun markSyncState_notifies_room_invalidation_observers() = runTest {
        store.upsert(listOf(note("n", SyncState.PENDING)))

        val notified = CountDownLatch(1)
        db.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(arrayOf("notes")) {
                override fun onInvalidated(tables: Set<String>) = notified.countDown()
            },
        )

        store.markSyncState("n", SyncState.SYNCED)

        assertTrue(
            "Room was not notified of the raw-SQL write — host Flows would go stale",
            notified.await(5, TimeUnit.SECONDS),
        )
    }

    // --- SEC-05: identifier validation ----------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_table_name() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes; DROP TABLE notes",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_id_column() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
            idColumn = "id; DROP TABLE notes",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_state_column() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
            stateColumn = "syncState; DROP TABLE notes",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_deleted_column() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
            deletedColumn = "isDeleted; DROP TABLE notes",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_non_identifier_modified_column() {
        RoomSyncAdapter<TestNote>(
            db,
            tableName = "notes",
            rawQuery = db.noteDao()::rawQuery,
            upsert = db.noteDao()::upsertAll,
            modifiedColumn = "lastModified; DROP TABLE notes",
        )
    }

    // --- LOCK-003: custom column names -----------------------------------------

    /**
     * End-to-end proof the column-name parameters actually reach every raw-SQL
     * site (getPending/getById/markSyncState/getTombstones/hardDelete/
     * purgeExpiredTombstones) against a table whose columns don't use the
     * SyncableEntity defaults.
     */
    @Test
    fun custom_column_names_are_honored_by_every_query() = runTest {
        val renamedStore = RoomSyncAdapter<RenamedNote>(
            database = db,
            tableName = "renamed_notes",
            rawQuery = db.renamedNoteDao()::rawQuery,
            upsert = db.renamedNoteDao()::upsertAll,
            idColumn = "note_id",
            stateColumn = "state",
            deletedColumn = "deleted",
            modifiedColumn = "modified_at",
            clock = { now },
        )
        val day = 24L * 60L * 60L * 1000L

        renamedStore.upsert(
            listOf(
                RenamedNote(id = "live", lastModified = now, syncState = SyncState.PENDING, isDeleted = false),
                RenamedNote(
                    id = "old-tombstone",
                    lastModified = now - 40 * day,
                    syncState = SyncState.FAILED,
                    isDeleted = true,
                ),
            ),
        )

        assertEquals(listOf("live"), renamedStore.getPending().map { it.id })

        renamedStore.markSyncState("live", SyncState.SYNCED)
        assertEquals(SyncState.SYNCED, renamedStore.getById("live")?.syncState)

        assertEquals(listOf("old-tombstone"), renamedStore.getTombstones().map { it.id })

        assertEquals(1, renamedStore.purgeExpiredTombstones(retentionDays = 30))
        assertNull(renamedStore.getById("old-tombstone"))

        renamedStore.hardDelete(listOf("live"))
        assertNull(renamedStore.getById("live"))
    }
}

// --- test-only Room schema ----------------------------------------------------

/**
 * Concrete [SyncableEntity] backing the tests. Column names are the
 * [SyncableEntity] defaults (id/syncState/isDeleted/lastModified).
 */
@Entity(tableName = "notes")
internal data class TestNote(
    @PrimaryKey override val id: String,
    val title: String,
    override val lastModified: Long,
    override val syncState: SyncState,
    override val isDeleted: Boolean = false,
) : SyncableEntity

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
}

/** Backs the LOCK-003 custom-column-name test — every column deliberately renamed. */
@Entity(tableName = "renamed_notes")
internal data class RenamedNote(
    @PrimaryKey @ColumnInfo(name = "note_id") override val id: String,
    @ColumnInfo(name = "modified_at") override val lastModified: Long,
    @ColumnInfo(name = "state") override val syncState: SyncState,
    @ColumnInfo(name = "deleted") override val isDeleted: Boolean = false,
) : SyncableEntity

@Dao
internal interface RenamedNoteDao {
    @Upsert suspend fun upsertAll(entities: List<RenamedNote>)
    @RawQuery suspend fun rawQuery(query: SupportSQLiteQuery): List<RenamedNote>
}

@Database(entities = [TestNote::class, RenamedNote::class], version = 1, exportSchema = false)
internal abstract class TestDatabase : RoomDatabase() {
    abstract fun noteDao(): TestNoteDao
    abstract fun renamedNoteDao(): RenamedNoteDao
}
