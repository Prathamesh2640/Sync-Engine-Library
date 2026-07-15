package io.github.prathamesh2640.sync.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [SyncError].
 *
 * Covers every sealed branch and locks the branch set via an exhaustive `when`.
 */
class SyncErrorTest {

    @Test
    fun `NetworkUnavailable is a singleton`() {
        assertSame(SyncError.NetworkUnavailable, SyncError.NetworkUnavailable)
    }

    @Test
    fun `HttpError carries its code`() {
        assertEquals(401, SyncError.HttpError(401).code)
    }

    @Test
    fun `ConflictUnresolvable carries the entity id`() {
        assertEquals("note-7", SyncError.ConflictUnresolvable("note-7").entityId)
    }

    @Test
    fun `StorageError carries its cause`() {
        val cause = IOException("disk full")
        assertSame(cause, SyncError.StorageError(cause).cause)
    }

    @Test
    fun `data class equality holds`() {
        assertEquals(SyncError.HttpError(500), SyncError.HttpError(500))
        assertEquals(SyncError.ConflictUnresolvable("x"), SyncError.ConflictUnresolvable("x"))
    }

    @Test
    fun `exhaustive when covers every branch`() {
        val samples: List<SyncError> = listOf(
            SyncError.NetworkUnavailable,
            SyncError.HttpError(500),
            SyncError.ConflictUnresolvable("1"),
            SyncError.StorageError(IOException()),
        )
        val labels = samples.map { error ->
            when (error) {
                SyncError.NetworkUnavailable -> "network"
                is SyncError.HttpError -> "http"
                is SyncError.ConflictUnresolvable -> "conflict"
                is SyncError.StorageError -> "storage"
            }
        }
        assertEquals(listOf("network", "http", "conflict", "storage"), labels)
        // The `when` above has no `else`: adding/removing a branch is a compile error.
        assertEquals(4, labels.toSet().size)
    }
}
