package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.adapter.ConflictResolver
import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.core.engine.SyncEngineConfig
import io.github.prathamesh2640.sync.core.model.SyncCounts
import io.github.prathamesh2640.sync.core.model.SyncMetadata
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

    /** A remote/local entity — [SyncableEntity] carries no sync-lifecycle fields. */
    private fun note(id: String, lastModified: Long = 0L, title: String = "t-$id") =
        Note(id = id, title = title, lastModified = lastModified)

    /** A [note] paired with the [SyncMetadata] to seed a [FakeStore] with. */
    private fun seeded(
        id: String,
        state: SyncState = SyncState.PENDING,
        deleted: Boolean = false,
        lastModified: Long = 0L,
        title: String = "t-$id",
    ): Pair<Note, SyncMetadata> = note(id, lastModified, title) to SyncMetadata(syncState = state, isDeleted = deleted)

    /** In-memory [LocalSyncStore] the engine reads from and writes into. */
    private class FakeStore(initial: List<Pair<Note, SyncMetadata>> = emptyList()) : LocalSyncStore<Note> {
        val rows = LinkedHashMap<String, Note>().apply { initial.forEach { (entity, _) -> put(entity.id, entity) } }
        val metadata = LinkedHashMap<String, SyncMetadata>().apply {
            initial.forEach { (entity, meta) -> put(entity.id, meta) }
        }

        val getMetadataByIdsCalls = mutableListOf<List<String>>()

        override suspend fun getPending(limit: Int) =
            rows.values.filter { metadata[it.id]?.syncState == SyncState.PENDING }.take(limit)
        override suspend fun getByIds(ids: List<String>) = ids.mapNotNull { id -> rows[id]?.let { id to it } }.toMap()
        override suspend fun getMetadataByIds(ids: List<String>): Map<String, SyncMetadata> {
            getMetadataByIdsCalls += ids
            return ids.mapNotNull { id -> metadata[id]?.let { id to it } }.toMap()
        }
        override suspend fun counts() = SyncCounts(
            pending = metadata.values.count { it.syncState == SyncState.PENDING },
            failed = metadata.values.count { it.syncState == SyncState.FAILED },
            conflict = metadata.values.count { it.syncState == SyncState.CONFLICT },
        )
        override suspend fun upsert(entities: List<Note>) = entities.forEach { rows[it.id] = it }
        override suspend fun getTombstones() = rows.values.filter { metadata[it.id]?.isDeleted == true }
        override suspend fun markSyncState(id: String, state: SyncState) {
            metadata[id] = metadata[id]?.copy(syncState = state) ?: SyncMetadata(syncState = state)
        }
        override suspend fun markDeleted(id: String) {
            metadata[id] = SyncMetadata(syncState = SyncState.PENDING, isDeleted = true)
        }
        override suspend fun hardDelete(ids: List<String>) = ids.forEach { rows.remove(it); metadata.remove(it) }
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
        assertEquals(SyncState.SYNCED, store.metadata["remote"]?.syncState)
    }

    @Test
    fun `a non-dirty local row is overwritten by the remote copy`() = runTest {
        val store = FakeStore(listOf(seeded("x", state = SyncState.SYNCED, title = "local")))
        val remote = note("x", title = "remote", lastModified = 10L)
        val e = engine(store, FakeAdapter(pullData = listOf(remote)), scheduler = testScheduler)

        e.triggerSync()

        assertEquals("remote", store.rows["x"]?.title)
        assertEquals(SyncState.SYNCED, store.metadata["x"]?.syncState)
    }

    @Test
    fun `a conflict is resolved by the resolver, pushed, and counted`() = runTest {
        val store = FakeStore(listOf(seeded("x", state = SyncState.PENDING, title = "local", lastModified = 5L)))
        val remote = note("x", title = "remote", lastModified = 20L)
        val lastWriteWins = ConflictResolver<Note> { local, r -> if (local.lastModified >= r.lastModified) local else r }
        val e = engine(store, FakeAdapter(pullData = listOf(remote)), resolver = lastWriteWins, scheduler = testScheduler)

        val result = e.triggerSync()

        assertEquals(SyncResult.Success(syncedCount = 1, conflictCount = 1), result)
        assertEquals("remote won (newer)", "remote", store.rows["x"]?.title)
        assertEquals(SyncState.SYNCED, store.metadata["x"]?.syncState)
    }

    @Test
    fun `a conflict with no resolver leaves the entity CONFLICT and fails`() = runTest {
        val store = FakeStore(listOf(seeded("x", state = SyncState.PENDING, title = "local")))
        val remote = note("x", title = "remote", lastModified = 20L)
        val e = engine(store, FakeAdapter(pullData = listOf(remote)), resolver = null, scheduler = testScheduler)

        val result = e.triggerSync()

        assertTrue(result is SyncResult.Failure)
        assertEquals(SyncError.ConflictUnresolvable("x"), (result as SyncResult.Failure).error)
        assertEquals(SyncState.CONFLICT, store.metadata["x"]?.syncState)
        assertEquals(SyncState.CONFLICT, e.syncState.value)
    }

    @Test
    fun `a successfully-pushed tombstone is confirmed and hard-deleted`() = runTest {
        val store = FakeStore(listOf(seeded("d", state = SyncState.PENDING, deleted = true)))
        val adapter = FakeAdapter()
        val e = engine(store, adapter, scheduler = testScheduler)

        e.triggerSync()

        assertEquals(listOf("d"), adapter.deleted)
        assertNull("tombstone hard-deleted after remote confirmation", store.rows["d"])
    }

    @Test
    fun `an unresolvable conflict blocks the watermark from advancing past it`() = runTest {
        val store = FakeStore(listOf(seeded("stuck", state = SyncState.PENDING, title = "local", lastModified = 5L)))
        val stuckRemote = note("stuck", title = "remote", lastModified = 20L)
        val laterRemote = note("other", title = "other", lastModified = 30L)
        val sinceCalls = mutableListOf<Long>()
        var pullCount = 0
        val adapter = object : SyncNetworkAdapter<Note> {
            override suspend fun push(payload: List<Note>) = NetworkResult.Success(Unit)
            override suspend fun pull(since: Long): NetworkResult<List<Note>> {
                sinceCalls += since
                pullCount++
                return NetworkResult.Success(if (pullCount == 1) listOf(stuckRemote, laterRemote) else emptyList())
            }
            override suspend fun delete(ids: List<String>) = NetworkResult.Success(Unit)
        }
        val e = SyncEngineImpl(adapter, SyncEngineConfig {}, UnconfinedTestDispatcher(testScheduler), store = store, resolver = null)

        e.triggerSync() // "stuck" is dirty local + remote change, no resolver -> Unresolvable. "other" is new -> Apply.
        e.triggerSync() // watermark must still be below "stuck"'s lastModified (20), not past "other"'s (30).

        assertEquals(listOf(0L, 19L), sinceCalls)
        assertEquals(SyncState.CONFLICT, store.metadata["stuck"]?.syncState)
        assertEquals(SyncState.SYNCED, store.metadata["other"]?.syncState)
    }

    @Test
    fun `a future-dated entity does not poison the watermark past the injected clock`() = runTest {
        val store = FakeStore()
        val future = note("future", lastModified = 4_102_444_800_000L) // year ~2100
        val sinceCalls = mutableListOf<Long>()
        var pullCount = 0
        val adapter = object : SyncNetworkAdapter<Note> {
            override suspend fun push(payload: List<Note>) = NetworkResult.Success(Unit)
            override suspend fun pull(since: Long): NetworkResult<List<Note>> {
                sinceCalls += since
                pullCount++
                return NetworkResult.Success(if (pullCount == 1) listOf(future) else emptyList())
            }
            override suspend fun delete(ids: List<String>) = NetworkResult.Success(Unit)
        }
        val e = SyncEngineImpl(
            adapter,
            SyncEngineConfig {},
            UnconfinedTestDispatcher(testScheduler),
            store = store,
            resolver = null,
            clock = { 500L },
        )

        e.triggerSync() // "future" is applied normally, but must not pin the watermark at its own timestamp.
        e.triggerSync() // must ask for "since 500" (the clock), not "since 4102444800000" (the poisoned value).

        assertEquals(listOf(0L, 500L), sinceCalls)
        assertEquals(SyncState.SYNCED, store.metadata["future"]?.syncState)
    }

    @Test
    fun `confirmDeletions looks up tombstone metadata in one batch call`() = runTest {
        val store = FakeStore(
            listOf(
                seeded("d1", state = SyncState.PENDING, deleted = true),
                seeded("d2", state = SyncState.PENDING, deleted = true),
                seeded("d3", state = SyncState.PENDING, deleted = true),
            ),
        )
        val e = engine(store, FakeAdapter(), scheduler = testScheduler)

        e.triggerSync()

        assertEquals("exactly one batch call", 1, store.getMetadataByIdsCalls.size)
        assertEquals(setOf("d1", "d2", "d3"), store.getMetadataByIdsCalls.single().toSet())
    }

    @Test
    fun `a pull failure with a good push is a partial failure`() = runTest {
        val store = FakeStore(listOf(seeded("a", state = SyncState.PENDING)))
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
