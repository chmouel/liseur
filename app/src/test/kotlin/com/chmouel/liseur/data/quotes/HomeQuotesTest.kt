package com.chmouel.liseur.data.quotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Home may quote, and what the cache is allowed to keep saying.
 *
 * The rules themselves live in [com.chmouel.liseur.domain.quotes.QuoteRules];
 * these are about them being applied: a highlight the reader has not
 * reached, and a file replaced under a URL that keeps its place.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class HomeQuotesTest {
    private lateinit var context: Context
    private lateinit var db: LiseurDatabase
    private lateinit var quoteDb: QuoteDatabase
    private lateinit var quotes: HomeQuotes

    private val url = "file:///books/moby-dick.epub"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        quoteDb = Room.inMemoryDatabaseBuilder(context, QuoteDatabase::class.java).build()
        quotes = HomeQuotes(
            context = context,
            database = db,
            store = { quoteDb.quotes() },
            opener = { error("no book is opened to answer a quote") },
            scope = CoroutineScope(Job()),
            today = { LocalDate.of(2024, 5, 1) },
        )
    }

    @After
    fun tearDown() {
        db.close()
        quoteDb.close()
    }

    @Test
    fun `a highlight past the read point is never quoted`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.2))
        db.annotationDao().upsert(highlight("unread", progression = 0.9, at = 0.9))

        // Nothing else could be quoted, so this would be the one shown.
        assertNull(quotes.quote.first())
    }

    @Test
    fun `a highlight inside what was read is quoted`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.2))
        db.annotationDao().upsert(highlight("read", progression = 0.1, at = 0.1))

        assertEquals("read", quotes.quote.first()?.text)
    }

    @Test
    fun `a highlight with no column is placed by its locator`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.2))
        db.annotationDao().upsert(highlight("read", progression = null, at = 0.1))

        assertEquals("read", quotes.quote.first()?.text)
    }

    @Test
    fun `a highlight nobody placed is not quoted at all`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.5))
        db.annotationDao().upsert(highlight("nowhere", progression = null, at = null))

        assertNull(quotes.quote.first())
    }

    @Test
    fun `a replaced file drops its lines and its watermark`() = runTest {
        dao().replace(scan(workId = "work-one"), listOf(line()))

        quotes.sweep { mapOf(url to "work-two") }

        assertEquals(emptyList<QuoteRow>(), quoteDb.quotes().observeAll().first())
        assertTrue(quoteDb.quotes().scans().isEmpty())
    }

    @Test
    fun `a book still holding the same work keeps them`() = runTest {
        dao().replace(scan(workId = "work-one"), listOf(line()))

        quotes.sweep { mapOf(url to "work-one") }

        assertEquals(listOf("a line worth keeping"), quoteDb.quotes().observeAll().first().map { it.text })
        assertNotNull(quoteDb.quotes().scans().singleOrNull())
    }

    @Test
    fun `a book gone from the library drops them, as before`() = runTest {
        dao().replace(scan(workId = "work-one"), listOf(line()))

        quotes.sweep { emptyMap() }

        assertTrue(quoteDb.quotes().observeAll().first().isEmpty())
    }

    @Test
    fun `a scan that could not name its work is not kept once the book names one`() = runTest {
        dao().replace(scan(workId = null), listOf(line()))

        quotes.sweep { mapOf(url to "work-one") }

        assertTrue(quoteDb.quotes().observeAll().first().isEmpty())
    }

    @Test
    fun `a book with a highlight inside the read point is not walked for lines`() = runTest {
        val highlighted = book()
        val walked = book(url = "file:///books/another.epub")
        db.bookDao().upsertAll(listOf(highlighted, walked))
        db.readingProgressDao().upsert(progress(total = 0.3))
        db.readingProgressDao().upsert(progress(total = 0.3, url = walked.url))
        db.annotationDao().upsert(highlight("read", progression = 0.1, at = 0.1))

        quotes.scanDue()

        // The opener throws, so an attempted walk would leave a row behind.
        assertEquals(listOf(walked.url), quoteDb.quotes().scans().map { it.bookUrl })
    }

    @Test
    fun `a highlight past the read point leaves the text worth walking`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.3))
        db.annotationDao().upsert(highlight("unread", progression = 0.9, at = 0.9))

        quotes.scanDue()

        assertEquals(listOf(url), quoteDb.quotes().scans().map { it.bookUrl })
    }

    private fun dao(): QuoteDao = quoteDb.quotes()

    private fun book(url: String = this.url) = Book(
        url = url,
        title = "Moby-Dick",
        author = "Herman Melville",
        coverPath = null,
        source = null,
        addedAt = 1L,
        lastOpenedAt = 1L,
        workId = "work-one",
    )

    private fun progress(total: Double, url: String = this.url) = ReadingProgress(
        bookUrl = url,
        locatorJson = "{}",
        totalProgression = total,
        updatedAt = 1L,
    )

    private fun highlight(text: String, progression: Double?, at: Double?) = BookAnnotation(
        id = "id-$text",
        bookId = url,
        kind = AnnotationKind.HIGHLIGHT.name,
        locatorJson = JSONObject()
            .put("href", "/chapter-1.xhtml")
            .put("type", "application/xhtml+xml")
            .put(
                "locations",
                JSONObject().apply { at?.let { put("totalProgression", it) } },
            )
            .put("text", JSONObject().put("highlight", text))
            .toString(),
        text = text,
        totalProgression = progression,
        createdAt = 1L,
    )

    private fun scan(workId: String?) = QuoteScanRow(
        bookUrl = url,
        scannedThrough = 0.5,
        version = com.chmouel.liseur.domain.quotes.MemorableLines.VERSION,
        usable = true,
        scannedAt = 1L,
        workId = workId,
    )

    private fun line() = QuoteRow(
        bookUrl = url,
        ord = 0,
        text = "a line worth keeping",
        locatorJson = "{}",
        totalProgression = 0.2,
        chapter = null,
        score = 1.0,
    )
}
