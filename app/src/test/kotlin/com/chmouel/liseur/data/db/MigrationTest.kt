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

    private companion object {
        const val TEST_DB = "migration-test.db"

        /** Kept in step with the `version` on [LiseurDatabase]. */
        const val LATEST = 17
    }
}
