package io.github.prathamesh2640.sync.sample.net

import io.github.prathamesh2640.sync.sample.data.Note
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A tiny in-memory stand-in for a real backend, so the sample runs fully offline
 * and deterministically. Thread-safe via a [Mutex].
 *
 * [online] toggles simulated connectivity; [seedRemoteEdit] injects a concurrent
 * server-side change to demonstrate conflict resolution. The server has no
 * concept of sync state — that's purely local (ADL-022) — so it just stores
 * [Note]s as-is.
 */
class InMemorySyncApi {

    private val mutex = Mutex()
    private val server = LinkedHashMap<String, Note>()

    @Volatile
    var online: Boolean = true

    /** Accept a pushed batch. */
    suspend fun push(notes: List<Note>): Unit = mutex.withLock {
        for (note in notes) server[note.id] = note
    }

    /** Return every server note changed strictly after [since]. */
    suspend fun pull(since: Long): List<Note> = mutex.withLock {
        server.values.filter { it.lastModified > since }.map { it.copy() }
    }

    /** Hard-delete confirmed tombstones. */
    suspend fun delete(ids: List<String>): Unit = mutex.withLock {
        ids.forEach { server.remove(it) }
    }

    /** Simulate another device editing [note] on the server (for the conflict demo). */
    suspend fun seedRemoteEdit(note: Note): Unit = mutex.withLock {
        server[note.id] = note
    }

    /** Current server contents (for diagnostics/tests). */
    suspend fun snapshot(): List<Note> = mutex.withLock { server.values.map { it.copy() } }
}
