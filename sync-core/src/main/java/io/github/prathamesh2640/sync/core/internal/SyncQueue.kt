package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.model.SyncableEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe, in-memory queue of entities waiting to be synced.
 *
 * Backed by a [LinkedHashMap] keyed on [SyncableEntity.id], which gives two
 * properties at once:
 *  - **FIFO order** — iteration order is insertion order, so [drainBatch] returns
 *    the oldest pending entities first.
 *  - **Coalescing by id** — enqueuing an entity whose id is already queued
 *    replaces the queued copy with the newer one instead of adding a duplicate.
 *    A burst of edits to the same record therefore syncs once, with the latest
 *    value, and the id (a UUID v4 — the idempotency key) stays unique in the queue.
 *
 * All access is serialised through a [Mutex]; the class is safe to share across
 * coroutines and dispatchers. It holds no Android references and is fully
 * JVM-testable.
 *
 * This type is **internal** — the queue is an implementation detail of the
 * engine, never part of the public API (module-guide: "`SyncQueue` … must stay
 * `internal`").
 *
 * @param T the [SyncableEntity] subtype held in the queue.
 */
internal class SyncQueue<T : SyncableEntity> {

    private val mutex = Mutex()

    // insertion-ordered; key = entity id, value = latest queued version of it.
    private val pending = LinkedHashMap<String, T>()

    /**
     * Add [entity] to the queue, or replace the queued copy that shares its id.
     *
     * @return `true` if this was a new id, `false` if it coalesced onto an
     *   already-queued entity.
     */
    suspend fun enqueue(entity: T): Boolean = mutex.withLock {
        val isNew = !pending.containsKey(entity.id)
        pending[entity.id] = entity
        isNew
    }

    /**
     * Add every entity in [entities], applying the same coalescing rule as
     * [enqueue] to each.
     */
    suspend fun enqueueAll(entities: Collection<T>): Unit = mutex.withLock {
        for (entity in entities) {
            pending[entity.id] = entity
        }
    }

    /**
     * Remove and return up to [size] entities in FIFO order.
     *
     * Returns fewer than [size] items when the queue holds fewer; returns an
     * empty list when the queue is empty or [size] is not positive. The returned
     * entities are removed from the queue — a caller that fails to sync one must
     * re-[enqueue] it.
     *
     * @param size the maximum number of entities to drain (typically the
     *   configured batch size). Values `<= 0` drain nothing.
     */
    suspend fun drainBatch(size: Int): List<T> {
        if (size <= 0) return emptyList()
        return mutex.withLock {
            if (pending.isEmpty()) return@withLock emptyList()
            val iterator = pending.entries.iterator()
            val batch = ArrayList<T>(minOf(size, pending.size))
            while (iterator.hasNext() && batch.size < size) {
                batch.add(iterator.next().value)
                iterator.remove()
            }
            batch
        }
    }

}
