package io.github.prathamesh2640.sync.sample.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.prathamesh2640.sync.core.model.SyncState

/**
 * Plain `@Dao` for [Note] — no generic supertype (a generic `@Dao` crashes Room's
 * KSP). `RoomSyncAdapter` is wired to [upsertAll] and [rawQuery].
 *
 * Reads are one-shot suspend queries (not `Flow`): the engine writes sync state
 * through raw SQL, which bypasses Room's invalidation tracker, so the app refreshes
 * explicitly after edits and syncs rather than relying on observable queries.
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

    /** Active (non-deleted) notes for the list UI, newest first. */
    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY lastModified DESC")
    suspend fun activeNotes(): List<Note>

    /** Count of notes in a given [SyncState], for the dashboard counters. */
    @Query("SELECT COUNT(*) FROM notes WHERE syncState = :state")
    suspend fun countOf(state: SyncState): Int

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Note?
}
