package io.github.prathamesh2640.sync.sample.data

import androidx.room.Database
import io.github.prathamesh2640.sync.room.SyncDatabase

/**
 * The app's Room database. Extends [SyncDatabase] (optional) to mark it as a
 * SyncEngine-backed store. No `fallbackToDestructiveMigration()` — the migration
 * contract protects unsynced local changes.
 */
@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class NoteDatabase : SyncDatabase() {
    abstract fun noteDao(): NoteDao
}
