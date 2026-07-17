package io.github.prathamesh2640.sync.core.internal

import app.cash.turbine.test
import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.core.engine.SyncEngineConfig
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.testing.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Turbine-based assertions on the engine's observable [SyncState] emissions. */
class SyncEngineImplFlowTest {

    private val config = SyncEngineConfig {}
    private fun note(id: String) = Note(id = id, title = "t-$id", lastModified = 0L)

    @Test
    fun `state flows PENDING then SYNCING then SYNCED on a successful run`() = runTest {
        val release = CompletableDeferred<Unit>()
        val adapter = object : SyncNetworkAdapter<Note> {
            override suspend fun push(payload: List<Note>): NetworkResult<Unit> {
                release.await() // park so SYNCING is observable
                return NetworkResult.Success(Unit)
            }
            override suspend fun pull(since: Long) = NetworkResult.Success(emptyList<Note>())
            override suspend fun delete(ids: List<String>) = NetworkResult.Success(Unit)
        }
        val engine = SyncEngineImpl(adapter, config, UnconfinedTestDispatcher(testScheduler))
        engine.enqueue(note("a"))

        engine.syncState.test {
            assertEquals(SyncState.PENDING, awaitItem())
            val run = async { engine.triggerSync() }
            assertEquals(SyncState.SYNCING, awaitItem())
            release.complete(Unit)
            assertEquals(SyncState.SYNCED, awaitItem())
            run.await()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state ends FAILED when the run fails`() = runTest {
        val adapter = object : SyncNetworkAdapter<Note> {
            override suspend fun push(payload: List<Note>) = NetworkResult.HttpError(500, "boom")
            override suspend fun pull(since: Long) = NetworkResult.Success(emptyList<Note>())
            override suspend fun delete(ids: List<String>) = NetworkResult.Success(Unit)
        }
        val engine = SyncEngineImpl(adapter, config, UnconfinedTestDispatcher(testScheduler))
        engine.enqueue(note("a"))

        engine.syncState.test {
            assertEquals(SyncState.PENDING, awaitItem())
            engine.triggerSync()
            assertEquals(SyncState.FAILED, expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
