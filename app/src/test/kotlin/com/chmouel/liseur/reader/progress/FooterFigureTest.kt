package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.settings.FooterField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What an edge of the footer says, and above all when it says nothing.
 *
 * An edge has room for a figure and no room for a hedge, so a figure
 * that cannot be stood behind has to be left out rather than dressed
 * up: a page that has not been laid out yet, a chapter that could not
 * be resolved, a pace that has not been measured.
 */
class FooterFigureTest {

    private val progress = ReaderProgress(
        position = 42,
        totalPositions = 300,
        totalProgression = 0.14f,
        chapterTitle = "The Sign of Four",
        minutesLeftInChapter = 12,
        minutesLeftInBook = 340,
        positionsLeftInChapter = 17,
        isSpeedMeasured = true,
        resource = BookResource(index = 2, href = "ch3.xhtml", start = 0.10, end = 0.18),
    )

    /** One resource measured, so the whole book has a length. */
    private val estimate = BookScreenEstimate()
        .recording(generation = 0, index = 2, start = 0.10, end = 0.18, screens = 12)

    private fun figure(
        field: FooterField,
        progress: ReaderProgress? = this.progress,
        reflowable: Boolean = true,
        screens: SectionScreens? = SectionScreens(screen = 4, screens = 12),
        estimate: BookScreenEstimate = this.estimate,
        device: FooterDevice = FooterDevice(),
    ) = footerFigure(field, progress, reflowable, screens, estimate, device)

    // -- The book ----------------------------------------------------------

    @Test
    fun `percent read is the book's own percentage`() {
        assertEquals(
            FooterFigure.BookPercent(14, left = false),
            figure(FooterField.PERCENT_READ),
        )
    }

    @Test
    fun `percent left is the other end of the same figure`() {
        assertEquals(
            FooterFigure.BookPercent(86, left = true),
            figure(FooterField.PERCENT_LEFT),
        )
    }

    @Test
    fun `the page of the book is the one the right edge always drew`() {
        val pages = footerPages(true, SectionScreens(4, 12), progress, estimate)!!
        assertEquals(
            FooterFigure.Pages(pages.page, pages.pages, exact = false),
            figure(FooterField.PAGE_OF_BOOK),
        )
    }

    @Test
    fun `pages left in the book is the total less the page`() {
        val pages = footerPages(true, SectionScreens(4, 12), progress, estimate)!!
        assertEquals(
            FooterFigure.PagesLeftInBook(pages.pages - pages.page, exact = false),
            figure(FooterField.PAGES_LEFT_BOOK),
        )
    }

    @Test
    fun `a page past a total that has not caught up counts down to nought`() {
        // The page is counted and the total is estimated, so a dense
        // stretch can put one past the other. Zero is true there and a
        // negative count is not.
        val short = BookScreenEstimate()
            .recording(generation = 0, index = 2, start = 0.0, end = 1.0, screens = 3)
        val figure = figure(
            FooterField.PAGES_LEFT_BOOK,
            screens = SectionScreens(screen = 3, screens = 3),
            estimate = short,
        )
        assertEquals(FooterFigure.PagesLeftInBook(0, exact = false), figure)
    }

    @Test
    fun `a fixed-layout book counts its own pages exactly`() {
        assertEquals(
            FooterFigure.Pages(42, 300, exact = true),
            figure(FooterField.PAGE_OF_BOOK, reflowable = false, screens = null),
        )
    }

    @Test
    fun `an unmeasured reflowable page has no page number`() {
        assertNull(figure(FooterField.PAGE_OF_BOOK, screens = null))
        assertNull(figure(FooterField.PAGES_LEFT_BOOK, screens = null))
    }

    @Test
    fun `the location is the stable position, whatever the typography does`() {
        assertEquals(
            FooterFigure.Location(42, 300),
            figure(FooterField.LOCATION),
        )
    }

