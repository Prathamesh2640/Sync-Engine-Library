package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.core.engine.SyncEngineConfig
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.result.SyncError
import io.github.prathamesh2640.sync.core.result.SyncResult
import io.github.prathamesh2640.sync.core.testing.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineImplTest {

    private val config = SyncEngineConfig {}

    private fun note(id: String) = Note(id = id, title = "t-$id", lastModified = 0L)

    // --- Test doubles ---------------------------------------------------------

    /** Always succeeds. Records how many pushes it saw. */
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

    /** Always fails with a fixed HTTP error. */
    private class HttpErrorAdapter(private val code: Int) : SyncNetworkAdapter<Note> {
        override suspend fun push(payload: List<Note>): NetworkResult<Unit> =
            NetworkResult.HttpError(code, "boom")

        override suspend fun pull(since: Long): NetworkResult<List<Note>> =
            NetworkResult.HttpError(code, "boom")

        override suspend fun delete(ids: List<String>): NetworkResult<Unit> =
            NetworkResult.HttpError(code, "boom")
    }

    // --- close() --------------------------------------------------------------

    @Test
    fun `triggerSync on a closed engine returns StorageError failure`() = runTest {
        val engine = SyncEngineImpl(SuccessAdapter(), config, UnconfinedTestDispatcher(testScheduler))
        engine.enqueue(note("a"))

        engine.close()
        val result = engine.triggerSync()

        assertTrue(result is SyncResult.Failure)
        assertTrue((result as SyncResult.Failure).error is SyncError.StorageError)
    }

    @Test
    fun `close is idempotent`() = runTest {
        val engine = SyncEngineImpl(SuccessAdapter(), config, UnconfinedTestDispatcher(testScheduler))
        engine.close()
        engine.close() // must not throw
    }

    // --- happy path -----------------------------------------------------------

    @Test
    fun `all items sync successfully and state ends at SYNCED`() = runTest {
        val adapter = SuccessAdapter()
        val engine = SyncEngineImpl(adapter, config, UnconfinedTestDispatcher(testScheduler))
        engine.enqueue(note("a"))
        engine.enqueue(note("b"))
        engine.enqueue(note("c"))

        val result = engine.triggerSync()

        assertEquals(SyncResult.Success(syncedCount = 3, conflictCount = 0), result)
        assertEquals(3, adapter.pushCount)
        assertEquals(SyncState.SYNCED, engine.syncState.value)
    }

    @Test
    fun `empty queue yields an empty success`() = runTest {
        val engine = SyncEngineImpl(SuccessAdapter(), config, UnconfinedTestDispatcher(testScheduler))

        val result = engine.triggerSync()

        assertEquals(SyncResult.Success(syncedCount = 0, conflictCount = 0), result)
        assertEquals(SyncState.SYNCED, engine.syncState.value)
    }

    // --- failure mapping ------------------------------------------------------

    @Test
    fun `total failure maps HttpError and ends at FAILED`() = runTest {
        val engine = SyncEngineImpl(HttpErrorAdapter(500), config, UnconfinedTestDispatcher(testScheduler))
        engine.enqueue(note("a"))

        val result = engine.triggerSync()

        assertTrue(result is SyncResult.Failure)
        assertEquals(SyncError.HttpError(500), (result as SyncResult.Failure).error)
        assertEquals(SyncState.FAILED, engine.syncState.value)
    }

    // --- supervisorScope isolation (F-08 core guarantee) ----------------------

    @Test
    fun `one throwing item does not abort the rest of the batch`() = runTest {
        // Adapter throws only for the entity whose id is "boom"; all others succeed.
        val adapter = object : SyncNetworkAdapter<Note> {
            override suspend fun push(payload: List<Note>): NetworkResult<Unit> {
                if (payload.single().id == "boom") throw RuntimeException("kaboom")
                return NetworkResult.Success(Unit)
            }

            override suspend fun pull(since: Long): NetworkResult<List<Note>> =
                NetworkResult.Success(emptyList())

            override suspend fun delete(ids: List<String>): NetworkResult<Unit> =
                NetworkResult.Success(Unit)
        }
        val engine = SyncEngineImpl(adapter, config, UnconfinedTestDispatcher(testScheduler))
        listOf("a", "b", "boom", "c", "d").forEach { engine.enqueue(note(it)) }

        val result = engine.triggerSync()

        // Four siblings still synced; the thrower is isolated and reported.
        assertTrue("expected PartialFailure but was $result", result is SyncResult.PartialFailure)
        result as SyncResult.PartialFailure
        assertEquals(4, result.syncedCount)
        assertEquals(1, result.failedCount)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single() is SyncError.StorageError)
        assertEquals(SyncState.FAILED, engine.syncState.value)
    }

    // --- maxRetries dead-letter (SEC hardening) --------------------------------

    /** Always fails with a fixed HTTP error; counts how many pushes it saw. */
    private class CountingHttpErrorAdapter(private val code: Int) : SyncNetworkAdapter<Note> {
        var pushCount = 0
            private set

        override suspend fun push(payload: List<Note>): NetworkResult<Unit> {
            pushCount++
            return NetworkResult.HttpError(code, "boom")
        }

        override suspend fun pull(since: Long): NetworkResult<List<Note>> =
            NetworkResult.Success(emptyList())

        override suspend fun delete(ids: List<String>): NetworkResult<Unit> =
            NetworkResult.Success(Unit)
    }

    @Test
    fun `a permanently failing entity is retried up to maxRetries then given up on`() = runTest {
        val adapter = CountingHttpErrorAdapter(500)
        val config = SyncEngineConfig { maxRetries = 2 }
        val engine = SyncEngineImpl(adapter, config, UnconfinedTestDispatcher(testScheduler))
        engine.enqueue(note("a"))

        // Attempt 1 fails (retried), attempt 2 fails (retried), attempt 3 fails
        // (maxRetries exhausted — gives up instead of re-queueing).
        repeat(3) { engine.triggerSync() }
        assertEquals(3, adapter.pushCount)

        // A further run must not push "a" again — it was dropped from the queue,
        // not retried forever.
        val result = engine.triggerSync()
        assertEquals(SyncResult.Success(syncedCount = 0, conflictCount = 0), result)
        assertEquals(3, adapter.pushCount)
    }

    @Test
    fun `a later success resets the retry count so the next failure streak gets a fresh budget`() = runTest {
        // Outcome per push, in order: fail, succeed, fail, fail.
        val outcomes = listOf(false, true, false, false)
        val adapter = object : SyncNetworkAdapter<Note> {
            var pushCount = 0
                private set

            override suspend fun push(payload: List<Note>): NetworkResult<Unit> {
                val succeed = outcomes[pushCount]
                pushCount++
                return if (succeed) NetworkResult.Success(Unit) else NetworkResult.HttpError(500, "boom")
            }

            override suspend fun pull(since: Long) = NetworkResult.Success(emptyList<Note>())
            override suspend fun delete(ids: List<String>) = NetworkResult.Success(Unit)
        }
        val config = SyncEngineConfig { maxRetries = 1 }
        val engine = SyncEngineImpl(adapter, config, UnconfinedTestDispatcher(testScheduler))
        engine.enqueue(note("a"))

        engine.triggerSync() // push #1 fails — attempt 1 of 1 retry, re-queued
        engine.triggerSync() // push #2 succeeds — retry count resets; queue now empty
        engine.enqueue(note("a")) // a fresh edit starts a new failure streak
        engine.triggerSync() // push #3 fails — if the count truly reset, this is attempt 1 of 1: re-queued
        engine.triggerSync() // push #4 fails — budget exhausted now, gives up

        // If the success in run #2 had NOT cleared the stale count, push #3 alone
        // would already have exceeded maxRetries and push #4 would never happen
        // (empty queue) — so pushCount would stop at 3, not 4.
        assertEquals(4, adapter.pushCount)
    }

    // --- SEC-11: concurrent triggerSync is a no-op ----------------------------

    @Test
    fun `a second triggerSync while one is in flight is a no-op`() = runTest {
        // Adapter parks inside push until released, so the first run holds the lock.
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = object : SyncNetworkAdapter<Note> {
            override suspend fun push(payload: List<Note>): NetworkResult<Unit> {
                started.complete(Unit)
                release.await()
                return NetworkResult.Success(Unit)
            }

            override suspend fun pull(since: Long): NetworkResult<List<Note>> =
                NetworkResult.Success(emptyList())

            override suspend fun delete(ids: List<String>): NetworkResult<Unit> =
                NetworkResult.Success(Unit)
        }
        val engine = SyncEngineImpl(adapter, config, UnconfinedTestDispatcher(testScheduler))
        engine.enqueue(note("a"))

        val first = async { engine.triggerSync() }
        started.await() // first run is now inside push, holding the sync lock
        assertEquals(SyncState.SYNCING, engine.syncState.value)

        val second = engine.triggerSync() // must not start a second run
        assertEquals(SyncResult.Success(syncedCount = 0, conflictCount = 0), second)

        release.complete(Unit)
        assertEquals(SyncResult.Success(syncedCount = 1, conflictCount = 0), first.await())
    }
}
