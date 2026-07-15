package io.github.prathamesh2640.sync.core.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [NetworkResult].
 *
 * Covers every sealed branch: construction, payload access, equality, and an
 * exhaustive `when` that locks the branch set. If a branch is added or removed,
 * [exhaustive_when_covers_every_branch] stops compiling — the intended tripwire.
 */
class NetworkResultTest {

    @Test
    fun `Success carries its payload`() {
        val result = NetworkResult.Success(listOf("a", "b"))
        assertEquals(listOf("a", "b"), result.data)
    }

    @Test
    fun `Success of Unit models an empty body`() {
        // Keep the Success type: `data` lives on the Success branch, not the base.
        val result = NetworkResult.Success(Unit)
        assertEquals(Unit, result.data)
        // Compile-time proof Success is assignable to the base type used in signatures.
        val asBase: NetworkResult<Unit> = result
        assertSame(result, asBase)
    }

    @Test
    fun `HttpError carries code and message`() {
        val result = NetworkResult.HttpError(code = 409, message = "Conflict")
        assertEquals(409, result.code)
        assertEquals("Conflict", result.message)
    }

    @Test
    fun `NetworkError carries its cause`() {
        val cause = IOException("connection refused")
        val result = NetworkResult.NetworkError(cause)
        assertSame(cause, result.cause)
    }

    @Test
    fun `UnknownError carries its cause`() {
        val cause = IllegalStateException("bad json")
        val result = NetworkResult.UnknownError(cause)
        assertSame(cause, result.cause)
    }

    @Test
    fun `data class equality holds for identical branches`() {
        assertEquals(NetworkResult.HttpError(500, "x"), NetworkResult.HttpError(500, "x"))
        assertEquals(NetworkResult.Success(1), NetworkResult.Success(1))
    }

    @Test
    fun `exhaustive when covers every branch`() {
        val samples: List<NetworkResult<Int>> = listOf(
            NetworkResult.Success(1),
            NetworkResult.HttpError(500, "err"),
            NetworkResult.NetworkError(IOException()),
            NetworkResult.UnknownError(RuntimeException()),
        )
        // No `else`: compilation fails if the branch set changes.
        val labels = samples.map { result ->
            when (result) {
                is NetworkResult.Success -> "success"
                is NetworkResult.HttpError -> "http"
                is NetworkResult.NetworkError -> "network"
                is NetworkResult.UnknownError -> "unknown"
            }
        }
        // Exactly four distinct branches are represented; the `when` above has no
        // `else`, so adding or removing a branch is a compile error — a stronger
        // guarantee than any runtime count.
        assertEquals(listOf("success", "http", "network", "unknown"), labels)
        assertEquals(4, labels.toSet().size)
    }
}
