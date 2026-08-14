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
        RemoteServer::class,
        BookAnnotation::class,
        BookTypography::class,
        ReadingSession::class,
        SyncPeerState::class,
        SyncAccount::class,
        BookFingerprintRow::class,
        WorkAlias::class,
        WorkAmbiguity::class,
        SeriesExtra::class,
    ],
    version = 28,
    exportSchema = true,
)
abstract class LiseurDatabase : RoomDatabase() {
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun syncPeerStateDao(): SyncPeerStateDao
    abstract fun syncAccountDao(): SyncAccountDao

    abstract fun workIdentityDao(): WorkIdentityDao
    abstract fun bookDao(): BookDao
    abstract fun seriesOrderDao(): SeriesOrderDao
    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun remoteServerDao(): RemoteServerDao
    abstract fun annotationDao(): BookAnnotationDao
    abstract fun typographyDao(): BookTypographyDao
    abstract fun seriesExtraDao(): SeriesExtraDao

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

        /**
         * Adds the record of a book having been marked read, or put back
         * on the pile, by hand.
         *
         * Only the unambiguous case is filled in: a book marked read while
         * its position is nowhere near the end can only have been said so
         * outright. A book at the end with no mark is left alone, because
         * that is just as likely to be a book finished on another device
         * as one deliberately put back.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE reading_progress " +
                        "ADD COLUMN finished_override INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    """
                    UPDATE reading_progress SET finished_override = 1
                    WHERE COALESCE(total_progression, 0) < 0.97
                      AND book_url IN (
                          SELECT url FROM books WHERE finished_at IS NOT NULL
                      )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Remembers which work each file actually contains, so that a
         * file replaced at the same path can be told apart from the same
         * book downloaded again.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN work_id TEXT")
            }
        }

        /** Lets a book be set apart from the shared reading settings. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS book_typography (
                        book_url TEXT NOT NULL PRIMARY KEY,
                        font TEXT NOT NULL,
                        font_size REAL NOT NULL,
                        line_height REAL,
                        page_margins REAL
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Lets a book be archived without deleting anything. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE books ADD COLUMN archived_at INTEGER")
            }
        }

        /**
         * Makes room for a second kind of server.
         *
         * The account table stops being about calibre-web in particular:
         * it gains the kind of server it describes, somewhere to keep an
         * API key, and a place for an account id that is not a number.
         * The login and password become optional, because Komga does not
         * use either.
         *
         * Everything already stored is a calibre-web account, and is
         * carried over saying so, down to the user id being copied into
         * the new text column as well as staying where it was. That
         * matters more than it looks: `accountKey` is built from the
         * user id and is already written into `owner_account` on every
         * row that has ever synced, so a value that came out differently
         * here would make the reader's own reading look like somebody
         * else's.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `remote_server` (
                        `id` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `base_url` TEXT NOT NULL,
                        `username` TEXT,
                        `password_cipher` TEXT,
                        `api_key_cipher` TEXT,
                        `account_id` TEXT,
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
                connection.execSQL(
                    """
                    INSERT INTO remote_server (
                        id, kind, base_url, username, password_cipher, api_key_cipher,
                        account_id, user_id, kobo_token, can_download, added_at,
                        catalog_synced_at, position_synced_at, sync_token
                    )
                    SELECT id, 'CALIBRE', base_url, username, password_cipher, NULL,
                           CAST(user_id AS TEXT), user_id, kobo_token, can_download, added_at,
                           catalog_synced_at, position_synced_at, sync_token
                    FROM calibre_server
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE calibre_server")
                // What Komga counts as the length of a book. Needed to
                // put a position back when a locator will not go.
                connection.execSQL("ALTER TABLE books ADD COLUMN remote_page_count INTEGER")
            }
        }

        /**
         * Adds the record of time actually spent reading.
         *
         * Nothing is backfilled. Positions say where a reader got to,
         * never how long it took them, and inventing durations from the
         * one timestamp there is would put a number on the screen that
         * nobody's reading ever produced.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reading_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `book_url` TEXT NOT NULL,
                        `started_at` INTEGER NOT NULL,
                        `ended_at` INTEGER,
                        `last_checkpoint_at` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_book_url` " +
                        "ON `reading_sessions` (`book_url`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_started_at` " +
                        "ON `reading_sessions` (`started_at`)",
                )
            }
        }

        /**
         * Gives every sync partner its own copy of what it has agreed
         * with this device.
         *
         * One server meant one baseline, and `reading_progress` held it.
         * A dedicated sync server can now run alongside the catalog
         * server, and a baseline is a fact about a pair: sharing one
         * between two partners would have each quietly overwrite the
         * other's idea of what was settled.
         *
         * What is already agreed is carried across rather than
         * rediscovered — starting the connected account from nothing
         * would make every book look as though both sides had moved, and
         * ask about a conflict that does not exist. The old columns stay
         * where they are for now: they are still what the calibre-web
         * and Komga paths read, and copying rather than moving means a
         * release can be rolled back without taking anybody's place in a
         * book with it.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_peer_state` (
                        `book_url` TEXT NOT NULL,
                        `peer_id` TEXT NOT NULL,
                        `acked_revision` INTEGER NOT NULL DEFAULT 0,
                        `agreed_progression` REAL,
                        `agreed_status` TEXT,
                        `pending_progression` REAL,
                        `pending_status` TEXT,
                        `pending_updated_at` INTEGER,
                        `has_pending` INTEGER NOT NULL DEFAULT 0,
                        `remote_updated_at` INTEGER,
                        `synced_at` INTEGER,
                        PRIMARY KEY(`book_url`, `peer_id`)
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_peer_state_peer_id` " +
                        "ON `sync_peer_state` (`peer_id`)",
                )
                // A book only has a partner if some account is named on
                // it. The pending state is attributed to the account that
                // reported it, which may not be the one the baseline was
                // agreed with, so the two are carried across separately.
                connection.execSQL(
                    """
                    INSERT INTO sync_peer_state (
                        book_url, peer_id, acked_revision,
                        agreed_progression, agreed_status,
                        pending_progression, pending_status, pending_updated_at,
                        has_pending, remote_updated_at, synced_at
                    )
                    SELECT book_url,
                           COALESCE(owner_account, agreed_account),
                           acked_revision,
                           agreed_progression,
                           agreed_status,
                           CASE WHEN pending_account IS NOT NULL
                                     AND pending_account = COALESCE(owner_account, agreed_account)
                                THEN pending_progression END,
                           CASE WHEN pending_account IS NOT NULL
                                     AND pending_account = COALESCE(owner_account, agreed_account)
                                THEN pending_status END,
                           CASE WHEN pending_account IS NOT NULL
                                     AND pending_account = COALESCE(owner_account, agreed_account)
                                THEN pending_updated_at END,
                           CASE WHEN pending_account IS NOT NULL
                                     AND pending_account = COALESCE(owner_account, agreed_account)
                                THEN 1 ELSE 0 END,
                           remote_updated_at,
                           synced_at
                    FROM reading_progress
                    WHERE COALESCE(owner_account, agreed_account) IS NOT NULL
                    """.trimIndent(),
                )
                // A partner that reported something about a book this
                // device has never agreed with anyone about is still a
                // partner, and its report is the whole reason the row
                // needs to exist.
                connection.execSQL(
                    """
                    INSERT OR IGNORE INTO sync_peer_state (
                        book_url, peer_id, acked_revision,
                        agreed_progression, agreed_status,
                        pending_progression, pending_status, pending_updated_at,
                        has_pending, remote_updated_at, synced_at
                    )
                    SELECT book_url, pending_account, acked_revision,
                           NULL, NULL,
                           pending_progression, pending_status, pending_updated_at,
                           1, remote_updated_at, synced_at
                    FROM reading_progress
                    WHERE pending_account IS NOT NULL
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the dedicated sync server.
         *
         * Its own table rather than another `remote_server` row: it holds
         * no books, it is connected to as well as a catalog server rather
         * than instead of one, and it is the only partner a book that
         * never came from a server can have.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_account` (
                        `id` INTEGER NOT NULL,
                        `base_url` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `token_cipher` TEXT NOT NULL,
                        `insights_token_cipher` TEXT,
                        `device_name` TEXT NOT NULL,
                        `cursor_seq` INTEGER NOT NULL DEFAULT 0,
                        `added_at` INTEGER NOT NULL,
                        `synced_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_fingerprint` (
                        `book_url` TEXT NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `partial_md5` TEXT NOT NULL,
                        `file_size` INTEGER NOT NULL,
                        `file_modified_at` INTEGER,
                        `computed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_url`)
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `work_alias` (
                        `book_url` TEXT NOT NULL,
                        `peer_id` TEXT NOT NULL,
                        `work_id` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `confirmed` INTEGER NOT NULL DEFAULT 0,
                        `edition_sha` TEXT,
                        `resolved_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_url`, `peer_id`)
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `work_ambiguity` (
                        `book_url` TEXT NOT NULL,
                        `peer_id` TEXT NOT NULL,
                        `work_ids` TEXT NOT NULL,
                        `noticed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_url`, `peer_id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `sync_account` ADD COLUMN `device_id` TEXT")
                connection.execSQL(
                    "ALTER TABLE `sync_account` ADD COLUMN `device_key` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `reading_sessions` ADD COLUMN `start_progression` REAL",
                )
                connection.execSQL(
                    "ALTER TABLE `reading_sessions` ADD COLUMN `end_progression` REAL",
                )
                connection.execSQL(
                    "ALTER TABLE `reading_sessions` ADD COLUMN `uploaded_at` INTEGER",
                )
            }
        }

        /**
         * Remembers which books have been asked where they stand.
         *
         * Deliberately starts everybody at no: the one-off question was
         * only ever asked for books named by a fresh resolve, so a book
         * whose doubtful match was confirmed by hand never got it, and
         * whatever the other device read beforehand sits behind the
         * cursor. Asking once more per book is cheap and budgeted, and
         * it is what heals those books.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `work_alias` ADD COLUMN `seeded` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `work_alias` ADD COLUMN `source_sent` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** Adds resumable v2 reading-pace evidence without trusting v1 speed. */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `reading_progress` " +
                        "ADD COLUMN `reading_seconds_per_position` REAL",
                )
                connection.execSQL(
                    "ALTER TABLE `reading_progress` " +
                        "ADD COLUMN `reading_pace_samples` INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE `reading_progress` " +
                        "ADD COLUMN `reading_pace_elapsed_ms` INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE `reading_progress` " +
                        "ADD COLUMN `reading_pace_evidence` REAL NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds where a book sits in its series.
         *
         * `series_checked` starts at 0 for every existing row, including
         * ones that will turn out to have no series at all: it records
         * that the file has been looked at, not that a series was found,
         * which is what stops a standalone book being reopened on every
         * launch for ever.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `books` ADD COLUMN `series_name` TEXT")
                connection.execSQL("ALTER TABLE `books` ADD COLUMN `series_index` REAL")
                connection.execSQL("ALTER TABLE `books` ADD COLUMN `file_series_name` TEXT")
                connection.execSQL("ALTER TABLE `books` ADD COLUMN `file_series_index` REAL")
                connection.execSQL("ALTER TABLE `books` ADD COLUMN `series_id` TEXT")
                connection.execSQL(
                    "ALTER TABLE `books` ADD COLUMN `series_checked` INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_books_series_name` " +
                        "ON `books` (`series_name`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_extra` (" +
                        "`series_id` TEXT NOT NULL, " +
                        "`summary` TEXT, " +
                        "`status` TEXT, " +
                        "`total_book_count` INTEGER, " +
                        "`fetched_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`series_id`))",
                )
            }
        }

        /**
         * Adds the reader's own filing of a book into a series.
         *
         * `catalog_series_name` arrives empty and stays empty until the
         * next catalog walk fills it in. Nothing reads it before then
         * except the undo, which falls back to what the file said —
         * which is what an un-refreshed library has anyway.
         *
         * Every column is added only if it is not already there, and
         * that includes two that version 26 was supposed to have. 26 was
         * never released, and it was not one shape: devices carrying a
         * build from the middle of the series work have a `books` table
         * without `file_series_name`. Room only checks the columns when
         * the version moves, so those devices ran happily until this
         * migration and would then have failed the check on the way out
         * of it, for a column this migration never touched. Repairing
         * what we find is the only way past that, and it costs one
         * PRAGMA on an upgrade that happens once.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(connection: SQLiteConnection) {
                val existing = connection.columnsOf("books")
                fun add(name: String, type: String) {
                    if (name !in existing) {
                        connection.execSQL("ALTER TABLE `books` ADD COLUMN `$name` $type")
                    }
                }
                add("file_series_name", "TEXT")
                add("file_series_index", "REAL")
                add("catalog_series_name", "TEXT")
                add("catalog_series_index", "REAL")
                add("user_series_name", "TEXT")
                add("user_series_index", "REAL")
                add("series_override", "INTEGER NOT NULL DEFAULT 0")
                if ("file_series_name" !in existing) {
                    // A book with no server behind it was only ever
                    // described by its own file, so what it says now is
                    // what the file said. Recovering it matters because
                    // it is what a book falls back to when the reader
                    // undoes their own filing.
                    connection.execSQL(
                        "UPDATE `books` SET `file_series_name` = `series_name`, " +
                            "`file_series_index` = `series_index` " +
                            "WHERE `remote_uuid` IS NULL",
                    )
                }
            }
        }

        /**
         * Splits the series override in two, so a book can be moved
         * within a series without freezing the series' name.
         *
         * The old `series_override` is kept and narrowed to mean the
         * name; only `series_index_override` is added. Rewriting it into
         * two new columns would mean dropping the old one, and
         * `ALTER TABLE … DROP COLUMN` wants SQLite 3.35 — this app runs
         * from API 26, where most devices cannot, and the alternative is
         * rebuilding `books` with its indices and its data.
         *
         * Version 27 is unreleased and 26→27 was itself a repair, so the
         * column guard runs again over everything canonical v27 owes
         * before anything else happens.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(connection: SQLiteConnection) {
                val existing = connection.columnsOf("books")
                fun add(name: String, type: String) {
                    if (name !in existing) {
                        connection.execSQL("ALTER TABLE `books` ADD COLUMN `$name` $type")
                    }
                }
                add("file_series_name", "TEXT")
                add("file_series_index", "REAL")
                add("catalog_series_name", "TEXT")
                add("catalog_series_index", "REAL")
                add("user_series_name", "TEXT")
                add("user_series_index", "REAL")
                add("series_override", "INTEGER NOT NULL DEFAULT 0")
                add("series_index_override", "INTEGER NOT NULL DEFAULT 0")

                if ("file_series_name" !in existing) {
                    // As in 26→27: a book with no server behind it was
                    // only ever described by its own file. Skipped on a
                    // row filed by hand, where `series_name` holds the
                    // reader's answer and copying it here would forge a
                    // provenance the file never gave — after which the
                    // refresh would find its own value already in place
                    // and agree with itself forever.
                    connection.execSQL(
                        "UPDATE `books` SET `file_series_name` = `series_name`, " +
                            "`file_series_index` = `series_index` " +
                            "WHERE `remote_uuid` IS NULL AND `series_override` = 0",
                    )
                }

                // `catalog_series_*` is deliberately not recovered.
                // `remote_uuid` says the book came from a server, not
                // that the server is where its series came from: the
                // merge falls back field by field, so a linked book can
                // carry a name the catalog gave beside an index the file
                // gave, or a series the catalog never mentioned. A wrong
                // value here is worse than an empty one, because the
                // merge will trust it. The next refresh knows.

                if ("series_index_override" !in existing) {
                    // Filing a book by hand has always set its number
                    // too, so every existing override is an index
                    // override as well. Only on the way in: a device
                    // that already carries the column has been through
                    // an earlier build of this migration and may hold
                    // index-only overrides this would flatten.
                    connection.execSQL(
                        "UPDATE `books` SET `series_index_override` = `series_override`",
                    )
                }
            }
        }

        /**
         * Every migration, in order, as one list so that what the app
         * runs and what the tests replay cannot drift apart.
         */
        val MIGRATIONS: Array<Migration> get() = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
        )
    }
}

/** Which columns a table actually has, whatever the version number claims. */
private fun SQLiteConnection.columnsOf(table: String): Set<String> {
    val names = mutableSetOf<String>()
    val statement = prepare("PRAGMA table_info(`$table`)")
    try {
        while (statement.step()) names += statement.getText(1)
    } finally {
        statement.close()
    }
    return names
}
