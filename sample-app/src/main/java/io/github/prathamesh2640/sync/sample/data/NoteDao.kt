package io.github.prathamesh2640.sync.sample.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.prathamesh2640.sync.core.model.SyncState
import kotlinx.coroutines.flow.Flow

/** A [Note] joined with its [NoteSyncMeta.syncState], for list-UI display. */
data class NoteWithState(
    @Embedded val note: Note,
    val syncState: SyncState,
)

/**
 * Plain `@Dao` for [Note] — no generic supertype (a generic `@Dao` crashes Room's
 * KSP). `RoomSyncAdapter` is wired to [upsertAll] and [rawQuery]; it never reads
 * [NoteSyncMeta] through this DAO — its own raw SQL against `notes_sync_meta`
 * handles that (ADL-022). The read-only joins here are for the UI only.
 *
 * [activeNotes] is a `Flow`: `RoomSyncAdapter` calls `refreshVersionsAsync()` after
 * every raw-SQL write to `notes_sync_meta`, so Room's invalidation tracker does
 * pick up engine writes and this query re-emits on its own — no manual refresh
 * needed after a sync or a local edit.
 */
@Dao
interface NoteDao {

    @Upsert
    suspend fun upsertAll(notes: List<Note>)

    @Upsert
    suspend fun upsert(note: Note)

    /** Backs `RoomSyncAdapter`'s state-scoped reads. */
    @RawQuery
    suspend fun rawQuery(query: SupportSQLiteQuery): List<Note>

    /** Active (non-deleted) notes for the list UI, newest first, joined with their sync state. */
    @Query(
        "SELECT notes.*, notes_sync_meta.syncState AS syncState FROM notes " +
            "JOIN notes_sync_meta ON notes.id = notes_sync_meta.id " +
            "WHERE notes_sync_meta.isDeleted = 0 ORDER BY notes.lastModified DESC",
    )
    fun activeNotes(): Flow<List<NoteWithState>>

    /** Count of notes in a given [SyncState], for the dashboard counters. */
    @Query("SELECT COUNT(*) FROM notes_sync_meta WHERE syncState = :state")
    suspend fun countOf(state: SyncState): Int

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Note?
}
