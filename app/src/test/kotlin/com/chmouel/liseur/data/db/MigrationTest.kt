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

    private companion object {
        const val TEST_DB = "migration-test.db"

        /** Kept in step with the `version` on [LiseurDatabase]. */
        const val LATEST = 22
    }
}
