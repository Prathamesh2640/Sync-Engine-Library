package io.github.prathamesh2640.sync.sample

import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.sample.data.Note
import io.github.prathamesh2640.sync.sample.net.FakeNoteApiAdapter
import io.github.prathamesh2640.sync.sample.net.InMemorySyncApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the in-memory server + its adapter. */
class FakeNoteApiAdapterTest {

    private fun note(id: String, ts: Long) = Note(id = id, title = "t-$id", lastModified = ts)

    @Test
    fun push_then_pull_round_trips_changed_notes() = runTest {
        val api = InMemorySyncApi()
        val adapter = FakeNoteApiAdapter(api)

        assertTrue(adapter.push(listOf(note("a", 10), note("b", 20))) is NetworkResult.Success)

        val pulled = adapter.pull(since = 15)
        assertTrue(pulled is NetworkResult.Success)
        assertEquals(listOf("b"), (pulled as NetworkResult.Success).data.map { it.id })
    }

    @Test
    fun offline_surfaces_a_network_error() = runTest {
        val api = InMemorySyncApi().apply { online = false }
        val adapter = FakeNoteApiAdapter(api)

        assertTrue(adapter.push(listOf(note("a", 1))) is NetworkResult.NetworkError)
        assertTrue(adapter.pull(0) is NetworkResult.NetworkError)
    }

    @Test
    fun delete_removes_the_note_from_the_server() = runTest {
        val api = InMemorySyncApi()
        val adapter = FakeNoteApiAdapter(api)
        adapter.push(listOf(note("a", 10)))

        adapter.delete(listOf("a"))

        assertTrue(api.snapshot().isEmpty())
    }
}
