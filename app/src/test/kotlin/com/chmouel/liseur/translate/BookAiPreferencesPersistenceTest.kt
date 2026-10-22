package com.chmouel.liseur.translate

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.GlossaryEntry
import com.chmouel.liseur.data.db.LiseurDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BookAiPreferencesPersistenceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LiseurDatabase::class.java)

    @Test fun independentPreferencesSurviveRemovalAndClearing() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java).build()
        try {
            val dao = db.bookAiPreferencesDao()
            dao.setTranslation("one", "First person")
            dao.setNarration("one", "Quietly")
            dao.setTranslation("two", "Short sentences")
            val glossary = db.glossaryDao()
            glossary.upsert(GlossaryEntry(bookUrl = "one", term = "Anna", translation = "Anne", target = "fr", updatedAt = 1))
            glossary.upsert(GlossaryEntry(bookUrl = "one", term = "Anna", translation = "Ana", target = "es", updatedAt = 1))
            val book = Book(url = "one", title = "Book", author = null, coverPath = null, source = null, addedAt = 0, lastOpenedAt = null)
            db.bookDao().upsert(book)
            db.bookDao().deleteByUrls(listOf("one"))
            db.bookDao().upsert(book)
            assertEquals("First person", dao.get("one")!!.translationDirection)
            assertEquals("Quietly", dao.get("one")!!.narrationDirection)
            assertEquals("Short sentences", dao.get("two")!!.translationDirection)
            assertEquals("Anne", BookTranslationPreferences.from("", glossary.all("one"), "fr").terms.single().preferred)
            assertEquals("Ana", BookTranslationPreferences.from("", glossary.all("one"), "es").terms.single().preferred)
            assertTrue(glossary.all("two").isEmpty())
            dao.setNarration("one", "")
            assertEquals("First person", dao.get("one")!!.translationDirection)
            dao.setTranslation("one", "")
            assertEquals("", dao.get("one")!!.narrationDirection)
            assertEquals("", dao.get("one")!!.translationDirection)
        } finally { db.close() }
    }

    @Test fun migrationPreservesGlossaryAndAddsEmptyDirections() {
        helper.createDatabase("ai-preferences-migration", 68).use {
            it.execSQL("INSERT INTO translation_glossary (book_url,term,aliases,translation,note,target,enabled,updated_at) VALUES ('one','Anna','','Anne','','fr',1,1)")
        }
        helper.runMigrationsAndValidate("ai-preferences-migration", 69, true, *LiseurDatabase.MIGRATIONS).use { db ->
            db.query("SELECT translation FROM translation_glossary").use { assertTrue(it.moveToFirst()); assertEquals("Anne", it.getString(0)) }
            db.query("SELECT COUNT(*) FROM book_ai_preferences").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
