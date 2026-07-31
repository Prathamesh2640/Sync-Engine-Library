package io.github.prathamesh2640.sync.sample.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's Room database. No `fallbackToDestructiveMigration()` — that would drop
 * every table on a version bump, silently destroying unsynced local changes and
 * tombstones. Ship explicit, additive migrations instead.
 */
@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
