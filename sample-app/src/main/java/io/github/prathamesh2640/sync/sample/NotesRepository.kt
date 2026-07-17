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
 * Reads are explicit (see [refresh]) because the engine's raw-SQL state writes
 * bypass Room's observable-query invalidation.
 */
class NotesRepository(context: Context, private val scope: CoroutineScope) {

    private val database = Room.databaseBuilder(
        context.applicationContext,
        NoteDatabase::class.java,
        "notes.db",
    ).build()

    private val dao = database.noteDao()

    /** The simulated backend; exposed so the UI can toggle connectivity. */
    val api = InMemorySyncApi()

    private val store = RoomSyncAdapter<Note>(
        database = database,
        tableName = "notes",
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

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

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
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        _notes.value = dao.activeNotes()
        refreshSnapshot()
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
        dao.upsert(Note(title = title.trim(), body = body.trim()))
        refresh()
    }

    suspend fun updateNote(note: Note, title: String, body: String) {
        dao.upsert(
            note.copy(
                title = title.trim(),
                body = body.trim(),
                lastModified = System.currentTimeMillis(),
                syncState = SyncState.PENDING,
            ),
        )
        refresh()
    }

    /** Soft-delete (tombstone): the row stays until the server confirms deletion. */
    suspend fun deleteNote(note: Note) {
        dao.upsert(
            note.copy(
                isDeleted = true,
                lastModified = System.currentTimeMillis(),
                syncState = SyncState.PENDING,
            ),
        )
        refresh()
    }

    suspend fun syncNow(): SyncResult {
        val result = engine.triggerSync()
        lastSyncTimestamp = System.currentTimeMillis()
        lastError = result.toErrorText()
        refresh()
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
        dao.upsert(note.copy(body = note.body + " [local edit]", lastModified = now, syncState = SyncState.PENDING))
        api.seedRemoteEdit(note.copy(body = note.body + " [server edit]", lastModified = now + 1_000))
        refresh()
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
