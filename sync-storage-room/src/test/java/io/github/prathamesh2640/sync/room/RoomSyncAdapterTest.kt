package io.github.prathamesh2640.sync.room

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RawQuery
import androidx.room.Room
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

/**
 * End-to-end integration tests for [RoomSyncAdapter] against a real in-memory
 * Room database. Also the compile-time proof that a plain `@Dao` with a
 * `@RawQuery` method wires cleanly into [RoomSyncAdapter], and that [SyncDatabase]
 * works as a Room `@Database` base class.
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
        assertEquals(listOf("a"), store.getByState(SyncState.SYNCED).map { it.id })
        assertEquals(SyncState.SYNCED, store.getById("a")?.syncState)
    }

    @Test
    fun getById_returns_null_on_miss() = runTest {
        assertNull(store.getById("does-not-exist"))
    }

    @Test
    fun upsert_inserts_then_replaces_by_id() = runTest {
        store.upsert(listOf(note("a", SyncState.SYNCED), note("b", SyncState.SYNCED)))
        assertEquals(setOf("a", "b"), store.getByState(SyncState.SYNCED).map { it.id }.toSet())

        store.upsert(listOf(note("a", SyncState.SYNCED).copy(title = "updated")))
        assertEquals("updated", store.getById("a")?.title)

        store.upsert(emptyList()) // no-op, must not throw
        assertNotNull(store.getById("b"))
    }

    @Test
    fun getByState_filters_correctly() = runTest {
        val dao = db.noteDao()
        dao.upsert(note("p", SyncState.PENDING))
        dao.upsert(note("s", SyncState.SYNCED))
        dao.upsert(note("f", SyncState.FAILED))

        assertEquals(listOf("p"), store.getByState(SyncState.PENDING).map { it.id })
        assertEquals(listOf("s"), store.getByState(SyncState.SYNCED).map { it.id })
        assertEquals(listOf("f"), store.getByState(SyncState.FAILED).map { it.id })
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

/** Exercises [SyncDatabase] as a Room `@Database` base class. */
@Database(entities = [TestNote::class], version = 1, exportSchema = false)
internal abstract class TestDatabase : SyncDatabase() {
    abstract fun noteDao(): TestNoteDao
}
