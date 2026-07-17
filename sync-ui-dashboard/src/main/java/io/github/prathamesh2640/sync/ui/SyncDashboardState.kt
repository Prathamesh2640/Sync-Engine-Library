package io.github.prathamesh2640.sync.ui

import io.github.prathamesh2640.sync.core.model.SyncState

/**
 * Immutable snapshot the dashboard renders.
 *
 * The host builds and updates this (usually a `StateFlow<SyncDashboardState>`)
 * from its engine and store — the dashboard only displays it, so it stays
 * decoupled from Room/WorkManager and depends on `:sync-core` alone.
 *
 * @property syncState the engine's current overall state.
 * @property lastSyncTimestamp epoch-ms of the last completed run, or `null` if
 *   none has finished yet.
 * @property pendingCount entities awaiting sync.
 * @property failedCount entities whose last sync failed.
 * @property conflictCount entities left unresolved in [SyncState.CONFLICT].
 * @property lastError a short human-readable description of the last error, or
 *   `null` when the last run was clean.
 */
public data class SyncDashboardState(
    val syncState: SyncState = SyncState.PENDING,
    val lastSyncTimestamp: Long? = null,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val conflictCount: Int = 0,
    val lastError: String? = null,
)
