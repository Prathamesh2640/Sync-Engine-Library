package io.github.prathamesh2640.sync.sample

import android.content.Context
import android.content.Intent
import io.github.prathamesh2640.sync.ui.SyncDashboard
import io.github.prathamesh2640.sync.ui.SyncDashboardActivity
import io.github.prathamesh2640.sync.ui.SyncDashboardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Debug variant: the `:sync-ui-dashboard` module is on the classpath, so wire it up.
 * Maps the app's [DashboardSnapshot] to the dashboard module's [SyncDashboardState].
 */
const val DASHBOARD_AVAILABLE: Boolean = true

fun installDashboard(
    scope: CoroutineScope,
    snapshots: StateFlow<DashboardSnapshot>,
    onTriggerSync: () -> Unit,
) {
    val state = snapshots
        .map { snapshot ->
            SyncDashboardState(
                syncState = snapshot.syncState,
                lastSyncTimestamp = snapshot.lastSyncTimestamp,
                pendingCount = snapshot.pending,
                failedCount = snapshot.failed,
                conflictCount = snapshot.conflict,
                lastError = snapshot.lastError,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, SyncDashboardState())
    SyncDashboard.install(state, onTriggerSync)
}

fun launchDashboard(context: Context) {
    context.startActivity(
        Intent(context, SyncDashboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