    @Test
    fun `a book with no positions has no location`() {
        assertNull(figure(FooterField.LOCATION, progress = progress.copy(totalPositions = 0)))
    }

    // -- The chapter -------------------------------------------------------

    @Test
    fun `pages left in the chapter counts the screens the middle counts`() {
        assertEquals(
            FooterFigure.PagesLeftInChapter(8),
            figure(FooterField.PAGES_LEFT_CHAPTER),
        )
        assertEquals(
            FooterMiddle.PagesInChapter(8),
            footerMiddle(
                progress,
                com.chmouel.liseur.data.settings.FooterMode.PAGES_LEFT_CHAPTER,
                reflowable = true,
                screens = SectionScreens(4, 12),
            ),
        )
    }

    @Test
    fun `a chapter that cannot be resolved is not counted to`() {
        assertNull(
            figure(
                FooterField.PAGES_LEFT_CHAPTER,
                progress = progress.copy(positionsLeftInChapter = null),
            ),
        )
    }

    @Test
    fun `the page of the chapter is the measured screen`() {
        assertEquals(
            FooterFigure.PagesInChapter(4, 12),
            figure(FooterField.PAGE_IN_CHAPTER),
        )
    }

    @Test
    fun `a page of the chapter is refused where no screen was measured`() {
        // The percentages fall back to a share of the resource, which
        // is honest as a percentage and would be an invented page here.
        assertNull(figure(FooterField.PAGE_IN_CHAPTER, screens = null))
        assertNull(figure(FooterField.PAGE_IN_CHAPTER, reflowable = false, screens = null))
    }

    @Test
    fun `the chapter percentage is measured where the screens are`() {
        assertEquals(
            FooterFigure.ChapterPercent(33, left = false),
            figure(FooterField.PERCENT_READ_CHAPTER),
        )
        assertEquals(
            FooterFigure.ChapterPercent(67, left = true),
            figure(FooterField.PERCENT_LEFT_CHAPTER),
        )
    }

    @Test
    fun `the last screen of a chapter has read all of it`() {
        assertEquals(
            FooterFigure.ChapterPercent(100, left = false),
            figure(FooterField.PERCENT_READ_CHAPTER, screens = SectionScreens(12, 12)),
        )
    }

    @Test
    fun `an unmeasured chapter falls back to its share of the book`() {
        // The resource holds positions 31 to 54, and position 42 is
        // the twelfth of those twenty-four. The position on screen
        // counts as read, as it does when the screens have been
        // measured, so this is 12 of 24 rather than 11.
        assertEquals(
            FooterFigure.ChapterPercent(50, left = false),
            figure(FooterField.PERCENT_READ_CHAPTER, reflowable = false, screens = null),
        )
    }

    /**
     * The resource's own first position, where one of its twenty-four
     * has been read. Before the two ends were put on the same ruler
     * this came out at several percent, and a fixed-layout book, where
     * every resource holds one position, handed the whole book's
     * percentage to a corner saying "chapter".
     */
    @Test
    fun `arriving at a chapter has read one page of it`() {
        val atStart = progress.copy(totalProgression = 30 / 300f, position = 31)
        assertEquals(
            FooterFigure.ChapterPercent(4, left = false),
            figure(
                FooterField.PERCENT_READ_CHAPTER,
                progress = atStart,
                reflowable = false,
                screens = null,
            ),
        )
    }

    /**
     * The last resource in a book ends at 1.0 while the progression
     * stops one position short of it, so a two-position final chapter
     * used to read half done with the book finished.
     */
    @Test
    fun `the last chapter of a book can be finished`() {
        val ending = progress.copy(
            totalProgression = 299 / 300f,
            position = 300,
            resource = BookResource(index = 9, href = "end.xhtml", start = 298.0 / 300, end = 1.0),
        )
        assertEquals(
            FooterFigure.ChapterPercent(100, left = false),
            figure(
                FooterField.PERCENT_READ_CHAPTER,
                progress = ending,
                reflowable = false,
                screens = null,
            ),
        )
    }

