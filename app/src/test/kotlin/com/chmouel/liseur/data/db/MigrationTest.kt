package com.chmouel.liseur.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Replays every released schema forward through the migrations.
 *
 * Twelve schemas are exported and, until now, none of them were ever
 * opened again. A migration that drops a column takes the reading
 * positions in it with it, on an upgrade, on someone else's phone —
 * which is precisely where it cannot be found out about afterwards.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LiseurDatabase::class.java,
    )

    @Test
    fun `every released version migrates all the way up`() {
        for (from in 1 until LATEST) {
            helper.createDatabase(TEST_DB, from).close()
            helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
                .close()
        }
    }

    @Test
    fun `reading survives every upgrade from the version that first stored it`() {
        // Version 1 already had reading_progress. A position written then
        // has to still be there now, or an upgrade silently sent someone
        // back to the first page of everything.
        helper.createDatabase(TEST_DB, 1).use { old ->
            old.execSQL(
                """
                INSERT INTO reading_progress (book_url, locator_json, total_progression, updated_at)
                VALUES ('calibre:uuid-1', '{"at":0.42}', 0.42, 1000)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT total_progression, locator_json FROM reading_progress " +
                        "WHERE book_url = 'calibre:uuid-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0.42, cursor.getDouble(0), 1e-9)
                    assertEquals("""{"at":0.42}""", cursor.getString(1))
                }
            }
    }

    @Test
    fun `pace upgrade leaves reading and sync state untouched`() {
            helper.createDatabase(TEST_DB, 24).use { old ->
                old.execSQL(
                    """
                    INSERT INTO reading_progress (
                        book_url, locator_json, total_progression, reading_speed, updated_at,
                        status, local_revision, acked_revision,
                        agreed_progression, agreed_status,
                        pending_progression, pending_status, pending_account
                    ) VALUES (
                        'calibre:uuid-1', '{"exact":"place"}', 0.42, 9.0, 1000,
                        'Reading', 7, 5,
                        0.40, 'Reading',
                        0.60, 'Reading', 'peer'
                    )
                    """.trimIndent(),
                )
            }

            helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
                .use { db ->
                    db.query(
                        """
                        SELECT locator_json, total_progression, reading_speed,
                               local_revision, acked_revision,
                               agreed_progression, pending_progression, pending_account,
                               reading_seconds_per_position, reading_pace_samples,
                               reading_pace_elapsed_ms, reading_pace_evidence
                        FROM reading_progress WHERE book_url = 'calibre:uuid-1'
                        """.trimIndent(),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("""{"exact":"place"}""", cursor.getString(0))
                        assertEquals(0.42, cursor.getDouble(1), 1e-9)
                        assertEquals(9.0, cursor.getDouble(2), 1e-9)
                        assertEquals(7, cursor.getInt(3))
                        assertEquals(5, cursor.getInt(4))
                        assertEquals(0.40, cursor.getDouble(5), 1e-9)
                        assertEquals(0.60, cursor.getDouble(6), 1e-9)
                        assertEquals("peer", cursor.getString(7))
                        assertTrue(cursor.isNull(8))
                        assertEquals(0, cursor.getInt(9))
                        assertEquals(0, cursor.getLong(10))
                        assertEquals(0.0, cursor.getDouble(11), 0.0)
                    }
                }
    }

    @Test
    fun `a book survives every upgrade`() {
        helper.createDatabase(TEST_DB, 1).use { old ->
            old.execSQL(
                """
                INSERT INTO books (url, title, author, cover_path, source, added_at, last_opened_at)
                VALUES ('file:///b', 'Morning Star', 'Pierce Brown', NULL, NULL, 1, 2)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query("SELECT title, author FROM books WHERE url = 'file:///b'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Morning Star", cursor.getString(0))
                    assertEquals("Pierce Brown", cursor.getString(1))
                }
            }
    }

    @Test
    fun `a book keeps its series across the upgrade that adds one`() {
        // Series arrives at 26 filled in from the sources, so an
        // existing book must come through with room for it and nothing
        // of its own lost.
        helper.createDatabase(TEST_DB, 25).use { old ->
            old.execSQL(
                """
                INSERT INTO books
                  (url, title, author, cover_path, source, added_at, last_opened_at, download_state)
                VALUES
                  ('file:///eye', 'The Eye of the World', 'Robert Jordan', NULL, NULL, 1, 2, 'DOWNLOADED')
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT title, series_name, series_index, file_series_name, " +
                        "file_series_index, series_id, series_checked " +
                        "FROM books WHERE url = 'file:///eye'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("The Eye of the World", cursor.getString(0))
                    assertTrue("series is unknown, not empty", cursor.isNull(1))
                    assertTrue(cursor.isNull(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.isNull(4))
                    assertTrue(cursor.isNull(5))
                    // Nothing has been looked at yet, so the backfill
                    // still has this book to visit.
                    assertEquals(0, cursor.getInt(6))
                }
            }
    }

    @Test
    fun `a connected calibre account survives becoming a generic server`() {
        // The account row is renamed and widened at 16. Anyone upgrading
        // is by definition on calibre-web, and has to stay connected —
        // and keep the same account key, since that key is already
        // written into every row they have ever synced.
        helper.createDatabase(TEST_DB, 15).use { old ->
            old.execSQL(
                """
                INSERT INTO calibre_server (
                    id, base_url, username, password_cipher, user_id, kobo_token,
                    can_download, added_at, catalog_synced_at, position_synced_at, sync_token
                ) VALUES (1, 'https://books.example', 'ada', 'cipher', 7, 'tok', 1, 100, 200, 300, 'sync')
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT kind, base_url, username, password_cipher, api_key_cipher, " +
                        "account_id, user_id, kobo_token, can_download, sync_token FROM remote_server",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("CALIBRE", cursor.getString(0))
                    assertEquals("https://books.example", cursor.getString(1))
                    assertEquals("ada", cursor.getString(2))
                    assertEquals("cipher", cursor.getString(3))
                    assertTrue(cursor.isNull(4))
                    assertEquals("7", cursor.getString(5))
                    assertEquals(7, cursor.getInt(6))
                    assertEquals("tok", cursor.getString(7))
                    assertEquals(1, cursor.getInt(8))
                    assertEquals("sync", cursor.getString(9))
                }
            }
    }

    @Test
    fun `what the connected account agreed becomes that account's own baseline`() {
        // Splitting the baseline out per partner must carry the existing
        // one across. Starting the connected account from nothing would
        // make every book look as though both sides had moved and ask
        // about a conflict that never happened.
        helper.createDatabase(TEST_DB, 17).use { old ->
            old.execSQL(
                """
                INSERT INTO reading_progress (
                    book_url, locator_json, total_progression, updated_at,
                    local_revision, acked_revision,
                    agreed_progression, agreed_status, agreed_account,
                    pending_progression, pending_status, pending_updated_at, pending_account,
                    owner_account, remote_updated_at, synced_at
                ) VALUES (
                    'calibre:uuid-1', '{}', 0.5, 1000,
                    4, 3,
                    0.4, 'Reading', 'https://books.example|ada|7',
                    0.6, 'Reading', 900, 'https://books.example|ada|7',
                    'https://books.example|ada|7', 800, 700
                )
                """.trimIndent(),
            )
            // Nobody has ever agreed anything about this one; a partner
            // has simply said something that is still unsettled.
            old.execSQL(
                """
                INSERT INTO reading_progress (
                    book_url, locator_json, total_progression, updated_at,
                    local_revision, acked_revision,
                    pending_progression, pending_status, pending_updated_at, pending_account
                ) VALUES (
                    'komga:book-2', '{}', NULL, 1000,
                    0, 0,
                    0.2, 'Reading', 950, 'https://komga.example|ada|u1'
                )
                """.trimIndent(),
            )
            // Read only here, agreed with nobody: no partner, no row.
            old.execSQL(
                """
                INSERT INTO reading_progress (book_url, locator_json, total_progression, updated_at)
                VALUES ('file:///local', '{}', 0.3, 1000)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT peer_id, acked_revision, agreed_progression, agreed_status, " +
                        "pending_progression, has_pending, remote_updated_at " +
                        "FROM sync_peer_state WHERE book_url = 'calibre:uuid-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("https://books.example|ada|7", cursor.getString(0))
                    assertEquals(3, cursor.getInt(1))
                    assertEquals(0.4, cursor.getDouble(2), 1e-9)
                    assertEquals("Reading", cursor.getString(3))
                    assertEquals(0.6, cursor.getDouble(4), 1e-9)
                    assertEquals(1, cursor.getInt(5))
                    assertEquals(800, cursor.getLong(6))
                }

                db.query(
                    "SELECT peer_id, agreed_progression, pending_progression, has_pending " +
                        "FROM sync_peer_state WHERE book_url = 'komga:book-2'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("https://komga.example|ada|u1", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertEquals(0.2, cursor.getDouble(2), 1e-9)
                    assertEquals(1, cursor.getInt(3))
                }

                db.query(
                    "SELECT COUNT(*) FROM sync_peer_state WHERE book_url = 'file:///local'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }

                // The old columns are still what the calibre-web and
                // Komga paths read, so copying must not have moved them.
                db.query(
                    "SELECT agreed_progression FROM reading_progress " +
                        "WHERE book_url = 'calibre:uuid-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0.4, cursor.getDouble(0), 1e-9)
                }
            }
    }

    @Test
    fun `a half-built version 26 is repaired on the way to 27`() {
        // Version 26 was never released and was not one shape: a build
        // from the middle of the series work left a books table without
        // the file_series columns. Room only looks at the columns when
        // the version moves, so the first upgrade after that is where it
        // would be found out.
        helper.createDatabase(TEST_DB, 26).use { old ->
            old.execSQL("ALTER TABLE books DROP COLUMN file_series_name")
            old.execSQL("ALTER TABLE books DROP COLUMN file_series_index")
            old.execSQL(
                """
                INSERT INTO books (
                    url, title, author, cover_path, source, added_at, last_opened_at,
                    download_state, series_checked, series_name, series_index
                ) VALUES (
                    'file:///books/a.epub', 'A Book', 'An Author', NULL, NULL, 1, NULL,
                    'DOWNLOADED', 1, 'The Expanse', 3.0
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT file_series_name, file_series_index, series_override FROM books " +
                        "WHERE url = 'file:///books/a.epub'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    // A local book was only ever described by its file,
                    // so what it says is what the file said.
                    assertEquals("The Expanse", cursor.getString(0))
                    assertEquals(3.0, cursor.getDouble(1), 1e-9)
                    assertEquals(0, cursor.getInt(2))
                }
            }
    }

    @Test
    fun `filing a book by hand is carried into the split flag`() {
        // Filing a book by hand has always set its number as well, so
        // every override that exists is an index override too.
        helper.createDatabase(TEST_DB, 27).use { old ->
            old.execSQL(
                """
                INSERT INTO books (
                    url, title, author, cover_path, source, added_at, last_opened_at,
                    download_state, series_checked, series_name, series_index,
                    user_series_name, user_series_index, series_override
                ) VALUES
                ('file:///books/filed.epub', 'Filed', NULL, NULL, NULL, 1, NULL,
                 'DOWNLOADED', 1, 'My Shelf', 2.0, 'My Shelf', 2.0, 1),
                ('file:///books/unnumbered.epub', 'Unnumbered', NULL, NULL, NULL, 1, NULL,
                 'DOWNLOADED', 1, 'My Shelf', NULL, 'My Shelf', NULL, 1),
                ('file:///books/nowhere.epub', 'Nowhere', NULL, NULL, NULL, 1, NULL,
                 'DOWNLOADED', 1, NULL, NULL, NULL, NULL, 1),
                ('file:///books/plain.epub', 'Plain', NULL, NULL, NULL, 1, NULL,
                 'DOWNLOADED', 1, 'The Expanse', 3.0, NULL, NULL, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT url, series_override, series_index_override FROM books " +
                        "ORDER BY url",
                ).use { cursor ->
                    val flags = buildMap {
                        while (cursor.moveToNext()) {
                            put(cursor.getString(0), cursor.getInt(1) to cursor.getInt(2))
                        }
                    }
                    assertEquals(1 to 1, flags["file:///books/filed.epub"])
                    assertEquals(1 to 1, flags["file:///books/unnumbered.epub"])
                    // "In no series" is still an answer, and it is still
                    // an answer about the number.
                    assertEquals(1 to 1, flags["file:///books/nowhere.epub"])
                    assertEquals(0 to 0, flags["file:///books/plain.epub"])
                }
            }
    }

    @Test
    fun `a version 27 missing its override columns is repaired on the way to 28`() {
        // 26 to 27 was itself a repair, and 27 is unreleased too, so a
        // dev device can be sitting on a books table that never grew the
        // override columns at all.
        helper.createDatabase(TEST_DB, 27).use { old ->
            old.execSQL(
                """
                INSERT INTO books (
                    url, title, author, cover_path, source, added_at, last_opened_at,
                    download_state, series_checked, series_name, series_index,
                    series_override
                ) VALUES (
                    'file:///books/a.epub', 'A Book', NULL, NULL, NULL, 1, NULL,
                    'DOWNLOADED', 1, 'The Expanse', 3.0, 0
                )
                """.trimIndent(),
            )
            old.execSQL("ALTER TABLE books DROP COLUMN user_series_name")
            old.execSQL("ALTER TABLE books DROP COLUMN user_series_index")
            old.execSQL("ALTER TABLE books DROP COLUMN series_override")
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT user_series_name, series_override, series_index_override " +
                        "FROM books WHERE url = 'file:///books/a.epub'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    // Nothing can be recovered: a database that never
                    // had the columns cannot say which books had been
                    // filed by hand, so it says none had, and they fall
                    // back to the catalog and the file as before.
                    assertTrue(cursor.isNull(0))
                    assertEquals(0, cursor.getInt(1))
                    assertEquals(0, cursor.getInt(2))
                }
            }
    }

    @Test
    fun `a version 27 missing its file series columns recovers only local books`() {
        helper.createDatabase(TEST_DB, 27).use { old ->
            old.execSQL(
                """
                INSERT INTO books (
                    url, title, author, cover_path, source, added_at, last_opened_at,
                    download_state, series_checked, series_name, series_index,
                    remote_uuid, user_series_name, user_series_index, series_override
                ) VALUES
                ('file:///books/local.epub', 'Local', NULL, NULL, NULL, 1, NULL,
                 'DOWNLOADED', 1, 'The Expanse', 3.0, NULL, NULL, NULL, 0),
                ('file:///books/filed.epub', 'Filed', NULL, NULL, NULL, 1, NULL,
                 'DOWNLOADED', 1, 'My Shelf', 1.0, NULL, 'My Shelf', 1.0, 1)
                """.trimIndent(),
            )
            old.execSQL("ALTER TABLE books DROP COLUMN file_series_name")
            old.execSQL("ALTER TABLE books DROP COLUMN file_series_index")
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT url, file_series_name FROM books ORDER BY url",
                ).use { cursor ->
                    val names = buildMap {
                        while (cursor.moveToNext()) {
                            put(cursor.getString(0), cursor.getString(1))
                        }
                    }
                    assertEquals("The Expanse", names["file:///books/local.epub"])
                    // On a book filed by hand, series_name holds the
                    // reader's answer. Copying it into the file's column
                    // would forge a provenance the file never gave, and
                    // the next refresh would find it and agree.
                    assertEquals(null, names["file:///books/filed.epub"])
                }
            }
    }

    @Test
    fun `an index override already set is not flattened by the backfill`() {
        // A dev device that has been through an earlier build of this
        // migration may hold a book that was dragged but never refiled:
        // an index override with no name override. Re-running the
        // backfill over it would clear it.
        helper.createDatabase(TEST_DB, 27).use { old ->
            old.execSQL("ALTER TABLE books ADD COLUMN series_index_override INTEGER NOT NULL DEFAULT 0")
            old.execSQL(
                """
                INSERT INTO books (
                    url, title, author, cover_path, source, added_at, last_opened_at,
                    download_state, series_checked, series_name, series_index,
                    user_series_index, series_override, series_index_override
                ) VALUES (
                    'file:///books/dragged.epub', 'Dragged', NULL, NULL, NULL, 1, NULL,
                    'DOWNLOADED', 1, 'The Expanse', 2.0, 2.0, 0, 1
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT series_override, series_index_override FROM books " +
                        "WHERE url = 'file:///books/dragged.epub'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                    assertEquals(1, cursor.getInt(1))
                }
            }
    }

    @Test
    fun `a sync-only account becomes the connected liseur-sync server`() {
        // Version 28 kept liseur-sync in its own `sync_account` row.
        // Promoting it must carry the cursor — the only irreplaceable
        // state — and rekey everything the peer agreed to the account
        // key the row will have as a server.
        helper.createDatabase(TEST_DB, 28).use { old ->
            old.execSQL(
                """
                INSERT INTO sync_account (
                    id, base_url, username, token_cipher, insights_token_cipher,
                    device_name, device_id, device_key, cursor_seq, added_at, synced_at
                ) VALUES (
                    1, 'https://sync.example', 'ada', 'cipher', 'insights-cipher',
                    'Phone', 'dev-1', 'device-key', 41, 100, 900
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                INSERT INTO sync_peer_state (
                    book_url, peer_id, acked_revision, agreed_progression, agreed_status,
                    pending_progression, pending_status, pending_updated_at, has_pending,
                    remote_updated_at, synced_at
                ) VALUES (
                    'content://sd/book.epub', 'liseursync|https://sync.example|ada',
                    3, 0.4, 'reading', NULL, NULL, NULL, 0, NULL, 800
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                INSERT INTO work_alias (
                    book_url, peer_id, work_id, confidence, confirmed, seeded,
                    source_sent, edition_sha, resolved_at
                ) VALUES (
                    'content://sd/book.epub', 'liseursync|https://sync.example|ada',
                    'w-1', 'high', 1, 1, 0, NULL, 700
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query(
                    "SELECT kind, base_url, username, account_id, liseur_token_cipher, " +
                        "sync_cursor_seq, position_synced_at, can_download FROM remote_server",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("LISEUR_SYNC", cursor.getString(0))
                    assertEquals("https://sync.example", cursor.getString(1))
                    assertEquals("ada", cursor.getString(2))
                    assertEquals("dev-1", cursor.getString(3))
                    assertEquals("cipher", cursor.getString(4))
                    assertEquals(41, cursor.getLong(5))
                    assertEquals(900, cursor.getLong(6))
                    // Unknown until the token is asked what it may do.
                    assertEquals(0, cursor.getInt(7))
                }
                // The peer's agreements moved to the device-id spelling,
                // which is what the account key now resolves to.
                db.query("SELECT peer_id, acked_revision FROM sync_peer_state").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("liseursync|https://sync.example|dev-1", cursor.getString(0))
                    assertEquals(3, cursor.getLong(1))
                }
                db.query("SELECT peer_id, work_id FROM work_alias").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("liseursync|https://sync.example|dev-1", cursor.getString(0))
                    assertEquals("w-1", cursor.getString(1))
                }
            }
    }

    @Test
    fun `a sync account without a device id keeps its login-spelled peer key`() {
        // A token pasted in by hand never said which device it was; the
        // account key falls back to the login, so the agreements stay
        // under the spelling they already had.
        helper.createDatabase(TEST_DB, 28).use { old ->
            old.execSQL(
                """
                INSERT INTO sync_account (
                    id, base_url, username, token_cipher, insights_token_cipher,
                    device_name, device_id, device_key, cursor_seq, added_at, synced_at
                ) VALUES (
                    1, 'https://sync.example', 'ada', 'cipher', NULL,
                    'Phone', NULL, 'device-key', 7, 100, NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                INSERT INTO sync_peer_state (
                    book_url, peer_id, acked_revision, agreed_progression, agreed_status,
                    pending_progression, pending_status, pending_updated_at, has_pending,
                    remote_updated_at, synced_at
                ) VALUES (
                    'content://sd/book.epub', 'liseursync|https://sync.example|ada',
                    2, 0.5, 'reading', NULL, NULL, NULL, 0, NULL, NULL
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query("SELECT kind, sync_cursor_seq FROM remote_server").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("LISEUR_SYNC", cursor.getString(0))
                    assertEquals(7, cursor.getLong(1))
                }
                db.query("SELECT peer_id FROM sync_peer_state").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("liseursync|https://sync.example|ada", cursor.getString(0))
                }
            }
    }

    @Test
    fun `a connected catalog server is never traded for the sync account`() {
        // Both accounts existed. The connected one stays connected —
        // silently switching someone's library is not an upgrade — and
        // the sync account's agreements go with it, as they do whenever
        // a peer is removed.
        helper.createDatabase(TEST_DB, 28).use { old ->
            old.execSQL(
                """
                INSERT INTO remote_server (
                    id, kind, base_url, username, password_cipher, api_key_cipher,
                    account_id, user_id, kobo_token, can_download, added_at,
                    catalog_synced_at, position_synced_at, sync_token
                ) VALUES (
                    1, 'KOMGA', 'https://books.example', 'ada', NULL, 'key-cipher',
                    'u-1', NULL, NULL, 1, 50, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                INSERT INTO sync_account (
                    id, base_url, username, token_cipher, insights_token_cipher,
                    device_name, device_id, device_key, cursor_seq, added_at, synced_at
                ) VALUES (
                    1, 'https://sync.example', 'ada', 'cipher', NULL,
                    'Phone', 'dev-1', 'device-key', 41, 100, NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                INSERT INTO sync_peer_state (
                    book_url, peer_id, acked_revision, agreed_progression, agreed_status,
                    pending_progression, pending_status, pending_updated_at, has_pending,
                    remote_updated_at, synced_at
                ) VALUES (
                    'content://sd/book.epub', 'liseursync|https://sync.example|ada',
                    3, 0.4, 'reading', NULL, NULL, NULL, 0, NULL, NULL
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *LiseurDatabase.MIGRATIONS)
            .use { db ->
                db.query("SELECT kind, base_url FROM remote_server").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("KOMGA", cursor.getString(0))
                }
                db.query("SELECT COUNT(*) FROM sync_peer_state").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='sync_account'",
                ).use { cursor ->
                    assertTrue(!cursor.moveToFirst())
                }
            }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        /** Kept in step with the `version` on [LiseurDatabase]. */
        const val LATEST = 35
    }
}
