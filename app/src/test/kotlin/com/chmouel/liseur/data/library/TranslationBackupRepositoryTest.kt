package com.chmouel.liseur.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.decodeTranslationBackup
import com.chmouel.liseur.translate.SavedTranslations
import com.chmouel.liseur.translate.TranslationCacheDatabase
import com.chmouel.liseur.translate.WholeBookCoverage
import com.chmouel.liseur.translate.WholeBookPreview
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class TranslationBackupRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var library: LiseurDatabase
    private lateinit var database: TranslationCacheDatabase
    private lateinit var store: SavedTranslations
    private lateinit var repository: TranslationBackupRepository

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        library = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        database = Room.inMemoryDatabaseBuilder(context, TranslationCacheDatabase::class.java).build()
        var clock = 1000L
        store = SavedTranslations(database, { library.bookDao().presentUrls(it) }, now = { clock++ })
        repository = TranslationBackupRepository({ store }, library.bookDao())
    }

    @After fun close() { database.close(); library.close() }

    @Test fun `locally finalized interrupted coverage is included in backups`(): Unit = runTest {
        library.bookDao().upsert(book("source"))
        val record = store.begin("source", null, "fr", "identity", 1)
        store.saveSuccess("source", record.generation, "one", "un", 1, 0)
        store.pause("source", record.generation, "interrupted")
        val file = File(folder.root, "finalized.json")
        assertEquals(TranslationBackupResult(0, 1), repository.writeContents(file, TranslationBackupRepository.MAX_BYTES))
        val preview = WholeBookPreview(
            "source", "Book", null, "fr", "identity", 1, 1, 0, 0, null, null, "fingerprint",
            retentionStamp = store.retentionStamp("source"),
        )
        store.retain(preview) {
            val saved = store.strictLookup("source", "one")
            WholeBookCoverage(1, if (saved.translation == null) 0 else 1, 0, 0, 0, 0, 0)
        }
        assertEquals(TranslationBackupResult(1), repository.writeContents(file, TranslationBackupRepository.MAX_BYTES))
        assertEquals("un", file.reader().use(::decodeTranslationBackup).single().rows.single().translation)
    }

    @Test fun `restored records export again with failure keys and fresh local save times`() = runTest {
        library.bookDao().upsert(book("source"))
        val record = store.begin("source", null, "fr", "identity", 2)
        store.saveSuccess("source", record.generation, "one", "é漢😀", 1, 0)
        store.saveFailure("source", record.generation, "two", 1, 1)
        store.complete("source", record.generation)
        val file = File(folder.root, "translations.json")
        assertEquals(TranslationBackupResult(1), repository.writeContents(file, TranslationBackupRepository.MAX_BYTES))
        val contents = file.reader().use(::decodeTranslationBackup)
        val originalAt = store.exportRows("source").single().savedAt
        store.remove("source")
        assertEquals(TranslationBackupResult(1), repository.restore(contents))
        assertEquals("restored", store.keptRecords().single().state)
        assertTrue(store.exportRows("source").single().savedAt > originalAt)
        assertTrue(store.strictLookup("source", "two").failed)
        val size = file.length()
        assertEquals(TranslationBackupResult(1), repository.writeContents(file, size))
        val repeated = file.reader().use(::decodeTranslationBackup)
        assertEquals(contents.single().rows.single().key, repeated.single().rows.single().key)
        assertEquals(contents.single().failures, repeated.single().failures)
        assertEquals(TranslationBackupResult(0, 1), repository.writeContents(file, size - 1))
        assertTrue(file.reader().use(::decodeTranslationBackup).isEmpty())
    }

    @Test fun `destination collisions skip both books before writing any rows`() = runTest {
        for (url in listOf("source", "second")) {
            library.bookDao().upsert(book(url))
            val record = store.begin(url, null, "fr", "identity", 1)
            store.saveSuccess(url, record.generation, url, "translation", 1, 0)
            store.complete(url, record.generation)
        }
        val file = File(folder.root, "translations.json")
        repository.writeContents(file, TranslationBackupRepository.MAX_BYTES)
        val contents = file.reader().use(::decodeTranslationBackup)
        for (url in listOf("source", "second")) store.remove(url)
        library.bookDao().deleteByUrls(listOf("source", "second"))
        library.bookDao().upsert(book("destination"))
        assertEquals(TranslationBackupResult(0, 2), repository.restore(contents))
        assertTrue(store.keptRecords().isEmpty())
    }

    @Test fun `decoder rejects duplicate keys and malformed counts before restore`() = runTest {
        library.bookDao().upsert(book("source"))
        val record = store.begin("source", null, "fr", "identity", 1)
        store.saveSuccess("source", record.generation, "one", "un", 1, 0)
        store.complete("source", record.generation)
        val file = File(folder.root, "translations.json")
        repository.writeContents(file, TranslationBackupRepository.MAX_BYTES)
        val text = file.readText()
        val key = store.exportRows("source").single().key
        for (bad in listOf(
            text.replace("\"failures\":[]", "\"failures\":[\"$key\"]"),
            text.replace("\"failed\":0", "\"failed\":2"),
            text.replace("\"peer\":false", "\"peer\":\"false\""),
        )) {
            var rejected = false
            try { decodeTranslationBackup(bad.reader()) } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
        }
    }

    private fun book(url: String) = Book(url = url, title = "Book", author = "Author",
        coverPath = null, source = null, addedAt = 1, lastOpenedAt = null)
}