    /**
     * One position is the whole of a fixed-layout resource, and the
     * measured path says the same about a chapter of one screen.
     */
    @Test
    fun `a chapter of one page is read as soon as it is open`() {
        val onePage = progress.copy(
            totalProgression = 41 / 300f,
            position = 42,
            resource = BookResource(index = 2, href = "p42.xhtml", start = 41.0 / 300, end = 42.0 / 300),
        )
        assertEquals(
            FooterFigure.ChapterPercent(100, left = false),
            figure(
                FooterField.PERCENT_READ_CHAPTER,
                progress = onePage,
                reflowable = false,
                screens = null,
            ),
        )
    }

    /**
     * A book of a single position is a usable book, and its only
     * resource is necessarily read through.
     */
    @Test
    fun `a book of one page is read as soon as it is open`() {
        val whole = progress.copy(
            position = 1,
            totalPositions = 1,
            totalProgression = 0f,
            resource = BookResource(index = 0, href = "only.xhtml", start = 0.0, end = 1.0),
        )
        assertEquals(
            FooterFigure.ChapterPercent(100, left = false),
            figure(
                FooterField.PERCENT_READ_CHAPTER,
                progress = whole,
                reflowable = false,
                screens = null,
            ),
        )
    }

    // -- The time ----------------------------------------------------------

    @Test
    fun `time is a figure the corners wait for`() {
        // The middle shows an unmeasured guess and hedges it with the
        // chapter's name when it has nothing better. A corner has room
        // for neither, so it waits for a pace taken from this reader.
        val guessing = progress.copy(isSpeedMeasured = false)
        assertNull(figure(FooterField.TIME_LEFT_BOOK, progress = guessing))
        assertNull(figure(FooterField.TIME_LEFT_CHAPTER, progress = guessing))
    }

    @Test
    fun `a measured pace puts both times on an edge`() {
        assertEquals(FooterFigure.TimeInBook(340), figure(FooterField.TIME_LEFT_BOOK))
        assertEquals(FooterFigure.TimeInChapter(12), figure(FooterField.TIME_LEFT_CHAPTER))
    }

    @Test
    fun `an unresolved chapter has no time of its own`() {
        // The minutes are still there, but they count to the end of the
        // book, and "12 mins left in chapter" would be a different
        // book's worth of reading under a chapter's label. The pages
        // left are null in exactly that case.
        val noChapter = progress.copy(positionsLeftInChapter = null)
        assertNull(figure(FooterField.TIME_LEFT_CHAPTER, progress = noChapter))
        assertEquals(
            FooterFigure.TimeInBook(340),
            figure(FooterField.TIME_LEFT_BOOK, progress = noChapter),
        )
    }

    // -- The device --------------------------------------------------------

    @Test
    fun `the clock and the battery are answered without a book`() {
        val device = FooterDevice(nowMillis = 1_700_000_000_000L, batteryPercent = 78)
        assertEquals(
            FooterFigure.Clock(1_700_000_000_000L),
            figure(FooterField.CLOCK, progress = null, device = device),
        )
        assertEquals(
            FooterFigure.Battery(78, charging = false),
            figure(FooterField.BATTERY, progress = null, device = device),
        )
    }

    @Test
    fun `a device that will not say is not guessed at`() {
        assertNull(figure(FooterField.CLOCK))
        assertNull(figure(FooterField.BATTERY))
        assertNull(figure(FooterField.BATTERY, device = FooterDevice(batteryPercent = 120)))
    }

    // -- Nothing -----------------------------------------------------------

    @Test
    fun `an emptied edge draws nothing`() {
        assertNull(figure(FooterField.EMPTY))
    }

    @Test
    fun `no page means no figure about the book`() {
        assertNull(figure(FooterField.PERCENT_READ, progress = null))
        assertNull(figure(FooterField.PAGES_LEFT_CHAPTER, progress = null))
    }
}
