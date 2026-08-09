package io.github.prathamesh2640.sync.sample

import android.content.Context
import androidx.room.Room
import io.github.prathamesh2640.sync.core.adapter.ConflictResolver
import io.github.prathamesh2640.sync.core.engine.SyncEngine
import io.github.prathamesh2640.sync.core.engine.SyncEngineConfig
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.result.SyncError
import io.github.prathamesh2640.sync.core.result.SyncResult
import io.github.prathamesh2640.sync.room.RoomSyncAdapter
import io.github.prathamesh2640.sync.sample.data.Note
import io.github.prathamesh2640.sync.sample.data.NoteDatabase
import io.github.prathamesh2640.sync.sample.data.NoteWithState
import io.github.prathamesh2640.sync.sample.net.FakeNoteApiAdapter
import io.github.prathamesh2640.sync.sample.net.InMemorySyncApi
import io.github.prathamesh2640.sync.sample.sync.NoteResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single wiring point for the sample: owns the Room database, the in-memory
 * "server", the [RoomSyncAdapter] store, and the [SyncEngine], and exposes simple
 * state + actions for the UI.
 *
 * The engine is created once with a delegating [ConflictResolver] that reads the
 * currently-selected [NoteResolver], so the strategy can be switched at runtime
 * without rebuilding the engine.
 *
 * The notes list is a live Room `Flow` ([io.github.prathamesh2640.sync.sample.data.NoteDao.activeNotes]) —
 * `RoomSyncAdapter` notifies Room's invalidation tracker after its raw-SQL writes,
 * so it re-emits on its own after a local edit or a sync. The dashboard snapshot
 * ([snapshot]) is still refreshed explicitly; its counts aren't Flow-backed yet.
 */
class NotesRepository(context: Context, private val scope: CoroutineScope) {

    private val database = Room.databaseBuilder(
        context.applicationContext,
        NoteDatabase::class.java,
        "notes.db",
    ).addMigrations(NoteDatabase.MIGRATION_1_2).build()

    private val dao = database.noteDao()

    /** The simulated backend; exposed so the UI can toggle connectivity. */
    val api = InMemorySyncApi()

    private val store = RoomSyncAdapter<Note>(
        database = database,
        tableName = "notes",
        metadataTable = "notes_sync_meta",
        rawQuery = dao::rawQuery,
        upsert = dao::upsertAll,
    )

    private val resolverSelection = MutableStateFlow(NoteResolver.DEFAULT)

    /** The engine drives sync; host owns its lifecycle (see [close]). */
    val engine: SyncEngine = SyncEngine.create(
        adapter = FakeNoteApiAdapter(api),
        config = SyncEngineConfig { batchSize = 50 },
        store = store,
        resolver = ConflictResolver { local, remote ->
            resolverSelection.value.resolver.resolve(local, remote)
        },
    )

    private val _notes = MutableStateFlow<List<NoteWithState>>(emptyList())
    val notes: StateFlow<List<NoteWithState>> = _notes.asStateFlow()

    private val _snapshot = MutableStateFlow(DashboardSnapshot())
    val snapshot: StateFlow<DashboardSnapshot> = _snapshot.asStateFlow()

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    val resolver: StateFlow<NoteResolver> = resolverSelection.asStateFlow()

    private var lastSyncTimestamp: Long? = null
    private var lastError: String? = null

    init {
        // Keep the dashboard state live as the engine's state changes.
        scope.launch { engine.syncState.collect { refreshSnapshot() } }
        scope.launch { dao.activeNotes().collect { _notes.value = it } }
        scope.launch { refreshSnapshot() }
    }

    private suspend fun refreshSnapshot() {
        _snapshot.value = DashboardSnapshot(
            syncState = engine.syncState.value,
            lastSyncTimestamp = lastSyncTimestamp,
            pending = dao.countOf(SyncState.PENDING),
            failed = dao.countOf(SyncState.FAILED),
            conflict = dao.countOf(SyncState.CONFLICT),
            lastError = lastError,
        )
    }

    suspend fun addNote(title: String, body: String) {
        val note = Note(title = title.trim(), body = body.trim())
        dao.upsert(note)
        store.markSyncState(note.id, SyncState.PENDING) // enqueue — no column default does this anymore
        refreshSnapshot()
    }

    suspend fun updateNote(note: Note, title: String, body: String) {
        dao.upsert(note.copy(title = title.trim(), body = body.trim(), lastModified = System.currentTimeMillis()))
        store.markSyncState(note.id, SyncState.PENDING)
        refreshSnapshot()
    }

    /** Soft-delete (tombstone): the row stays until the server confirms deletion. */
    suspend fun deleteNote(note: Note) {
        dao.upsert(note.copy(lastModified = System.currentTimeMillis()))
        store.markDeleted(note.id)
        refreshSnapshot()
    }

    suspend fun syncNow(): SyncResult {
        val result = engine.triggerSync()
        lastSyncTimestamp = System.currentTimeMillis()
        lastError = result.toErrorText()
        refreshSnapshot()
        return result
    }

    fun setResolver(selection: NoteResolver) {
        resolverSelection.value = selection
    }

    fun setOnline(online: Boolean) {
        api.online = online
        _online.value = online
    }

    /**
     * Force a conflict: edit the note locally (→ PENDING) *and* change it on the
     * server with a newer timestamp. The next sync detects the divergence and
     * applies the selected [NoteResolver].
     */
    suspend fun simulateConflict(note: Note) {
        val now = System.currentTimeMillis()
        dao.upsert(note.copy(body = note.body + " [local edit]", lastModified = now))
        store.markSyncState(note.id, SyncState.PENDING)
        api.seedRemoteEdit(note.copy(body = note.body + " [server edit]", lastModified = now + 1_000))
        refreshSnapshot()
    }

    fun close() {
        engine.close()
        database.close()
    }

    private fun SyncResult.toErrorText(): String? = when (this) {
        is SyncResult.Success -> null
        is SyncResult.PartialFailure -> "$failedCount failed (${errors.firstOrNull()?.describe() ?: "unknown"})"
        is SyncResult.Failure -> error.describe()
    }

    private fun SyncError.describe(): String = when (this) {
        SyncError.NetworkUnavailable -> "network unavailable"
        is SyncError.HttpError -> "HTTP $code"
        is SyncError.ConflictUnresolvable -> "conflict on $entityId"
        is SyncError.StorageError -> "storage error"
    }
}
