package com.yourlibrary.sync.core.adapter

import com.yourlibrary.sync.core.testing.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Contract tests for [ConflictResolver].
 *
 * Proves the generic bound (`T : SyncableEntity`) accepts a concrete host-app
 * entity ([Note]), that the SAM/lambda form works, and that a custom-merge
 * implementation can synthesise a brand-new winning instance.
 */
class ConflictResolverContractTest {

    private fun note(id: String, title: String, lastModified: Long) =
        Note(id = id, title = title, lastModified = lastModified)

    @Test
    fun `last-write-wins resolver keeps the newer version`() {
        val resolver = ConflictResolver<Note> { local, remote ->
            if (local.lastModified >= remote.lastModified) local else remote
        }

        val local = note("1", "local", lastModified = 200L)
        val remote = note("1", "remote", lastModified = 100L)

        assertSame(local, resolver.resolve(local, remote))
    }

    @Test
    fun `server-wins resolver always keeps remote`() {
        val resolver = ConflictResolver<Note> { _, remote -> remote }

        val local = note("1", "local", lastModified = 999L)
        val remote = note("1", "remote", lastModified = 1L)

        assertSame(remote, resolver.resolve(local, remote))
    }

    @Test
    fun `custom-merge resolver can synthesise a new instance`() {
        // Merge: keep remote's title but the latest timestamp of the two.
        val resolver = ConflictResolver<Note> { local, remote ->
            remote.copy(lastModified = maxOf(local.lastModified, remote.lastModified))
        }

        val local = note("1", "local", lastModified = 300L)
        val remote = note("1", "remote", lastModified = 100L)

        val winner = resolver.resolve(local, remote)
        assertEquals("remote", winner.title)
        assertEquals(300L, winner.lastModified)
    }
}
