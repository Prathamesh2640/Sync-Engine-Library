package io.github.prathamesh2640.sync.sample

import android.content.Context
import androidx.room.Room
import io.github.prathamesh2640.sync.core.adapter.ConflictResolver
import io.github.prathamesh2640.sync.core.engine.SyncEngine
import io.github.prathamesh2640.sync.core.engine.SyncEngineConfig
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * so it re-emits on its own after a local edit or a sync. [snapshot] is likewise
 * derived straight from [SyncEngine.syncState]/[SyncEngine.stats] — the engine
 * itself tracks pending/failed/conflict counts and the last run's outcome now, so
 * this repository does no hand-counting or manual bookkeeping of its own.
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

    val snapshot: StateFlow<DashboardSnapshot> = combine(engine.syncState, engine.stats) { state, stats ->
        DashboardSnapshot(
            syncState = state,
            lastSyncTimestamp = stats.lastSyncTimestamp,
            pending = stats.pending,
            failed = stats.failed,
            conflict = stats.conflict,
            lastError = stats.lastError?.describe(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, DashboardSnapshot())

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    val resolver: StateFlow<NoteResolver> = resolverSelection.asStateFlow()

    init {
        scope.launch { dao.activeNotes().collect { _notes.value = it } }
    }

    suspend fun addNote(title: String, body: String) {
        val note = Note(title = title.trim(), body = body.trim())
        store.enqueue(note) // persists + marks PENDING — no column default does this anymore
    }

    suspend fun updateNote(note: Note, title: String, body: String) {
        store.enqueue(note.copy(title = title.trim(), body = body.trim(), lastModified = System.currentTimeMillis()))
    }

    /** Soft-delete (tombstone): the row stays until the server confirms deletion. */
    suspend fun deleteNote(note: Note) {
        dao.upsert(note.copy(lastModified = System.currentTimeMillis()))
        store.markDeleted(note.id)
    }

    suspend fun syncNow(): SyncResult = engine.triggerSync()

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
        store.enqueue(note.copy(body = note.body + " [local edit]", lastModified = now))
        api.seedRemoteEdit(note.copy(body = note.body + " [server edit]", lastModified = now + 1_000))
    }

    fun close() {
        engine.close()
        database.close()
    }

    private fun SyncError.describe(): String = when (this) {
        SyncError.NetworkUnavailable -> "network unavailable"
        is SyncError.HttpError -> "HTTP $code"
        is SyncError.ConflictUnresolvable -> "conflict on $entityId"
        is SyncError.StorageError -> "storage error"
    }
}
