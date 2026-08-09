package io.github.prathamesh2640.sync.core.model

/**
 * Counts of entities in each "not yet done" [SyncState] a [SyncableEntity]'s
 * local row can be in — what a dashboard typically needs.
 *
 * Returned by [io.github.prathamesh2640.sync.core.store.LocalSyncStore.counts].
 *
 * @property pending entities with [SyncState.PENDING].
 * @property failed entities with [SyncState.FAILED].
 * @property conflict entities with [SyncState.CONFLICT].
 */
public data class SyncCounts(
    public val pending: Int,
    public val failed: Int,
    public val conflict: Int,
) {
    public companion object {
        /** All-zero counts — what a `null` (in-memory-only) store reports. */
        public val ZERO: SyncCounts = SyncCounts(pending = 0, failed = 0, conflict = 0)
    }
}
