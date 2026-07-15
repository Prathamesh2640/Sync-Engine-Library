package io.github.prathamesh2640.sync.core.result

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SyncResult].
 *
 * Covers every sealed branch, the enriched payloads (conflict count, error
 * list), and locks the branch set via an exhaustive `when`.
 */
class SyncResultTest {

    @Test
    fun `Success carries synced and conflict counts`() {
        val result = SyncResult.Success(syncedCount = 10, conflictCount = 2)
        assertEquals(10, result.syncedCount)
        assertEquals(2, result.conflictCount)
    }

    @Test
    fun `PartialFailure carries counts and per-entity errors`() {
        val errors = listOf(SyncError.HttpError(500), SyncError.NetworkUnavailable)
        val result = SyncResult.PartialFailure(syncedCount = 8, failedCount = 2, errors = errors)
        assertEquals(8, result.syncedCount)
        assertEquals(2, result.failedCount)
        assertEquals(errors, result.errors)
    }

    @Test
    fun `Failure carries a single error`() {
        val result = SyncResult.Failure(SyncError.NetworkUnavailable)
        assertEquals(SyncError.NetworkUnavailable, result.error)
    }

    @Test
    fun `data class equality holds`() {
        assertEquals(SyncResult.Success(1, 0), SyncResult.Success(1, 0))
        assertEquals(
            SyncResult.Failure(SyncError.HttpError(500)),
            SyncResult.Failure(SyncError.HttpError(500)),
        )
    }

    @Test
    fun `exhaustive when covers every branch`() {
        val samples: List<SyncResult> = listOf(
            SyncResult.Success(1, 0),
            SyncResult.PartialFailure(1, 1, listOf(SyncError.NetworkUnavailable)),
            SyncResult.Failure(SyncError.NetworkUnavailable),
        )
        val labels = samples.map { result ->
            when (result) {
                is SyncResult.Success -> "success"
                is SyncResult.PartialFailure -> "partial"
                is SyncResult.Failure -> "failure"
            }
        }
        assertEquals(listOf("success", "partial", "failure"), labels)
        // The `when` above has no `else`: adding/removing a branch is a compile error.
        assertEquals(3, labels.toSet().size)
    }
}
