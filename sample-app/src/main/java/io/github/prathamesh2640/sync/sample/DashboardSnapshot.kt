package io.github.prathamesh2640.sync.sample

import io.github.prathamesh2640.sync.core.model.SyncState

/**
 * App-owned snapshot of sync status, using only `:sync-core` + stdlib types.
 *
 * It deliberately references no `:sync-ui-dashboard` type, so `main` code can hold
 * and update it in every build variant. The debug-only `DashboardIntegration`
 * maps it to the dashboard module's `SyncDashboardState`; the release variant
 * ignores it. See `src/debug` / `src/release`.
 */
data class DashboardSnapshot(
    val syncState: SyncState = SyncState.PENDING,
    val lastSyncTimestamp: Long? = null,
    val pending: Int = 0,
    val failed: Int = 0,
    val conflict: Int = 0,
    val lastError: String? = null,
)
