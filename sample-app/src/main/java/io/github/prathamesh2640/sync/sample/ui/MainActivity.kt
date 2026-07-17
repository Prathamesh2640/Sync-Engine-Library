package io.github.prathamesh2640.sync.sample.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.sample.DASHBOARD_AVAILABLE
import io.github.prathamesh2640.sync.sample.SampleApp
import io.github.prathamesh2640.sync.sample.data.Note
import io.github.prathamesh2640.sync.sample.launchDashboard
import io.github.prathamesh2640.sync.sample.sync.NoteResolver
import kotlinx.coroutines.launch

/**
 * The sample's single screen: a list of notes with add / edit / delete, a manual
 * "Sync" action, an offline toggle, a conflict-resolver picker, a "Conflict"
 * action per note, and (debug builds) a button to the sync dashboard.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as SampleApp).repository
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NotesScreen(repository)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(repository: io.github.prathamesh2640.sync.sample.NotesRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val notes by repository.notes.collectAsStateWithLifecycle()
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    val online by repository.online.collectAsStateWithLifecycle()
    val resolver by repository.resolver.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                actions = {
                    TextButton(onClick = { scope.launch { repository.syncNow() } }) { Text("Sync") }
                    if (DASHBOARD_AVAILABLE) {
                        TextButton(onClick = { launchDashboard(context) }) { Text("Dashboard") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            StatusBar(
                state = snapshot.syncState,
                pending = snapshot.pending,
                failed = snapshot.failed,
                conflict = snapshot.conflict,
                lastError = snapshot.lastError,
                online = online,
                resolver = resolver,
                onToggleOnline = { repository.setOnline(it) },
                onPickResolver = { repository.setResolver(it) },
            )

            if (notes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notes yet — tap + to add one.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            onEdit = { editing = note },
                            onDelete = { scope.launch { repository.deleteNote(note) } },
                            onConflict = { scope.launch { repository.simulateConflict(note) } },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        NoteDialog(
            heading = "New note",
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { title, body -> scope.launch { repository.addNote(title, body) }; showAdd = false },
        )
    }
    editing?.let { note ->
        NoteDialog(
            heading = "Edit note",
            initial = note,
            onDismiss = { editing = null },
            onSave = { title, body -> scope.launch { repository.updateNote(note, title, body) }; editing = null },
        )
    }
}

@Composable
private fun StatusBar(
    state: SyncState,
    pending: Int,
    failed: Int,
    conflict: Int,
    lastError: String?,
    online: Boolean,
    resolver: NoteResolver,
    onToggleOnline: (Boolean) -> Unit,
    onPickResolver: (NoteResolver) -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "State: ${state.name}   •   pending $pending   •   failed $failed   •   conflict $conflict",
                style = MaterialTheme.typography.labelLarge,
            )
            lastError?.let {
                Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Online")
                Switch(checked = online, onCheckedChange = onToggleOnline)
                Spacer(Modifier.weight(1f))
                ResolverPicker(current = resolver, onPick = onPickResolver)
            }
        }
    }
}

@Composable
private fun ResolverPicker(current: NoteResolver, onPick: (NoteResolver) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Resolver: ${current.label}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NoteResolver.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onPick(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun NoteRow(note: Note, onEdit: () -> Unit, onDelete: () -> Unit, onConflict: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StateBadge(note.syncState)
            }
            if (note.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(note.body, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onConflict) { Text("Conflict") }
            }
        }
    }
}

@Composable
private fun StateBadge(state: SyncState) {
    val color = when (state) {
        SyncState.SYNCED -> Color(0xFF2E7D32)
        SyncState.SYNCING -> Color(0xFF1565C0)
        SyncState.PENDING -> Color(0xFF616161)
        SyncState.FAILED -> Color(0xFFC62828)
        SyncState.CONFLICT -> Color(0xFFE65100)
    }
    Text(state.name, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun NoteDialog(
    heading: String,
    initial: Note?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var body by remember { mutableStateOf(initial?.body.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Body") })
            }
        },
        confirmButton = { TextButton(onClick = { if (title.isNotBlank()) onSave(title, body) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
