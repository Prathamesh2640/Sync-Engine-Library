package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.testing.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncQueueTest {

    private fun note(id: String, lastModified: Long = 0L) =
        Note(id = id, title = "t-$id", lastModified = lastModified)

    @Test
    fun `enqueue then drain returns items in FIFO order`() = runTest {
        val queue = SyncQueue<Note>()
        queue.enqueue(note("a"))
        queue.enqueue(note("b"))
        queue.enqueue(note("c"))

        val drained = queue.drainBatch(10).map { it.id }

        assertEquals(listOf("a", "b", "c"), drained)
        assertEquals(0, queue.size())
    }

    @Test
    fun `drainBatch returns exactly the requested count when more are queued`() = runTest {
        val queue = SyncQueue<Note>()
        repeat(100) { queue.enqueue(note("id-$it")) }

        val batch = queue.drainBatch(50)

        assertEquals(50, batch.size)
        assertEquals(50, queue.size())
        // The first 50 by insertion order are the ones drained.
        assertEquals("id-0", batch.first().id)
        assertEquals("id-49", batch.last().id)
    }

    @Test
    fun `drainBatch returns all when fewer are queued than requested`() = runTest {
        val queue = SyncQueue<Note>()
        repeat(3) { queue.enqueue(note("id-$it")) }

        assertEquals(3, queue.drainBatch(50).size)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `drainBatch with non-positive size drains nothing`() = runTest {
        val queue = SyncQueue<Note>()
        queue.enqueue(note("a"))

        assertTrue(queue.drainBatch(0).isEmpty())
        assertTrue(queue.drainBatch(-5).isEmpty())
        assertEquals(1, queue.size())
    }

    @Test
    fun `enqueue coalesces on id keeping the latest version`() = runTest {
        val queue = SyncQueue<Note>()

        assertTrue("first insert of an id is new", queue.enqueue(note("x", lastModified = 1L)))
        assertFalse("second insert of same id coalesces", queue.enqueue(note("x", lastModified = 2L)))

        assertEquals(1, queue.size())
        val drained = queue.drainBatch(10)
        assertEquals(1, drained.size)
        assertEquals("latest version wins", 2L, drained.single().lastModified)
    }

    @Test
    fun `concurrent enqueue of distinct ids loses no item`() = runBlocking(Dispatchers.Default) {
        val queue = SyncQueue<Note>()
        val coroutines = 10
        val perCoroutine = 500

        val jobs = (0 until coroutines).map { worker ->
            launch {
                repeat(perCoroutine) { i ->
                    queue.enqueue(note("w$worker-i$i"))
                }
            }
        }
        jobs.forEach { it.join() }

        assertEquals(coroutines * perCoroutine, queue.size())

        // Drain everything and confirm every id is unique and present.
        val all = mutableListOf<Note>()
        while (true) {
            val batch = queue.drainBatch(256)
            if (batch.isEmpty()) break
            all += batch
        }
        assertEquals(coroutines * perCoroutine, all.size)
        assertEquals(coroutines * perCoroutine, all.map { it.id }.toSet().size)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `enqueueAll adds every entity`() = runTest {
        val queue = SyncQueue<Note>()
        queue.enqueueAll(listOf(note("a"), note("b"), note("c")))
        assertEquals(3, queue.size())
    }
}
