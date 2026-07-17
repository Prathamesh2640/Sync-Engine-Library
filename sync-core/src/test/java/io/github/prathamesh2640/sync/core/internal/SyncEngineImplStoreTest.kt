package io.github.prathamesh2640.sync.core.internal

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
 * Covers the storage-backed engine path added in Commit 7: the engine seeds its
 * queue from a [LocalSyncStore], writes each entity's outcome back, and purges
 * expired tombstones once per run. Uses an in-memory fake store — no Android or
 * Room needed, so these stay JVM unit tests.
 */
class SyncEngineImplStoreTest {

    private fun note(id: String, state: SyncState = SyncState.PENDING, deleted: Boolean = false) =
        Note(id = id, title = "t-$id", lastModified = 0L, syncState = state, isDeleted = deleted)

    /** In-memory [LocalSyncStore] that records the writes the engine makes. */
    private class FakeStore(initial: List<Note> = emptyList()) : LocalSyncStore<Note> {
        private val rows = LinkedHashMap<String, Note>().apply { initial.forEach { put(it.id, it) } }

        val stateWrites = mutableListOf<Pair<String, SyncState>>()
        var purgeCalls = 0
            private set
        var lastPurgeRetentionDays: Int? = null
            private set

        override suspend fun getPending(): List<Note> =
            rows.values.filter { it.syncState == SyncState.PENDING }

        override suspend fun getByState(state: SyncState): List<Note> =
            rows.values.filter { it.syncState == state }

        override suspend fun getById(id: String): Note? = rows[id]

        override suspend fun upsert(entities: List<Note>) {
            entities.forEach { rows[it.id] = it }
        }

        override suspend fun getTombstones(): List<Note> = rows.values.filter { it.isDeleted }

        override suspend fun markSyncState(id: String, state: SyncState) {
            stateWrites += id to state
            rows[id]?.let { rows[id] = it.copy(syncState = state) }
        }

        override suspend fun hardDelete(ids: List<String>) {
            ids.forEach { rows.remove(it) }
        }

        override suspend fun purgeExpiredTombstones(retentionDays: Int): Int {
            purgeCalls++
            lastPurgeRetentionDays = retentionDays
            return 0
        }

        /** Convenience for assertions: the state a given id was last written to. */
        fun stateOf(id: String): SyncState? = stateWrites.lastOrNull { it.first == id }?.second
    }

    private class SuccessAdapter : SyncNetworkAdapter<Note> {
        var pushCount = 0
            private set

        override suspend fun push(payload: List<Note>): NetworkResult<Unit> {
            pushCount += payload.size
            return NetworkResult.Success(Unit)
        }

        override suspend fun pull(since: Long): NetworkResult<List<Note>> =
            NetworkResult.Success(emptyList())

        override suspend fun delete(ids: List<String>): NetworkResult<Unit> =
            NetworkResult.Success(Unit)
    }

    private class HttpErrorAdapter(private val code: Int) : SyncNetworkAdapter<Note> {
        override suspend fun push(payload: List<Note>): NetworkResult<Unit> =
            NetworkResult.HttpError(code, "boom")

        override suspend fun pull(since: Long): NetworkResult<List<Note>> =
            NetworkResult.HttpError(code, "boom")

        override suspend fun delete(ids: List<String>): NetworkResult<Unit> =
            NetworkResult.HttpError(code, "boom")
    }

    // --- seeding + write-back --------------------------------------------------

    @Test
    fun `engine seeds queue from store and marks every pushed entity SYNCED`() = runTest {
        val store = FakeStore(listOf(note("a"), note("b"), note("c")))
        val adapter = SuccessAdapter()
        // Queue is left empty — the batch must come entirely from the store.
        val engine = SyncEngineImpl(
            adapter,
            SyncEngineConfig {},
            UnconfinedTestDispatcher(testScheduler),
            store = store,
        )

        val result = engine.triggerSync()

        assertEquals(SyncResult.Success(syncedCount = 3, conflictCount = 0), result)
        assertEquals("all three pending rows should have been pushed", 3, adapter.pushCount)
        assertEquals(SyncState.SYNCED, store.stateOf("a"))
        assertEquals(SyncState.SYNCED, store.stateOf("b"))
        assertEquals(SyncState.SYNCED, store.stateOf("c"))
        assertEquals(SyncState.SYNCED, engine.syncState.value)
    }

    @Test
    fun `a rejected push marks the entity FAILED in the store`() = runTest {
        val store = FakeStore(listOf(note("a")))
        val engine = SyncEngineImpl(
            HttpErrorAdapter(500),
            SyncEngineConfig {},
            UnconfinedTestDispatcher(testScheduler),
            store = store,
        )

        val result = engine.triggerSync()

        assertTrue(result is SyncResult.Failure)
        assertEquals(SyncError.HttpError(500), (result as SyncResult.Failure).error)
        assertEquals(SyncState.FAILED, store.stateOf("a"))
        assertEquals(SyncState.FAILED, engine.syncState.value)
    }

    @Test
    fun `partial failure marks synced rows SYNCED and failed rows FAILED`() = runTest {
        // Succeeds for every id except "boom".
        val adapter = object : SyncNetworkAdapter<Note> {
            override suspend fun push(payload: List<Note>): NetworkResult<Unit> =
                if (payload.single().id == "boom") NetworkResult.HttpError(500, "boom")
                else NetworkResult.Success(Unit)

            override suspend fun pull(since: Long): NetworkResult<List<Note>> =
                NetworkResult.Success(emptyList())

            override suspend fun delete(ids: List<String>): NetworkResult<Unit> =
                NetworkResult.Success(Unit)
        }
        val store = FakeStore(listOf(note("ok"), note("boom")))
        val engine = SyncEngineImpl(
            adapter,
            SyncEngineConfig {},
            UnconfinedTestDispatcher(testScheduler),
            store = store,
        )

        val result = engine.triggerSync()

        assertTrue("expected PartialFailure but was $result", result is SyncResult.PartialFailure)
        assertEquals(SyncState.SYNCED, store.stateOf("ok"))
        assertEquals(SyncState.FAILED, store.stateOf("boom"))
    }

    // --- tombstone purge (SEC-10) ---------------------------------------------

    @Test
    fun `every run purges expired tombstones with the configured retention`() = runTest {
        val store = FakeStore(listOf(note("a")))
        val engine = SyncEngineImpl(
            SuccessAdapter(),
            SyncEngineConfig { tombstoneRetentionDays = 7 },
            UnconfinedTestDispatcher(testScheduler),
            store = store,
        )

        engine.triggerSync()

        assertEquals(1, store.purgeCalls)
        assertEquals(7, store.lastPurgeRetentionDays)
    }

    @Test
    fun `an empty store yields an empty success and still purges tombstones`() = runTest {
        val store = FakeStore(emptyList())
        val engine = SyncEngineImpl(
            SuccessAdapter(),
            SyncEngineConfig {},
            UnconfinedTestDispatcher(testScheduler),
            store = store,
        )

        val result = engine.triggerSync()

        assertEquals(SyncResult.Success(syncedCount = 0, conflictCount = 0), result)
        assertEquals(1, store.purgeCalls)
        assertNull("nothing was pushed, so no state write", store.stateOf("a"))
        assertEquals(SyncState.SYNCED, engine.syncState.value)
    }
}
