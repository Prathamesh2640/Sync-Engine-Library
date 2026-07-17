package io.github.prathamesh2640.sync.sample

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Release variant: the debug-only `:sync-ui-dashboard` module is NOT on the
 * classpath, so these are no-ops. Same signatures as the debug variant so `main`
 * code compiles unchanged in both builds.
 */
const val DASHBOARD_AVAILABLE: Boolean = false

@Suppress("UNUSED_PARAMETER")
fun installDashboard(
    scope: CoroutineScope,
    snapshots: StateFlow<DashboardSnapshot>,
    onTriggerSync: () -> Unit,
) {
    // No dashboard in release builds.
}

@Suppress("UNUSED_PARAMETER")
fun launchDashboard(context: Context) {
    // No dashboard in release builds.
}
