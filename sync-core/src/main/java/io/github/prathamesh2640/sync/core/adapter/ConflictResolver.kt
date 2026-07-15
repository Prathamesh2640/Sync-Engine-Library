package io.github.prathamesh2640.sync.core.adapter

import io.github.prathamesh2640.sync.core.model.SyncableEntity

/**
 * Strategy for reconciling a local and a remote version of the same entity when
 * both changed since the last successful sync.
 *
 * The host app supplies the strategy; the engine invokes [resolve] whenever it
 * detects that an entity's local and remote copies diverge (same [id][SyncableEntity.id],
 * different content). The returned value becomes the authoritative version that
 * is persisted locally and pushed to the remote.
 *
 * This is a single-method (`fun`) interface, so it can be provided as a lambda:
 *
 * ```kotlin
 * // Last-Write-Wins: the copy with the newer timestamp survives.
 * val resolver = ConflictResolver<Note> { local, remote ->
 *     if (local.lastModified >= remote.lastModified) local else remote
 * }
 * ```
 *
 * Implementations must be **pure and side-effect free**: given the same inputs
 * they must return the same result, must not perform I/O, and must not mutate
 * either argument. The engine may call [resolve] off the main thread.
 *
 * @param T the concrete [SyncableEntity] type this resolver reconciles.
 */
public fun interface ConflictResolver<T : SyncableEntity> {

    /**
     * Choose (or synthesise) the winning version of a conflicted entity.
     *
     * @param local the version currently held in the local store.
     * @param remote the version returned by the remote.
     * @return the version to keep — usually [local] or [remote], but an
     *   implementation may also return a merged instance built from both.
     */
    public fun resolve(local: T, remote: T): T
}
