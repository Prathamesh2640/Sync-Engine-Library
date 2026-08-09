package io.github.prathamesh2640.sync.sample.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import java.util.UUID

/**
 * The sample app's syncable domain object — a note.
 *
 * Implements [SyncableEntity] with just `id`/`lastModified`; sync lifecycle
 * (state, tombstone flag) lives separately in [NoteSyncMeta] (ADL-022 in the
 * library's memory.md), not as fields here.
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val body: String = "",
    override val lastModified: Long = System.currentTimeMillis(),
) : SyncableEntity
