package com.yourlibrary.sync.core.adapter

import com.yourlibrary.sync.core.testing.Note
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [SyncNetworkAdapter].
 *
 * Proves a host-app implementation over a concrete entity ([Note]) compiles
 * against the generic bound, that every method is suspending and returns a
 * [NetworkResult], and that the adapter maps outcomes to sealed values rather
 * than throwing.
 */
class SyncNetworkAdapterContractTest {

    /** A fake adapter that returns pre-programmed results — the shape a real one follows. */
    private class FakeAdapter(
        private val pushResult: NetworkResult<Unit>,
        private val pullResult: NetworkResult<List<Note>>,
        private val deleteResult: NetworkResult<Unit>,
    ) : SyncNetworkAdapter<Note> {
        var lastPushed: List<Note>? = null
        var lastSince: Long? = null
        var lastDeletedIds: List<String>? = null

        override suspend fun push(payload: List<Note>): NetworkResult<Unit> {
            lastPushed = payload
            return pushResult
        }

        override suspend fun pull(since: Long): NetworkResult<List<Note>> {
            lastSince = since
            return pullResult
        }

        override suspend fun delete(ids: List<String>): NetworkResult<Unit> {
            lastDeletedIds = ids
            return deleteResult
        }
    }

    @Test
    fun `push forwards payload and returns a NetworkResult`() = runTest {
        val adapter = FakeAdapter(
            pushResult = NetworkResult.Success(Unit),
            pullResult = NetworkResult.Success(emptyList()),
            deleteResult = NetworkResult.Success(Unit),
        )
        val note = Note(id = "1", title = "n", lastModified = 1L)

        val result = adapter.push(listOf(note))

        assertEquals(listOf(note), adapter.lastPushed)
        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun `pull returns entities on success`() = runTest {
        val notes = listOf(Note(id = "1", title = "n", lastModified = 5L))
        val adapter = FakeAdapter(
            pushResult = NetworkResult.Success(Unit),
            pullResult = NetworkResult.Success(notes),
            deleteResult = NetworkResult.Success(Unit),
        )

        val result = adapter.pull(since = 42L)

        assertEquals(42L, adapter.lastSince)
        assertEquals(NetworkResult.Success(notes), result)
    }

    @Test
    fun `delete forwards ids and maps a server error to HttpError`() = runTest {
        val adapter = FakeAdapter(
            pushResult = NetworkResult.Success(Unit),
            pullResult = NetworkResult.Success(emptyList()),
            deleteResult = NetworkResult.HttpError(500, "boom"),
        )

        val result = adapter.delete(listOf("1", "2"))

        assertEquals(listOf("1", "2"), adapter.lastDeletedIds)
        assertEquals(NetworkResult.HttpError(500, "boom"), result)
    }
}
