package com.chmouel.liseur.companion

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class CompanionStoreTest {
    private lateinit var db: CompanionDatabase
    private val library = mutableSetOf(BOOK, OTHER)
    private var clock = 1_000L

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CompanionDatabase::class.java)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    private fun store(maxRecaps: Int = 100, maxCharacters: Long = 1_000_000, maxParts: Int = 100) = CompanionStore(
        database = db,
        present = { urls -> urls.filter { it in library } },
        now = { clock++ },
        maxRecaps = maxRecaps,
        maxCharacters = maxCharacters,
        maxParts = maxParts,
    )

    @Test
    fun `the database migrates to its exported schema`() {
        helper.createDatabase("companion-schema-test", 1).close()
        helper.runMigrationsAndValidate("companion-schema-test", 4, true, *CompanionDatabase.MIGRATIONS).close()
    }

    @Test
    fun `adding running summaries keeps every recap, batch, queued chapter, choice and codex row`() {
        helper.createDatabase("companion-data-test", 3).apply {
            execSQL("INSERT INTO chapter_recap VALUES ('$BOOK', 'k0', 0, '$SHA', 'server', 'model', 1, '{}', 1, 2)")
            execSQL("INSERT INTO recap_part VALUES ('$BOOK', 'k1', 0, 2, '$SHA', 'server', 'model', 1, '{}', 1)")
            execSQL("INSERT INTO recap_queue VALUES ('$BOOK', 'k1', 1, '$SHA', 3)")
            execSQL("INSERT INTO recap_choice VALUES ('$BOOK', 1)")
            execSQL("INSERT INTO codex_book VALUES ('$BOOK', 'id', 4)")
            close()
        }
        helper.runMigrationsAndValidate("companion-data-test", 4, true, *CompanionDatabase.MIGRATIONS).use { migrated ->
            for (table in listOf("chapter_recap", "recap_part", "recap_queue", "recap_choice", "codex_book")) {
                migrated.query("SELECT COUNT(*) FROM $table").use { c ->
                    c.moveToFirst()
                    assertEquals(table, 1, c.getInt(0))
                }
            }
            migrated.query("SELECT COUNT(*) FROM story_summary").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun `running summaries are kept per file and format, dropped with their book, and fenced after removal`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        val deps = listOf(SummaryDep(0, "k0", true), SummaryDep(1, "k1", false))
        assertTrue(book.summaries.saveSummary(SavedSummary("top", StorySummaries.TOP, 0, 1, deps, "So far."), 0))
        assertTrue(book.summaries.saveSummary(SavedSummary("win", 1, 0, 7, deps, "A window."), 0))
        assertEquals("So far.", book.summaries.summaryOf("top"))
        assertEquals(listOf(SavedSummary("top", StorySummaries.TOP, 0, 1, deps, "So far.", 1_002)).map { it.copy(savedAt = 0) },
            book.summaries.tops().map { it.copy(savedAt = 0) })

        val rewritten = store.forBook(BOOK, "new")
        assertNull("another file's summary is not used", rewritten.summaries.summaryOf("top"))
        assertTrue(rewritten.summaries.tops().isEmpty())
        assertTrue("and is dropped once the new file's are read", db.recaps().summariesAt(BOOK, StorySummaries.TOP).isEmpty())

        val other = store.forBook(OTHER, SHA)
        other.summaries.saveSummary(SavedSummary("top", StorySummaries.TOP, 0, 1, deps, "Other."), 0)
        library -= OTHER
        store.sweep()
        assertNull(db.recaps().summary(OTHER, "top"))
        assertFalse(other.summaries.saveSummary(SavedSummary("late", StorySummaries.TOP, 0, 1, deps, "Late."), 0))
        assertNull(db.recaps().summary(OTHER, "late"))
    }

    @Test
    fun `the least recently used running summaries go first once over their limit`() = runTest {
        val store = CompanionStore(db, { it }, { clock++ }, maxSummaries = 2)
        val summaries = store.forBook(BOOK, SHA).summaries
        val deps = listOf(SummaryDep(0, "k0", true))
        for (key in listOf("a", "b")) summaries.saveSummary(SavedSummary(key, StorySummaries.TOP, 0, 0, deps, key), 0)
        summaries.summaryOf("a")
        summaries.saveSummary(SavedSummary("c", StorySummaries.TOP, 0, 0, deps, "c"), 0)
        assertEquals(setOf("a", "c"), summaries.tops().map { it.key }.toSet())
    }

    @Test
    fun `a recap is read back under any server or model, but only for the same file and format`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        assertTrue(book.saveRecap(IDENTITY, UNIT_1, "one", book.ticket()))
        assertEquals(listOf(SavedRecap(UNIT_1, "one")), book.recaps(IDENTITY))

        // Another service reads it, counts it as done, and does not drop it.
        for (other in listOf(IDENTITY.copy(serverId = "other"), IDENTITY.copy(model = "other"))) {
            assertEquals("$other", listOf(SavedRecap(UNIT_1, "one")), book.recaps(other))
            assertNotNull("$other", book.recapOf(other, UNIT_1))
        }
        assertEquals(listOf(SavedRecap(UNIT_1, "one")), book.recaps(IDENTITY))

        val format = IDENTITY.copy(format = IDENTITY.format + 1)
        assertNull(book.recapOf(format, UNIT_1))
        assertTrue(book.recaps(format).isEmpty())
        // Dropped, not merely hidden: going back does not bring it back.
        assertTrue(book.recaps(IDENTITY).isEmpty())
    }

    @Test
    fun `a rewritten file does not see the old file's recaps or queue`() = runTest {
        val store = store()
        val old = store.forBook(BOOK, SHA)
        old.saveRecap(IDENTITY, UNIT_1, "one", old.ticket())
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
        assertTrue(book.savePart(IDENTITY, UNIT_1, 0, 3, "a", book.ticket()))
        assertTrue(book.savePart(IDENTITY, UNIT_1, 1, 3, "b", book.ticket()))

        val reopened = store.forBook(BOOK, SHA)
        assertEquals(mapOf(0 to "a", 1 to "b"), reopened.parts(IDENTITY, UNIT_1, 3))
        assertEquals(listOf(UNIT_1), reopened.queued())
        // Another batching, as after a format change, starts over.
        assertTrue(reopened.parts(IDENTITY, UNIT_1, 4).isEmpty())

        reopened.savePart(IDENTITY, UNIT_1, 0, 3, "a", reopened.ticket())
        reopened.saveRecap(IDENTITY, UNIT_1, "abc", reopened.ticket())
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
        val ticket = book.ticket()

        store.stopRecaps()

        assertTrue(book.queued().isEmpty())
        assertFalse(book.savePart(IDENTITY, UNIT_1, 0, 2, "a", ticket))
        assertFalse(book.saveRecap(IDENTITY, UNIT_1, "a", ticket))
        assertTrue(book.recaps(IDENTITY).isEmpty())
        assertTrue(book.saveRecap(IDENTITY, UNIT_1, "a", book.ticket()))
    }

    @Test
    fun `turning one book off empties only its queue and keeps what was begun for it from landing`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        val other = store.forBook(OTHER, SHA)
        book.enqueue(UNIT_1)
        other.enqueue(UNIT_1)
        val ticket = book.ticket()
        val otherTicket = other.ticket()
        assertNull(store.recapChoice(BOOK).first())

        store.setRecapChoice(BOOK, false)

        assertEquals(false, store.recapChoice(BOOK).first())
        assertTrue(book.queued().isEmpty())
        assertFalse(book.saveRecap(IDENTITY, UNIT_1, "a", ticket))
        assertEquals(listOf(UNIT_1), other.queued())
        assertTrue(other.saveRecap(IDENTITY, UNIT_1, "a", otherTicket))

        store.setRecapChoice(BOOK, true)
        assertEquals(true, store.recapChoice(BOOK).first())
        assertTrue(book.enqueue(UNIT_2))
        assertTrue(book.saveRecap(IDENTITY, UNIT_2, "b", book.ticket()))
    }

    @Test
    fun `turning recaps off in settings keeps the queue of a book turned on`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        val other = store.forBook(OTHER, SHA)
        store.setRecapChoice(OTHER, true)
        book.enqueue(UNIT_1)
        other.enqueue(UNIT_1)
        val ticket = book.ticket()
        val otherTicket = other.ticket()

        store.stopRecaps()

        assertTrue(book.queued().isEmpty())
        assertEquals(listOf(UNIT_1), other.queued())
        assertFalse(book.saveRecap(IDENTITY, UNIT_1, "a", ticket))
        assertTrue(other.saveRecap(IDENTITY, UNIT_1, "a", otherTicket))
    }

    @Test
    fun `removing a book drops its recaps and fences its open handles`() = runTest {
        val store = store()
        val book = store.forBook(BOOK, SHA)
        val other = store.forBook(OTHER, SHA)
        book.saveRecap(IDENTITY, UNIT_1, "one", book.ticket())
        book.savePart(IDENTITY, UNIT_2, 0, 2, "a", book.ticket())
        book.enqueue(UNIT_2)
        other.saveRecap(IDENTITY, UNIT_1, "kept", other.ticket())
        store.setRecapChoice(BOOK, true)
        store.setRecapChoice(OTHER, false)

        library -= BOOK
        store.sweep()

        assertTrue(db.recaps().recaps(BOOK).isEmpty())
        assertTrue(db.recaps().parts(BOOK, UNIT_2.key).isEmpty())
        assertTrue(db.recaps().queued(BOOK).isEmpty())
        assertFalse(book.saveRecap(IDENTITY, UNIT_2, "late", book.ticket()))
        assertFalse(book.enqueue(UNIT_2))
        assertTrue(db.recaps().recaps(BOOK).isEmpty())
        assertEquals(listOf(SavedRecap(UNIT_1, "kept")), other.recaps(IDENTITY))
        assertNull(store.recapChoice(BOOK).first())
        assertEquals(false, store.recapChoice(OTHER).first())
    }

    @Test
    fun `the least recently used recaps go first once over the limits`() = runTest {
        val store = store(maxRecaps = 2)
        val book = store.forBook(BOOK, SHA)
        book.saveRecap(IDENTITY, UNIT_1, "one", book.ticket())
        book.saveRecap(IDENTITY, UNIT_2, "two", book.ticket())
        store.forBook(OTHER, SHA).recaps(IDENTITY)
        book.saveRecap(IDENTITY, UNIT_3, "three", book.ticket())
        assertEquals(setOf(UNIT_2, UNIT_3), book.recaps(IDENTITY).map { it.unit }.toSet())

        val small = store(maxRecaps = 100, maxCharacters = 8)
        val other = small.forBook(OTHER, SHA)
        other.saveRecap(IDENTITY, UNIT_1, "aaaa", other.ticket())
        other.saveRecap(IDENTITY, UNIT_2, "bbbbbb", other.ticket())
        assertEquals(listOf(UNIT_2), other.recaps(IDENTITY).map { it.unit })
    }

    @Test
    fun `restored recaps count only those kept after trimming`() = runTest {
        val store = store(maxRecaps = 2)
        fun row(book: String, key: String, usedAt: Long, format: Int = Recaps.FORMAT) =
            ChapterRecapRow(book, key, 0, SHA, "server", "model", format, "{}", 1, usedAt)
        db.recaps().putRecap(row(BOOK, "existing", 5_000))
        val added = store.restoreRecaps(listOf(
            row(BOOK, "older", 10), row(BOOK, "newer", 20), row(BOOK, "existing", 30),
            row("file:///gone.epub", "missing", 40), row(BOOK, "format", 50, Recaps.FORMAT + 1),
        ))
        assertEquals(1, added)
        assertEquals(setOf("existing", "newer"), db.recaps().recaps(BOOK).map { it.unitKey }.toSet())
        assertEquals(5_000L, db.recaps().recap(BOOK, "existing")!!.usedAt)
    }

    @Test
    fun `the story so far reads without dropping other versions and touches only what it shows`() = runTest {
        val store = store()
        fun row(key: String, sha: String = SHA, format: Int = Recaps.FORMAT) =
            ChapterRecapRow(BOOK, key, 0, sha, "server", "model", format, "{}", 1, 7)
        db.recaps().putRecap(row("shown"))
        db.recaps().putRecap(row("hidden"))
        db.recaps().putRecap(row("old-file", sha = "old"))
        db.recaps().putRecap(row("old-format", format = Recaps.FORMAT + 1))
        val book = store.forBook(BOOK, SHA)
        assertEquals(setOf("shown", "hidden"), book.watch().first().map { it.unit.key }.toSet())
        book.touch(listOf("shown"))

        val rows = db.recaps().recaps(BOOK).associateBy { it.unitKey }
        assertEquals(setOf("shown", "hidden", "old-file", "old-format"), rows.keys)
        assertTrue(rows.getValue("shown").usedAt > 7)
        for (key in listOf("hidden", "old-file", "old-format")) assertEquals(key, 7L, rows.getValue(key).usedAt)
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
