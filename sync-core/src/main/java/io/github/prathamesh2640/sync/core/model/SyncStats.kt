package io.github.prathamesh2640.sync.core.model

import io.github.prathamesh2640.sync.core.result.SyncError

/**
 * A snapshot of engine-observed sync activity, exposed via
 * [io.github.prathamesh2640.sync.core.engine.SyncEngine.stats] — what a
 * dashboard needs beyond the single [SyncState] enum
 * [io.github.prathamesh2640.sync.core.engine.SyncEngine.syncState] exposes.
 *
 * Updated once at the end of every
 * [io.github.prathamesh2640.sync.core.engine.SyncEngine.triggerSync] run, not
 * on every local write — [pending]/[failed]/[conflict] reflect local storage
 * as of the last sync run, not live. A store-backed engine also populates
 * [pending]/[failed]/[conflict] once at construction (best-effort, before any
 * sync has run) so a freshly-launched host doesn't show stale zeros for
 * pre-existing local work; [lastSyncTimestamp]/[lastError] stay `null` until a
 * sync actually completes.
 *
 * @property pending entities with [SyncState.PENDING], from [io.github.prathamesh2640.sync.core.store.LocalSyncStore.counts]
 *   (`0` when the engine has no store).
 * @property failed entities with [SyncState.FAILED].
 * @property conflict entities with [SyncState.CONFLICT].
 * @property lastSyncTimestamp epoch ms when the last [io.github.prathamesh2640.sync.core.engine.SyncEngine.triggerSync]
 *   run finished, or `null` if none has completed yet.
 * @property lastError the first error from the last run, or `null` if it fully succeeded (or none has run yet).
 */
public data class SyncStats(
    public val pending: Int,
    public val failed: Int,
    public val conflict: Int,
    public val lastSyncTimestamp: Long?,
    public val lastError: SyncError?,
) {
    public companion object {
        /** Before any [io.github.prathamesh2640.sync.core.store.LocalSyncStore.counts] refresh has happened. */
        public val INITIAL: SyncStats = SyncStats(
            pending = 0,
            failed = 0,
            conflict = 0,
            lastSyncTimestamp = null,
            lastError = null,
        )
    }
}
