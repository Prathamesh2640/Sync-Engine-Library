package io.github.prathamesh2640.sync.sample.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.prathamesh2640.sync.core.model.SyncState

/**
 * The `notes_sync_meta` side table [RoomSyncAdapter][io.github.prathamesh2640.sync.room.RoomSyncAdapter]
 * reads/writes via raw SQL (ADL-022 in the library's memory.md). Declared as a
 * Room `@Entity` only so this app's own schema creates and migrates the table;
 * [NoteDao] never queries it through Room's typed write API, only read-only
 * `@Query` joins for the UI.
 */
@Entity(tableName = "notes_sync_meta")
data class NoteSyncMeta(
    @PrimaryKey val id: String,
    val syncState: SyncState,
    val isDeleted: Boolean = false,
)
