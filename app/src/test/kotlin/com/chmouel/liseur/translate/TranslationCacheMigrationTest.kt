package com.chmouel.liseur.translate

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class TranslationCacheMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TranslationCacheDatabase::class.java,
    )

    @Test
    fun `saved sentences survive the upgrade that adds translated books`() {
        helper.createDatabase(TEST_DB, 1).use { old ->
            old.execSQL(
                "INSERT INTO translated_sentences (book_url, `key`, translation, used_at) " +
                    "VALUES ('file:///one.epub', 'k', 'un', 1000)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, *TranslationCacheDatabase.MIGRATIONS).use { db ->
            db.query("SELECT translation FROM translated_sentences WHERE book_url = 'file:///one.epub'").use {
                assertTrue(it.moveToFirst())
                assertEquals("un", it.getString(0))
            }
            // A service that detects the language keeps no source.
            db.execSQL(
                "INSERT INTO page_translation_modes (book_url, source, target, updated_at) " +
                    "VALUES ('file:///one.epub', NULL, 'en', 1000)",
            )
            db.query("SELECT source IS NULL, target FROM page_translation_modes").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals("en", it.getString(1))
            }
        }
    }

    @Test
    fun `saved sentences keep their last read as their save, and the peer bookkeeping arrives empty`() {
        helper.createDatabase(TEST_DB, 2).use { old ->
            old.execSQL(
                "INSERT INTO translated_sentences (book_url, `key`, translation, used_at) " +
                    "VALUES ('file:///one.epub', 'k', 'un', 1000)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, *TranslationCacheDatabase.MIGRATIONS).use { db ->
            // A sentence saved before the upgrade is shared once: its
            // save is the last time it was read, not 0, which is kept
            // for sentences pulled from a peer and never pushed back.
            db.query("SELECT saved_at FROM translated_sentences").use {
                assertTrue(it.moveToFirst())
                assertEquals(1000, it.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM translation_sync_state").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `saved sentences survive the upgrade that adds owed account clears`() {
        helper.createDatabase(TEST_DB, 3).use { old ->
            old.execSQL(
                "INSERT INTO translated_sentences (book_url, `key`, translation, used_at, saved_at) " +
                    "VALUES ('file:///one.epub', 'k', 'un', 1000, 1000)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, *TranslationCacheDatabase.MIGRATIONS).use { db ->
            db.query("SELECT translation FROM translated_sentences").use {
                assertTrue(it.moveToFirst())
                assertEquals("un", it.getString(0))
            }
            db.query("SELECT COUNT(*) FROM translation_account_clears").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `version five preserves cache rows and cascades kept failure removal`() {
        helper.createDatabase(TEST_DB, 4).use { old ->
            old.execSQL(
                "INSERT INTO translated_sentences (book_url, `key`, translation, used_at, saved_at) " +
                    "VALUES ('file:///one.epub', 'k', 'un', 1000, 1000)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *TranslationCacheDatabase.MIGRATIONS).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.query("SELECT translation, saved_at FROM translated_sentences").use {
                assertTrue(it.moveToFirst())
                assertEquals("un", it.getString(0))
                assertEquals(1000, it.getLong(1))
            }
            db.execSQL(
                "INSERT INTO kept_translations VALUES ('file:///one.epub', NULL, 'fr', 'identity', 1, " +
                    "1, 0, 1, 'done', NULL, 1000, 1000)",
            )
            db.execSQL("INSERT INTO kept_failures VALUES ('file:///one.epub', 'failed')")
            db.execSQL("DELETE FROM kept_translations")
            db.query("SELECT COUNT(*) FROM kept_failures").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `version six keeps whole-book jobs and leaves their approved file unknown`() {
        helper.createDatabase(TEST_DB, 5).use { old ->
            old.execSQL(
                "INSERT INTO kept_translations VALUES ('file:///one.epub', NULL, 'fr', 'identity', 1, " +
                    "10, 4, 1, 'paused', 'interrupted', 1000, 1000)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 6, true, *TranslationCacheDatabase.MIGRATIONS).use { db ->
            db.query("SELECT done, reason, fingerprint FROM kept_translations").use {
                assertTrue(it.moveToFirst())
                assertEquals(4, it.getInt(0))
                assertEquals("interrupted", it.getString(1))
                assertTrue(it.isNull(2))
            }
        }
    }

    private companion object {
        const val TEST_DB = "translations-migration-test"
    }
}
