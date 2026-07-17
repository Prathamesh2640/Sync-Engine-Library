package io.github.prathamesh2640.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.prathamesh2640.sync.core.model.SyncState
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The debug sync dashboard, wired to a live [StateFlow].
 *
 * Collects [state] with lifecycle awareness and renders the current sync status,
 * queue counters, last-sync time, last error, and a "Sync now" button. Intended
 * for debug builds — embed it in your own screen or launch [SyncDashboardActivity].
 *
 * @param state the observable dashboard state.
 * @param onTriggerSync invoked when the user taps "Sync now".
 * @param modifier layout modifier for the root.
 */
@Composable
public fun SyncDashboardRoute(
    state: StateFlow<SyncDashboardState>,
    onTriggerSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by state.collectAsStateWithLifecycle()
    SyncDashboardScreen(state = current, onTriggerSync = onTriggerSync, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncDashboardScreen(
    state: SyncDashboardState,
    onTriggerSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Sync Dashboard") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state.syncState)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CounterCard("Pending", state.pendingCount, Modifier.weight(1f))
                CounterCard("Failed", state.failedCount, Modifier.weight(1f))
                CounterCard("Conflict", state.conflictCount, Modifier.weight(1f))
            }

            Text(
                text = "Last sync: ${formatTimestamp(state.lastSyncTimestamp)}",
                style = MaterialTheme.typography.bodyMedium,
            )

            state.lastError?.let { error ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = "Last error: $error",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Button(onClick = onTriggerSync, modifier = Modifier.fillMaxWidth()) {
                Text("Sync now")
            }
        }
    }
}

@Composable
private fun StatusCard(state: SyncState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Current state", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.name,
                color = colorFor(state),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CounterCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun colorFor(state: SyncState): Color = when (state) {
    SyncState.SYNCED -> Color(0xFF2E7D32)
    SyncState.SYNCING -> Color(0xFF1565C0)
    SyncState.PENDING -> Color(0xFF616161)
    SyncState.FAILED -> Color(0xFFC62828)
    SyncState.CONFLICT -> Color(0xFFE65100)
}

private fun formatTimestamp(epochMs: Long?): String =
    if (epochMs == null) "never"
    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
