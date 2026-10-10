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

    private companion object {
        const val TEST_DB = "translations-migration-test"
    }
}
