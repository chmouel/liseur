package com.chmouel.liseur.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        ReadingProgress::class,
        Book::class,
        LibraryFolder::class,
        CalibreServer::class,
        BookAnnotation::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class LiseurDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookDao(): BookDao
    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun calibreServerDao(): CalibreServerDao
    abstract fun annotationDao(): BookAnnotationDao

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

        /** Remembers which catalog link a book is downloaded from. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN download_href TEXT")
            }
        }

        /** Remembers how a reading position stands with the server. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE reading_progress ADD COLUMN status TEXT")
                connection.execSQL("ALTER TABLE reading_progress ADD COLUMN synced_at INTEGER")
            }
        }

        /** Adds highlights, notes and bookmarks. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN downloaded_at INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN file_modified_at INTEGER")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN finished_at INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `annotations` (
                        `id` TEXT NOT NULL,
                        `book_id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `locator_json` TEXT NOT NULL,
                        `text` TEXT,
                        `note` TEXT,
                        `tint` TEXT,
                        `chapter` TEXT,
                        `position` INTEGER,
                        `total_progression` REAL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotations_book_id` " +
                        "ON `annotations` (`book_id`)",
                )
            }
        }
    }
}
