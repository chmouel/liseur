package com.chmouel.liseur.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [ReadingProgress::class, Book::class, LibraryFolder::class, CalibreServer::class],
    version = 3,
    exportSchema = true,
)
abstract class LiseurDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookDao(): BookDao
    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun calibreServerDao(): CalibreServerDao

    companion object {
        /** Adds the measured reading speed used for time-left estimates. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE reading_progress ADD COLUMN reading_speed REAL",
                )
            }
        }

        /** Adds the calibre-web account and the remote side of a book. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `calibre_server` (
                        `id` INTEGER NOT NULL,
                        `base_url` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `password_cipher` TEXT NOT NULL,
                        `user_id` INTEGER,
                        `kobo_token` TEXT,
                        `can_download` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        `catalog_synced_at` INTEGER,
                        `position_synced_at` INTEGER,
                        `sync_token` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                connection.execSQL("ALTER TABLE books ADD COLUMN local_uri TEXT")
                connection.execSQL("ALTER TABLE books ADD COLUMN remote_uuid TEXT")
                connection.execSQL("ALTER TABLE books ADD COLUMN remote_book_id INTEGER")
                connection.execSQL("ALTER TABLE books ADD COLUMN cover_url TEXT")
                connection.execSQL("ALTER TABLE books ADD COLUMN remote_updated_at INTEGER")
                // Everything already in the library is a file on the device.
                connection.execSQL(
                    "ALTER TABLE books ADD COLUMN download_state TEXT NOT NULL DEFAULT 'DOWNLOADED'",
                )
            }
        }
    }
}
