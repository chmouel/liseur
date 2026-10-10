package com.chmouel.liseur.translate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SavedTranslationsTest {
    private lateinit var db: TranslationCacheDatabase
    private val library = mutableSetOf(BOOK, OTHER)
    private var clock = 1_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TranslationCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    private fun saved(maxSentences: Int = 100, maxCharacters: Long = 1_000_000) = SavedTranslations(
        database = db,
        present = { urls -> urls.filter { it in library } },
        now = { clock++ },
        maxSentences = maxSentences,
        maxCharacters = maxCharacters,
    )

    private suspend fun PageTranslationCache.keep(key: String, value: String) = put(key, value, get(key).stamp)

    @Test
    fun `a book opened again finds what it translated`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")

        assertEquals("un", store.forBook(BOOK).get("one").translation)
        assertNull(store.forBook(OTHER).get("one").translation)
        assertEquals(1, store.stats.first().sentences)
    }

    @Test
    fun `a reply asked before a clear is not kept after it`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        val asked = book.get("one")

        store.clear()
        book.put("one", "un", asked.stamp)

        assertNull(book.get("one").translation)
        book.keep("two", "deux")
        assertEquals("deux", book.get("two").translation)
    }

    @Test
    fun `a removed book's sentences go and its open handle keeps nothing more`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        book.keep("one", "un")
        store.forBook(OTHER).keep("one", "un")

        library -= BOOK
        store.sweep()

        assertNull(book.get("one").translation)
        book.keep("two", "deux")
        assertNull(book.get("two").translation)
        assertEquals("un", store.forBook(OTHER).get("one").translation)

        // Added back, it is a new opening and keeps again.
        library += BOOK
        store.forBook(BOOK).keep("two", "deux")
        assertEquals("deux", store.forBook(BOOK).get("two").translation)
    }

    @Test
    fun `a book removed during its first translation keeps nothing`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        val asked = book.get("one")

        library -= BOOK
        store.sweep()
        book.put("one", "un", asked.stamp)

        assertEquals(0, store.stats.first().sentences)
    }

    @Test
    fun `a handle made after its book was swept away keeps nothing`() = runTest {
        val store = saved()
        library -= BOOK
        store.sweep()

        val late = store.forBook(BOOK)
        late.keep("one", "un")

        assertEquals(0, store.stats.first().sentences)
    }

    @Test
    fun `a book brought back after its removal saves again through later sweeps`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        library -= BOOK
        store.sweep()

        library += BOOK
        val back = store.forBook(BOOK)
        store.sweep()
        back.keep("two", "deux")

        assertEquals("deux", back.get("two").translation)
        assertNull(back.get("one").translation)
    }

    @Test
    fun `a sweep while the book is still there keeps it`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        book.keep("one", "un")

        // As when a removal's transaction has not committed, or rolled back.
        store.sweep()

        assertEquals("un", book.get("one").translation)
        book.keep("two", "deux")
        assertEquals("deux", book.get("two").translation)
    }

    @Test
    fun `the least recently read go first past the cap, also across restarts`() = runTest {
        saved(maxSentences = 1_000).forBook(BOOK).apply {
            for (i in 0 until 5) keep("s$i", "t$i")
        }
        val book = saved(maxSentences = 3).forBook(BOOK)
        // Read again, so newer than the others.
        book.get("s0")

        val store = saved(maxSentences = 3)
        store.tidy()

        assertEquals(3, store.stats.first().sentences)
        assertEquals("t0", book.get("s0").translation)
        assertNull(book.get("s1").translation)
        assertNull(book.get("s2").translation)
        assertEquals("t4", book.get("s4").translation)
    }

    @Test
    fun `a save past the cap trims at once`() = runTest {
        val store = saved(maxSentences = 3)
        store.forBook(BOOK).apply {
            for (i in 0 until 5) keep("s$i", "t$i")
        }

        assertEquals(3, store.stats.first().sentences)
        assertNull(store.forBook(BOOK).get("s0").translation)
        assertEquals("t4", store.forBook(BOOK).get("s4").translation)
    }

    @Test
    fun `long translations are trimmed to the character budget`() = runTest {
        saved().forBook(BOOK).apply {
            keep("a", "x".repeat(400))
            keep("b", "y".repeat(400))
            keep("c", "z".repeat(400))
        }

        val store = saved(maxCharacters = 900)
        store.tidy()

        val book = store.forBook(BOOK)
        assertNull(book.get("a").translation)
        assertEquals(400, book.get("b").translation?.length)
        assertEquals(400, book.get("c").translation?.length)
    }

    @Test
    fun `clearing forgets everything`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.forBook(OTHER).keep("two", "deux")

        store.clear()

        assertEquals(0, store.stats.first().sentences)
    }

    @Test
    fun `a sweep that empties the store gives the room back`() = runTest {
        val file = File.createTempFile("translations", ".db").also { it.delete() }
        val onDisk = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), TranslationCacheDatabase::class.java, file.path)
            .allowMainThreadQueries()
            .build()
        try {
            val files = { listOf(file, File("${file.path}-wal"), File("${file.path}-shm")) }
            val store = SavedTranslations(onDisk, { urls -> urls.filter { it in library } }, files, now = { clock++ })
            val book = store.forBook(BOOK)
            repeat(300) { book.keep("sentence $it", "x".repeat(2_000)) }
            val full = store.stats.first().bytes

            library.clear()
            store.sweep()

            val after = store.stats.first()
            assertEquals(0, after.sentences)
            assertTrue("${after.bytes} of $full", after.bytes < full / 4)
        } finally {
            onDisk.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File("${file.path}$it").delete() }
        }
    }

    private companion object {
        const val BOOK = "file:///books/one.epub"
        const val OTHER = "file:///books/two.epub"
    }
}
