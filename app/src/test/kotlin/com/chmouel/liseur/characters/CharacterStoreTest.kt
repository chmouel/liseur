package com.chmouel.liseur.characters

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class CharacterStoreTest {
    private lateinit var db: CharacterDatabase
    private val library = mutableSetOf(BOOK, OTHER)
    private var clock = 1_000L

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CharacterDatabase::class.java)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CharacterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    private fun store(maxRecaps: Int = 100, maxCharacters: Long = 1_000_000, maxParts: Int = 100) = CharacterStore(
        database = db,
        present = { urls -> urls.filter { it in library } },
        now = { clock++ },
        maxRecaps = maxRecaps,
        maxCharacters = maxCharacters,
        maxParts = maxParts,
    )

    @Test
    fun `the first version of the database matches its exported schema`() {
        helper.createDatabase("characters-schema-test", 1).close()
        helper.runMigrationsAndValidate("characters-schema-test", 1, true, *CharacterDatabase.MIGRATIONS).close()
    }

    @Test
    fun `a recap is read back only under the identity that wrote it`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        assertTrue(book.saveRecap(IDENTITY, UNIT_1, "one", store.ticket()))
        assertEquals(listOf(SavedRecap(UNIT_1, "one")), book.recaps(IDENTITY))

        for (other in listOf(
            IDENTITY.copy(serverId = "other"),
            IDENTITY.copy(model = "other"),
            IDENTITY.copy(format = IDENTITY.format + 1),
        )) {
            book.saveRecap(IDENTITY, UNIT_1, "one", store.ticket())
            assertTrue("$other", book.recaps(other).isEmpty())
            // Dropped, not merely hidden: going back does not bring it back.
            assertTrue("$other", book.recaps(IDENTITY).isEmpty())
        }
    }

    @Test
    fun `a rewritten file does not see the old file's recaps or queue`() = runTest {
        val store = store()
        val old = store.forBook(BOOK, SHA)
        old.saveRecap(IDENTITY, UNIT_1, "one", store.ticket())
        old.enqueue(UNIT_2)

        val fresh = store.forBook(BOOK, "other-sha")
        val identity = IDENTITY.copy(contentSha = "other-sha")
        assertTrue(fresh.recaps(identity).isEmpty())
        assertTrue(fresh.queued().isEmpty())
    }

    @Test
    fun `finished batches survive an interruption, and a recap replaces them`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        book.enqueue(UNIT_1)
        assertTrue(book.savePart(IDENTITY, UNIT_1, 0, 3, "a", store.ticket()))
        assertTrue(book.savePart(IDENTITY, UNIT_1, 1, 3, "b", store.ticket()))

        val reopened = store.forBook(BOOK, SHA)
        assertEquals(mapOf(0 to "a", 1 to "b"), reopened.parts(IDENTITY, UNIT_1, 3))
        assertEquals(listOf(UNIT_1), reopened.queued())
        // Another batching, as after a format change, starts over.
        assertTrue(reopened.parts(IDENTITY, UNIT_1, 4).isEmpty())

        reopened.savePart(IDENTITY, UNIT_1, 0, 3, "a", store.ticket())
        reopened.saveRecap(IDENTITY, UNIT_1, "abc", store.ticket())
        assertTrue(reopened.parts(IDENTITY, UNIT_1, 3).isEmpty())
        assertTrue(reopened.queued().isEmpty())
    }

    @Test
    fun `the queue keeps the order chapters were finished in and each chapter once`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        book.enqueue(UNIT_2)
        book.enqueue(UNIT_1)
        book.enqueue(UNIT_2)
        assertEquals(listOf(UNIT_2, UNIT_1), book.queued())
        book.dequeue(UNIT_2)
        assertEquals(listOf(UNIT_1), book.queued())
    }

    @Test
    fun `turning recaps off empties the queue and keeps what was begun before from landing`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        book.enqueue(UNIT_1)
        val ticket = store.ticket()

        store.stopRecaps()

        assertTrue(book.queued().isEmpty())
        assertFalse(book.savePart(IDENTITY, UNIT_1, 0, 2, "a", ticket))
        assertFalse(book.saveRecap(IDENTITY, UNIT_1, "a", ticket))
        assertTrue(book.recaps(IDENTITY).isEmpty())
        assertTrue(book.saveRecap(IDENTITY, UNIT_1, "a", store.ticket()))
    }

    @Test
    fun `removing a book drops its recaps and fences its open handles`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        val other = store.forBook(OTHER, SHA)
        book.saveRecap(IDENTITY, UNIT_1, "one", store.ticket())
        book.savePart(IDENTITY, UNIT_2, 0, 2, "a", store.ticket())
        book.enqueue(UNIT_2)
        other.saveRecap(IDENTITY, UNIT_1, "kept", store.ticket())

        library -= BOOK
        store.sweep()

        assertTrue(db.recaps().recaps(BOOK).isEmpty())
        assertTrue(db.recaps().parts(BOOK, UNIT_2.key).isEmpty())
        assertTrue(db.recaps().queued(BOOK).isEmpty())
        assertFalse(book.saveRecap(IDENTITY, UNIT_2, "late", store.ticket()))
        assertFalse(book.enqueue(UNIT_2))
        assertTrue(db.recaps().recaps(BOOK).isEmpty())
        assertEquals(listOf(SavedRecap(UNIT_1, "kept")), other.recaps(IDENTITY))
    }

    @Test
    fun `the least recently used recaps go first once over the limits`() = runTest {
        val store = store(maxRecaps = 2)
        val book = store.forBook(BOOK, SHA)
        book.saveRecap(IDENTITY, UNIT_1, "one", store.ticket())
        book.saveRecap(IDENTITY, UNIT_2, "two", store.ticket())
        store.forBook(OTHER, SHA).recaps(IDENTITY)
        book.saveRecap(IDENTITY, UNIT_3, "three", store.ticket())
        assertEquals(setOf(UNIT_2, UNIT_3), book.recaps(IDENTITY).map { it.unit }.toSet())

        val small = store(maxRecaps = 100, maxCharacters = 8)
        val other = small.forBook(OTHER, SHA)
        other.saveRecap(IDENTITY, UNIT_1, "aaaa", small.ticket())
        other.saveRecap(IDENTITY, UNIT_2, "bbbbbb", small.ticket())
        assertEquals(listOf(UNIT_2), other.recaps(IDENTITY).map { it.unit })
    }

    private companion object {
        const val BOOK = "file:///book.epub"
        const val OTHER = "file:///other.epub"
        const val SHA = "sha"
        val IDENTITY = RecapIdentity(SHA, "server", "model", 1)
        val UNIT_1 = RecapUnit(0, "k0")
        val UNIT_2 = RecapUnit(1, "k1")
        val UNIT_3 = RecapUnit(2, "k2")
    }
}
