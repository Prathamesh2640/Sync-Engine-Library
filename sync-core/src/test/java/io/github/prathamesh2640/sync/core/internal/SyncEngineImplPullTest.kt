package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.adapter.ConflictResolver
import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.core.engine.SyncEngineConfig
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.result.SyncError
import io.github.prathamesh2640.sync.core.result.SyncResult
import io.github.prathamesh2640.sync.core.store.LocalSyncStore
import io.github.prathamesh2640.sync.core.testing.Note
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two-way sync path: pulling remote changes, applying downloads,
 * resolving conflicts through a [ConflictResolver], and confirming deletions.
 * Uses an in-memory fake store and adapter, so these stay JVM unit tests.
 */
class SyncEngineImplPullTest {

    private fun note(
        id: String,
        state: SyncState = SyncState.PENDING,
        deleted: Boolean = false,
        lastModified: Long = 0L,
        title: String = "t-$id",
    ) = Note(id = id, title = title, lastModified = lastModified, syncState = state, isDeleted = deleted)

    /** In-memory [LocalSyncStore] the engine reads from and writes into. */
    private class FakeStore(initial: List<Note> = emptyList()) : LocalSyncStore<Note> {
        val rows = LinkedHashMap<String, Note>().apply { initial.forEach { put(it.id, it) } }

        override suspend fun getPending() = rows.values.filter { it.syncState == SyncState.PENDING }
        override suspend fun getByState(state: SyncState) = rows.values.filter { it.syncState == state }
        override suspend fun getById(id: String) = rows[id]
        override suspend fun upsert(entities: List<Note>) = entities.forEach { rows[it.id] = it }
        override suspend fun getTombstones() = rows.values.filter { it.isDeleted }
        override suspend fun markSyncState(id: String, state: SyncState) {
            rows[id]?.let { rows[id] = it.copy(syncState = state) }
        }
        override suspend fun hardDelete(ids: List<String>) = ids.forEach { rows.remove(it) }
        override suspend fun purgeExpiredTombstones(retentionDays: Int) = 0
    }

    /** Adapter whose pull payload and delete recording are configurable. */
    private class FakeAdapter(
        private val pullData: List<Note> = emptyList(),
        private val pushOk: Boolean = true,
    ) : SyncNetworkAdapter<Note> {
        val deleted = mutableListOf<String>()

        override suspend fun push(payload: List<Note>): NetworkResult<Unit> =
            if (pushOk) NetworkResult.Success(Unit) else NetworkResult.HttpError(500, "boom")

        override suspend fun pull(since: Long): NetworkResult<List<Note>> = NetworkResult.Success(pullData)

        override suspend fun delete(ids: List<String>): NetworkResult<Unit> {
            deleted += ids
            return NetworkResult.Success(Unit)
        }
    }

    private fun engine(
        store: FakeStore,
        adapter: SyncNetworkAdapter<Note>,
        resolver: ConflictResolver<Note>? = null,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) = SyncEngineImpl(
        adapter,
        SyncEngineConfig {},
        UnconfinedTestDispatcher(scheduler),
        store = store,
        resolver = resolver,
    )

    @Test
    fun `a brand-new remote entity is downloaded and marked SYNCED`() = runTest {
        val store = FakeStore()
        val e = engine(store, FakeAdapter(pullData = listOf(note("remote"))), scheduler = testScheduler)

        val result = e.triggerSync()

        assertEquals(SyncResult.Success(syncedCount = 1, conflictCount = 0), result)
        assertEquals(SyncState.SYNCED, store.rows["remote"]?.syncState)
    }

    @Test
    fun `a non-dirty local row is overwritten by the remote copy`() = runTest {
        val store = FakeStore(listOf(note("x", state = SyncState.SYNCED, title = "local")))
        val remote = note("x", state = SyncState.SYNCED, title = "remote", lastModified = 10L)
        val e = engine(store, FakeAdapter(pullData = listOf(remote)), scheduler = testScheduler)

        e.triggerSync()

        assertEquals("remote", store.rows["x"]?.title)
        assertEquals(SyncState.SYNCED, store.rows["x"]?.syncState)
    }

    @Test
    fun `a conflict is resolved by the resolver, pushed, and counted`() = runTest {
        val store = FakeStore(listOf(note("x", state = SyncState.PENDING, title = "local", lastModified = 5L)))
        val remote = note("x", state = SyncState.SYNCED, title = "remote", lastModified = 20L)
        val lastWriteWins = ConflictResolver<Note> { local, r -> if (local.lastModified >= r.lastModified) local else r }
        val e = engine(store, FakeAdapter(pullData = listOf(remote)), resolver = lastWriteWins, scheduler = testScheduler)

        val result = e.triggerSync()

        assertEquals(SyncResult.Success(syncedCount = 1, conflictCount = 1), result)
        assertEquals("remote won (newer)", "remote", store.rows["x"]?.title)
        assertEquals(SyncState.SYNCED, store.rows["x"]?.syncState)
    }

    @Test
    fun `a conflict with no resolver leaves the entity CONFLICT and fails`() = runTest {
        val store = FakeStore(listOf(note("x", state = SyncState.PENDING, title = "local")))
        val remote = note("x", state = SyncState.SYNCED, title = "remote", lastModified = 20L)
        val e = engine(store, FakeAdapter(pullData = listOf(remote)), resolver = null, scheduler = testScheduler)

        val result = e.triggerSync()

        assertTrue(result is SyncResult.Failure)
        assertEquals(SyncError.ConflictUnresolvable("x"), (result as SyncResult.Failure).error)
        assertEquals(SyncState.CONFLICT, store.rows["x"]?.syncState)
        assertEquals(SyncState.CONFLICT, e.syncState.value)
    }

    @Test
    fun `a successfully-pushed tombstone is confirmed and hard-deleted`() = runTest {
        val store = FakeStore(listOf(note("d", state = SyncState.PENDING, deleted = true)))
        val adapter = FakeAdapter()
        val e = engine(store, adapter, scheduler = testScheduler)

        e.triggerSync()

        assertEquals(listOf("d"), adapter.deleted)
        assertNull("tombstone hard-deleted after remote confirmation", store.rows["d"])
    }

    @Test
    fun `a pull failure with a good push is a partial failure`() = runTest {
        val store = FakeStore(listOf(note("a", state = SyncState.PENDING)))
        val adapter = object : SyncNetworkAdapter<Note> {
            override suspend fun push(payload: List<Note>) = NetworkResult.Success(Unit)
            override suspend fun pull(since: Long): NetworkResult<List<Note>> = NetworkResult.HttpError(503, "down")
            override suspend fun delete(ids: List<String>) = NetworkResult.Success(Unit)
        }
        val e = SyncEngineImpl(
            adapter,
            SyncEngineConfig {},
            UnconfinedTestDispatcher(testScheduler),
            store = store,
        )

        val result = e.triggerSync()

        assertTrue("expected PartialFailure but was $result", result is SyncResult.PartialFailure)
        result as SyncResult.PartialFailure
        assertEquals(1, result.syncedCount)
        assertEquals(SyncError.HttpError(503), result.errors.single())
    }
}
