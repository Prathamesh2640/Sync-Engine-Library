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
 * ### Security — `remote` is untrusted input (SEC-09)
 * `remote` came from the network. If the server is compromised or a response is
 * tampered with in transit, [resolve] receives attacker-influenced data and its
 * return value is written straight into local storage. A naive
 * Last-Write-Wins strategy that blindly trusts `remote`'s timestamp lets a
 * malicious or clock-skewed server win every future conflict forever by
 * reporting a timestamp far in the future. Implementations that compare
 * timestamps should:
 * - treat equal (or missing/non-positive) timestamps as **local wins**, never
 *   remote, so an ambiguous comparison cannot silently discard local data;
 * - reject or clamp a `remote` timestamp that is implausibly far in the future
 *   relative to the device clock, rather than trusting it outright.
 *
 * See `NoteResolver.LAST_WRITE_WINS` in the sample app for a resolver that
 * applies this clock-skew guard.
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
