package com.chmouel.liseur.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.chmouel.liseur.data.calibre.CredentialCipher
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
    version = 11,
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

        /** Records when a book was downloaded, for sorting by it. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN downloaded_at INTEGER")
            }
        }

        /** Notes the file's timestamp, so a swapped file can be spotted. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN file_modified_at INTEGER")
            }
        }

        /** Records when a book was marked read. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN finished_at INTEGER")
            }
        }

        /**
         * Puts the Kobo sync token behind the same Keystore key as the
         * password. It is a bearer credential that never expires, so it
         * had no business sitting in the database in the clear.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                val plain = connection.prepare(
                    "SELECT id, kobo_token FROM calibre_server WHERE kobo_token IS NOT NULL",
                ).use { statement ->
                    buildList {
                        while (statement.step()) {
                            add(statement.getLong(0) to statement.getText(1))
                        }
                    }
                }
                for ((id, token) in plain) {
                    connection.prepare(
                        "UPDATE calibre_server SET kobo_token = ? WHERE id = ?",
                    ).use { statement ->
                        statement.bindText(1, CredentialCipher.encrypt(token))
                        statement.bindLong(2, id)
                        statement.step()
                    }
                }
            }
        }

        /**
         * Gives reading positions what they need to be reconciled with a
         * server without guessing: a revision counter instead of a wall
         * clock, the last state both sides agreed on, and a durable place
         * to land what the server reported before its token moves past it.
         *
         * The backfill treats an already-synced row as agreed at its
         * current position, which is what it is. Rows never synced get no
         * baseline, so the first sync establishes one rather than
         * inventing a disagreement.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                val added = listOf(
                    "local_revision INTEGER NOT NULL DEFAULT 0",
                    "acked_revision INTEGER NOT NULL DEFAULT 0",
                    "agreed_progression REAL",
                    "agreed_status TEXT",
                    "agreed_account TEXT",
                    "pending_progression REAL",
                    "pending_status TEXT",
                    "pending_updated_at INTEGER",
                    "pending_account TEXT",
                    "owner_account TEXT",
                    "remote_updated_at INTEGER",
                )
                for (column in added) {
                    connection.execSQL("ALTER TABLE reading_progress ADD COLUMN $column")
                }
                // Everything already here counts as one local write. A row
                // the server has confirmed is level; one it has not is a
                // revision behind, which is exactly what dirty means.
                connection.execSQL(
                    """
                    UPDATE reading_progress SET
                        local_revision = 1,
                        acked_revision = CASE WHEN synced_at IS NULL THEN 0 ELSE 1 END,
                        agreed_progression =
                            CASE WHEN synced_at IS NULL THEN NULL ELSE total_progression END,
                        agreed_status = CASE WHEN synced_at IS NULL THEN NULL ELSE status END
                    """.trimIndent(),
                )
            }
        }
    }
}
