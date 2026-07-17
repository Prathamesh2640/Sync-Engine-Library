package io.github.prathamesh2640.sync.sample.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import java.util.UUID

/**
 * The sample app's syncable domain object — a note.
 *
 * Implements [SyncableEntity] with the four sync columns under their default
 * names (required by `RoomSyncAdapter`). Room stores the [SyncState] enum as its
 * name string natively, no `TypeConverter` needed.
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val body: String = "",
    override val lastModified: Long = System.currentTimeMillis(),
    override val syncState: SyncState = SyncState.PENDING,
    override val isDeleted: Boolean = false,
) : SyncableEntity
