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
import org.junit.Assert.assertFalse
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
    private val walked = "file:///books/another.epub"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        quoteDb = Room.inMemoryDatabaseBuilder(context, QuoteDatabase::class.java).build()
        quotes = homeQuotes()
    }

    private fun homeQuotes(
        opener: () -> com.chmouel.liseur.translate.TranslationPublicationOpener = {
            error("no book is opened to answer a quote")
        },
    ) = HomeQuotes(
        context = context,
        database = db,
        store = { quoteDb.quotes() },
        opener = opener,
        scope = CoroutineScope(Job()),
        today = { LocalDate.of(2024, 5, 1) },
    )

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
        val other = book(url = walked)
        db.bookDao().upsertAll(listOf(highlighted, other))
        db.readingProgressDao().upsert(progress(total = 0.3, locator = chapter(0.1)))
        db.readingProgressDao().upsert(progress(total = 0.3, url = other.url, locator = chapter(0.1)))
        db.annotationDao().upsert(highlight("read", progression = 0.1, at = 0.1))

        quotes.scanDue()

        // The opener throws, so an attempted walk would leave a row behind.
        assertEquals(listOf(other.url), quoteDb.quotes().scans().map { it.bookUrl })
    }

    @Test
    fun `a highlight past the read point leaves the text worth walking`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.3, locator = chapter(0.1)))
        db.annotationDao().upsert(highlight("unread", progression = 0.9, at = 0.9))

        quotes.scanDue()

        assertEquals(listOf(url), quoteDb.quotes().scans().map { it.bookUrl })
    }

    @Test
    fun `a note on a passage is still that passage`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.2))
        db.annotationDao().upsert(
            highlight("read", progression = 0.1, at = 0.1).copy(kind = AnnotationKind.NOTE.name, note = "about it"),
        )

        assertEquals("read", quotes.quote.first()?.text)
    }

    @Test
    fun `a book marked unread starts the ceiling over`() = runTest {
        // Marking unread clears the place but leaves the furthest point
        // recorded before it. What was read then is not what the reader
        // is reading now.
        val unread = progress(total = 0.0).copy(
            peakProgression = 0.8,
            peakRevision = 3,
            localRevision = 4,
            unreadResetRevision = 4,
            finishedOverride = com.chmouel.liseur.domain.FinishedOverride.UNREAD.ordinal,
        )
        assertEquals(0.0, HomeQuotes.readPoint(unread), 0.0)

        // Reading on past the old peak gives the peak a newer revision,
        // and it speaks for the reading again.
        val readOn = unread.copy(
            totalProgression = 0.82,
            peakProgression = 0.85,
            peakRevision = 6,
            localRevision = 6,
        )
        assertEquals(0.85, HomeQuotes.readPoint(readOn), 0.0)

        // Before that it is the place the reader is at, and no further.
        assertEquals(
            0.02,
            HomeQuotes.readPoint(unread.copy(totalProgression = 0.02, localRevision = 5)),
            0.0,
        )
    }

    @Test
    fun `a book whose file was replaced is walked again even before its place moves`() = runTest {
        // The watermark says 0.5 and the reader is at 0.3, so nothing is
        // due by progress alone. The recorded work is not the book's,
        // though, and those lines are not its words.
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.3, locator = chapter(0.1)))
        dao().replace(scan(workId = "work-previous"), listOf(line()))

        quotes.scanDue()

        // The opener throws, so the row is rewritten as unusable and the
        // old work's lines are cleared with it: proof the walk ran.
        val scan = quoteDb.quotes().scans().single()
        assertEquals(url, scan.bookUrl)
        assertEquals("work-one", scan.workId)
        assertFalse(scan.usable)
        assertTrue(quoteDb.quotes().observeAll().first().isEmpty())
    }

    @Test
    fun `the day's line survives another book becoming eligible`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.3, locator = chapter(0.1)))
        db.readingProgressDao().upsert(progress(total = 0.3, url = walked, locator = chapter(0.1)))
        dao().replace(scan(workId = "work-one"), listOf(line()))

        val first = quotes.quote.first()?.text
        assertNotNull(first)

        // A second book arrives with a line of its own. The day seeded a
        // different book before it existed, and must not now.
        db.bookDao().upsert(book(url = walked))
        dao().replace(
            scan(workId = "work-one", url = walked, scannedThrough = 0.3),
            listOf(line(url = walked, text = "another line")),
        )

        assertEquals(first, quotes.quote.first()?.text)
    }

    @Test
    fun `a mark in the chapter the reader left behind is still read`() = runTest {
        // The reader read to 80% of the chapter and came back to 20%.
        // The furthest place, not the current one, is where the ceiling
        // is: the rest of that chapter has been read.
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(
            progress(total = 0.2, locator = chapter(0.2)).copy(
                peakProgression = 0.8,
                peakLocator = chapter(0.8),
                peakRevision = 4,
                localRevision = 5,
            ),
        )
        db.annotationDao().upsert(
            highlight("read", progression = null, at = null).copy(locatorJson = chapter(0.6)),
        )

        assertEquals("read", quotes.quote.first()?.text)
    }

    @Test
    fun `a finished book may be quoted anywhere`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(
            progress(total = 0.2, locator = chapter(0.2)).copy(
                finishedOverride = com.chmouel.liseur.domain.FinishedOverride.FINISHED.ordinal,
                peakProgression = 0.3,
                peakLocator = chapter(0.3),
                peakRevision = 4,
                localRevision = 5,
            ),
        )
        // The same chapter as the furthest place, further into it than
        // the recorded peak: only the finished mark vouches for it.
        db.annotationDao().upsert(
            highlight("read", progression = 0.95, at = 0.95).copy(locatorJson = chapter(0.9)),
        )

        assertEquals("read", quotes.quote.first()?.text)
    }

    @Test
    fun `an unread reset gives the old peak no say over a chapter`() = runTest {
        // The reader marked the book unread and has read 2% of it since.
        // The ceiling starts over, and the place that speaks for it has
        // to be the one that ceiling was read from: a highlight at 60%
        // of the chapter is past where they are now.
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(
            progress(total = 0.02, locator = chapter(0.02)).copy(
                peakProgression = 0.8,
                peakLocator = chapter(0.8),
                peakRevision = 3,
                localRevision = 5,
                unreadResetRevision = 4,
                finishedOverride = com.chmouel.liseur.domain.FinishedOverride.UNREAD.ordinal,
            ),
        )
        db.annotationDao().upsert(
            highlight("unread", progression = 0.6, at = 0.6).copy(locatorJson = chapter(0.6)),
        )

        assertNull(quotes.quote.first())
    }

    @Test
    fun `a book that could not be opened keeps its lines and is tried again`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 1.0).copy(
            finishedOverride = com.chmouel.liseur.domain.FinishedOverride.FINISHED.ordinal,
        ))
        dao().replace(scan(workId = "work-one", scannedThrough = 1.0), listOf(line()))
        val storage = homeQuotes(opener = { throw java.io.IOException("card removed") })

        storage.scanDue()

        // A walk that never happened is not a scan: recording it would
        // keep a book marked read from ever being looked at again.
        assertEquals(listOf("a line worth keeping"), quoteDb.quotes().observeAll().first().map { it.text })
        assertEquals(1.0, quoteDb.quotes().scans().single().scannedThrough, 0.0)
    }

    @Test
    fun `a position pulled from another device still says where the ceiling is`() {
        // A pull fills the place and not the peak. The fraction may come
        // from a client that measures the book differently, so the walk
        // needs the place it does have rather than nothing.
        val pulled = progress(total = 0.6, locator = chapter(0.4))
        assertNull(pulled.peakLocator)
        assertEquals(chapter(0.4), HomeQuotes.ceilingLocator(pulled))

        // A peak that stands speaks for the ceiling.
        val peaked = pulled.copy(peakProgression = 0.8, peakLocator = chapter(0.7), peakRevision = 3, localRevision = 4)
        assertEquals(chapter(0.7), HomeQuotes.ceilingLocator(peaked))

        // Once an unread reset has started it over, the place is the one
        // that counts, not the peak it kept.
        assertEquals(chapter(0.4), HomeQuotes.ceilingLocator(peaked.copy(unreadResetRevision = 5)))

        // A forward pull can leave the peak behind the place the reader
        // is on. The ceiling is then the place, not the peak: stopping
        // at the peak would hide words the count already covers.
        val forward = pulled.copy(
            totalProgression = 0.8,
            locatorJson = chapter(0.8),
            peakProgression = 0.2,
            peakLocator = chapter(0.2),
            peakRevision = 3,
            localRevision = 5,
        )
        assertEquals(chapter(0.8), HomeQuotes.ceilingLocator(forward))
    }

    @Test
    fun `a book whose ceiling is a percentage nobody can point at is left unread`() = runTest {
        // A position pulled from a client that sent no locator: the
        // fraction is on that client's scale, and there is no chapter to
        // stop a walk at. Nothing is cached until a page is turned here.
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.6, locator = "{}"))

        quotes.scanDue()

        assertTrue(quoteDb.quotes().scans().isEmpty())
    }

    @Test
    fun `a book whose place is written down here is walked to it`() = runTest {
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.6, locator = chapter(0.4)))

        quotes.scanDue()

        assertEquals(listOf(url), quoteDb.quotes().scans().map { it.bookUrl })
    }

    @Test
    fun `a book taken off the shelf is neither quoted nor walked`() = runTest {
        db.bookDao().upsert(book().copy(hiddenAt = 1L))
        db.readingProgressDao().upsert(progress(total = 0.3, locator = chapter(0.1)))
        dao().replace(scan(workId = "work-one"), listOf(line()))

        quotes.scanDue()

        assertEquals(0.5, quoteDb.quotes().scans().single().scannedThrough, 0.0)
        assertNull(quotes.quote.first())
    }

    @Test
    fun `a cached line in the reader's own chapter is checked where both are measured`() = runTest {
        // The ceiling is the whole-book fraction a peer wrote (0.9 is
        // past everything locally), and the line sits later in the same
        // chapter than the place this device recorded.
        db.bookDao().upsert(book())
        db.readingProgressDao().upsert(progress(total = 0.9, locator = chapter(0.3)))
        dao().replace(scan(workId = "work-one"), listOf(line().copy(locatorJson = chapter(0.7))))

        assertNull(quotes.quote.first())
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

    private fun progress(total: Double, url: String = this.url, locator: String = "{}") = ReadingProgress(
        bookUrl = url,
        locatorJson = locator,
        totalProgression = total,
        updatedAt = 1L,
    )

    /** A locator in a chapter, as this device writes one. */
    private fun chapter(progression: Double, href: String = "/chapter-1.xhtml") = JSONObject()
        .put("href", href)
        .put("type", "application/xhtml+xml")
        .put("locations", JSONObject().put("progression", progression))
        .toString()

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

    private fun scan(workId: String?, url: String = this.url, scannedThrough: Double = 0.5) = QuoteScanRow(
        bookUrl = url,
        scannedThrough = scannedThrough,
        version = com.chmouel.liseur.domain.quotes.MemorableLines.VERSION,
        usable = true,
        scannedAt = 1L,
        workId = workId,
    )

    private fun line(
        text: String = "a line worth keeping",
        url: String = this.url,
        workId: String? = "work-one",
    ) = QuoteRow(
        bookUrl = url,
        ord = 0,
        text = text,
        locatorJson = "{}",
        totalProgression = 0.2,
        chapter = null,
        score = 1.0,
        workId = workId,
    )
}
