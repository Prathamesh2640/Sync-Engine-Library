package io.github.prathamesh2640.sync.sample.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database. No `fallbackToDestructiveMigration()` — that would drop
 * every table on a version bump, silently destroying unsynced local changes and
 * tombstones. Ship explicit, additive migrations instead (see [MIGRATION_1_2]).
 */
@Database(entities = [Note::class, NoteSyncMeta::class], version = 2, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        /**
         * v1 → v2: sync lifecycle moves off `notes.syncState`/`notes.isDeleted`
         * into the new `notes_sync_meta` side table (ADL-022 in the library's
         * memory.md). Backfills existing rows into it, then recreates `notes`
         * without the two dropped columns — SQLite has no `DROP COLUMN` before
         * 3.35, so a rename-recreate-drop is the portable way to shed them.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS notes_sync_meta (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "syncState TEXT NOT NULL, " +
                        "isDeleted INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL(
                    "INSERT INTO notes_sync_meta (id, syncState, isDeleted) " +
                        "SELECT id, syncState, isDeleted FROM notes",
                )
                db.execSQL(
                    "CREATE TABLE notes_new (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "title TEXT NOT NULL, " +
                        "body TEXT NOT NULL, " +
                        "lastModified INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO notes_new (id, title, body, lastModified) " +
                        "SELECT id, title, body, lastModified FROM notes",
                )
                db.execSQL("DROP TABLE notes")
                db.execSQL("ALTER TABLE notes_new RENAME TO notes")
            }
        }
    }
}
