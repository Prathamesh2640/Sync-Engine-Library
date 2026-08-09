package io.github.prathamesh2640.sync.core.engine

import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncStats
import io.github.prathamesh2640.sync.core.result.SyncError
import io.github.prathamesh2640.sync.core.result.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/**
 * Contract tests for the [SyncEngine] interface.
 *
 * The real implementation lands in a later commit; here a fake proves the shape
 * the interface promises: an observable [StateFlow] of [SyncState], a suspending
 * [SyncEngine.triggerSync] returning a [SyncResult], [Closeable] semantics, and
 * that a closed engine reports failure rather than throwing.
 */
class SyncEngineContractTest {

    /** Minimal in-memory fake following the interface contract. */
    private class FakeEngine : SyncEngine {
        private val _syncState = MutableStateFlow(SyncState.PENDING)
        override val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
        override val stats: StateFlow<SyncStats> = MutableStateFlow(SyncStats.INITIAL).asStateFlow()
        private var closed = false

        override suspend fun triggerSync(): SyncResult {
            if (closed) return SyncResult.Failure(SyncError.StorageError(IllegalStateException("closed")))
            _syncState.value = SyncState.SYNCING
            _syncState.value = SyncState.SYNCED
            return SyncResult.Success(syncedCount = 1, conflictCount = 0)
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `SyncEngine is Closeable`() {
        // Compile-time proof the interface extends Closeable (enables use{} / try-with-resources).
        val closeable: Closeable = FakeEngine()
        closeable.close()
    }

    @Test
    fun `syncState starts at PENDING and advances on sync`() = runTest {
        val engine = FakeEngine()
        assertEquals(SyncState.PENDING, engine.syncState.value)

        engine.triggerSync()

        assertEquals(SyncState.SYNCED, engine.syncState.value)
    }

    @Test
    fun `triggerSync returns a Success result`() = runTest {
        val result = FakeEngine().triggerSync()
        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).syncedCount)
    }

    @Test
    fun `triggerSync on a closed engine returns Failure, never throws`() = runTest {
        val engine = FakeEngine()
        engine.close()

        val result = engine.triggerSync()

        assertTrue(result is SyncResult.Failure)
        assertTrue((result as SyncResult.Failure).error is SyncError.StorageError)
    }

    @Test
    fun `close is idempotent`() {
        val engine = FakeEngine()
        engine.close()
        engine.close() // second call must not throw
    }

    @Test
    fun `usable with Kotlin use block`() {
        var closedInside = false
        FakeEngine().use { engine ->
            closedInside = engine.syncState.value == SyncState.PENDING
        }
        assertTrue(closedInside)
    }
}
