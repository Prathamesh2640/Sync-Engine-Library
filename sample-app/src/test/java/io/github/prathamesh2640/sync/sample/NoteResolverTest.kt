package io.github.prathamesh2640.sync.sample

import io.github.prathamesh2640.sync.sample.data.Note
import io.github.prathamesh2640.sync.sample.sync.NoteResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the three demonstrated conflict-resolution strategies. */
class NoteResolverTest {

    private fun note(id: String = "n", ts: Long, body: String = "") =
        Note(id = id, title = "t", body = body, lastModified = ts)

    @Test
    fun lastWriteWins_keeps_the_newer_timestamp() {
        val local = note(ts = 5, body = "local")
        val remote = note(ts = 10, body = "remote")

        // remote is newer → remote wins
        assertEquals(remote, NoteResolver.LAST_WRITE_WINS.resolver.resolve(local, remote))

        // local is newer → local wins (compare against the same newer instance)
        val newerLocal = local.copy(lastModified = 20)
        assertEquals(newerLocal, NoteResolver.LAST_WRITE_WINS.resolver.resolve(newerLocal, remote))
    }

    @Test
    fun serverWins_always_returns_remote() {
        val local = note(ts = 100, body = "local")
        val remote = note(ts = 1, body = "remote")

        assertEquals(remote, NoteResolver.SERVER_WINS.resolver.resolve(local, remote))
    }

    @Test
    fun merge_combines_bodies_and_takes_the_newer_timestamp() {
        val local = note(ts = 5, body = "local body")
        val remote = note(ts = 10, body = "remote body")

        val merged = NoteResolver.MERGE.resolver.resolve(local, remote)

        assertEquals(10, merged.lastModified)
        assertTrue("keeps local text", merged.body.contains("local body"))
        assertTrue("keeps remote text", merged.body.contains("remote body"))
    }
}
