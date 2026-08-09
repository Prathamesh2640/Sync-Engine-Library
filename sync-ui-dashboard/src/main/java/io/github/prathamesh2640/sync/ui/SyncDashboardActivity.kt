package io.github.prathamesh2640.sync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Standalone debug screen showing live sync status. Launch it from a debug menu
 * (e.g. a FAB in the sample app).
 *
 * It renders whatever the host installed via [SyncDashboard.install] under the
 * key from the [EXTRA_ENGINE_KEY] intent extra (defaults to
 * [SyncDashboard.DEFAULT_KEY] if unset — pass it explicitly to view a specific
 * engine's dashboard when more than one is installed); if nothing is installed
 * under that key it shows a hint instead of crashing. Ship it only in debug
 * builds (`debugImplementation(project(":sync-ui-dashboard"))`).
 */
public class SyncDashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getStringExtra(EXTRA_ENGINE_KEY) ?: SyncDashboard.DEFAULT_KEY
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state = SyncDashboard.stateFlowFor(key)
                    val onTrigger = SyncDashboard.onTriggerSyncFor(key)
                    if (state != null && onTrigger != null) {
                        SyncDashboardRoute(state = state, onTriggerSync = onTrigger)
                    } else {
                        NotInstalled()
                    }
                }
            }
        }
    }

    public companion object {
        /** Optional intent extra selecting which installed dashboard to show; unset means [SyncDashboard.DEFAULT_KEY]. */
        public const val EXTRA_ENGINE_KEY: String = "io.github.prathamesh2640.sync.ui.ENGINE_KEY"
    }
}

@Composable
private fun NotInstalled() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("No dashboard source installed. Call SyncDashboard.install(...) first.")
    }
}
