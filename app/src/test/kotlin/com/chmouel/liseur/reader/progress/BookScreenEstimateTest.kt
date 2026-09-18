package com.chmouel.liseur.reader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The running guess at how long the book is in screenfuls.
 *
 * Accuracy matters least of what is tested here. The total must stand
 * still while the reader turns pages inside one resource, or the figure
 * reads as broken. It must be thrown away the moment the page is
 * rebuilt, because every older sample is then about different pages.
 * And the page number must never go backwards on a forward turn,
 * whatever joining a new sample does to the total: not when the book is
 * read from its first page, and not when it is resumed in the middle
 * with everything behind the reader still unmeasured.
 */
class BookScreenEstimateTest {

    private fun BookScreenEstimate.record(
        index: Int,
        start: Double,
        end: Double,
        screens: Int,
        generation: Int = layout,
    ) = recording(generation, index, start, end, screens)

    @Test
    fun `nothing measured says nothing`() {
        assertNull(BookScreenEstimate().totalScreens)
        assertNull(BookScreenEstimate().pageAt(0, 1))
    }

    @Test
    fun `one resource scales up to the book`() {
        // A twentieth of the book at 12 screens makes the book 240.
        assertEquals(240, BookScreenEstimate().record(0, 0.0, 0.05, 12).totalScreens)
    }

    @Test
    fun `more resources refine the guess`() {
        val dense = BookScreenEstimate()
            .record(0, 0.0, 0.05, 12)
            .record(1, 0.05, 0.10, 20)
        // 32 screens across a tenth of the book puts it at 320.
        assertEquals(320, dense.totalScreens)
    }

    @Test
    fun `a resource measuring differently begins the estimate again`() {
        val before = BookScreenEstimate()
            .record(0, 0.0, 0.05, 12)
            .record(1, 0.05, 0.10, 12)
        assertEquals(240, before.totalScreens)
        // The page was rebuilt without anything noticing and resource 1
        // now needs 18 screens.
        val after = before.record(1, 0.05, 0.10, 18)
        assertEquals(1, after.samples.size)
        assertEquals(360, after.totalScreens)
    }

    @Test
    fun `measuring the same resource the same way changes nothing`() {
        val once = BookScreenEstimate().record(0, 0.0, 0.05, 12)
        assertTrue(once === once.record(0, 0.0, 0.05, 12))
    }

    @Test
    fun `a reading from another layout is refused`() {
        // The type size changed while the question was out. The answer
        // describes a page that no longer exists.
        val estimate = BookScreenEstimate().record(0, 0.0, 0.05, 12)
        assertTrue(estimate === estimate.record(1, 0.05, 0.10, 20, generation = 7))
        assertEquals(240, estimate.totalScreens)
    }

    @Test
    fun `moving to another layout empties the estimate`() {
        val estimate = BookScreenEstimate().record(0, 0.0, 0.05, 12)
        val rebuilt = estimate.forLayout(1)
        assertNull(rebuilt.totalScreens)
        assertTrue(estimate === estimate.forLayout(0))
        // And it takes readings at the new shape.
        assertEquals(360, rebuilt.record(0, 0.0, 0.05, 18).totalScreens)
    }

    @Test
    fun `the page walks up by one for each screen of the resource`() {
        val estimate = BookScreenEstimate().record(3, 0.5, 0.55, 10)
        // Half the book is 100 screens behind a 200-screen book.
        assertEquals(200, estimate.totalScreens)
        assertEquals((101..110).toList(), (1..10).mapNotNull { estimate.pageAt(3, it) })
    }

    @Test
    fun `a short resource shortens the book without moving the page back`() {
        // Resource 0 is dense: 20 screens in a tenth of the book, so
        // the book looks 200 long and its last screen is page 20.
        // Resource 1 is half as dense, and joining it cuts the estimate
        // to 150 - but page 21 still follows page 20.
        val first = BookScreenEstimate().record(0, 0.0, 0.10, 20)
        assertEquals(200, first.totalScreens)
        assertEquals(20, first.pageAt(0, 20))

        val second = first.record(1, 0.10, 0.20, 10)
        assertEquals(150, second.totalScreens)
        assertEquals(21, second.pageAt(1, 1))
    }

    @Test
    fun `a book resumed in the middle does not move the page back either`() {
        // Nothing before 0.4 has ever been measured, so it is guessed.
        // Resource 4 is dense and puts the book at 200; its last screen
        // is page 100. Resource 5 is a twentieth as dense and revises
        // the guessed prefix downwards, which would drag the page back
        // to 63 if the screens behind the reader were scaled rather
        // than counted.
        val opened = BookScreenEstimate().record(4, 0.4, 0.5, 20)
        assertEquals(200, opened.totalScreens)
        assertEquals(100, opened.pageAt(4, 20))

        val onward = opened.record(5, 0.5, 0.6, 1)
        assertEquals(101, onward.pageAt(5, 1))
        assertTrue(onward.totalScreens!! >= 101)
    }

    @Test
    fun `a resource measured out of order still reads forwards`() {
        // The reader jumped ahead, then came back to read the part they
        // skipped. Both stretches must still run in book order.
        val jumped = BookScreenEstimate()
            .record(8, 0.8, 0.9, 30)
            .record(2, 0.2, 0.3, 4)
        val early = jumped.pageAt(2, 4) ?: error("no page for the earlier resource")
        val late = jumped.pageAt(8, 1) ?: error("no page for the later resource")
        assertTrue("$early should come before $late", early < late)
        assertTrue(jumped.totalScreens!! >= late + 29)
    }

    @Test
    fun `reading a whole book forwards never turns the page number back`() {
        assertForwardReadingRises(listOf(2, 30, 1, 18, 7, 44, 3), from = 0)
    }

    @Test
    fun `resuming partway through a book never turns the page number back`() {
        // Every possible resumption point of the same uneven book.
        val shape = listOf(2, 30, 1, 18, 7, 44, 3)
        shape.indices.forEach { assertForwardReadingRises(shape, from = it) }
    }

    /**
     * Reads [shape] from resource [from] to the end, one screen at a
     * time, and insists the page number rises by exactly one each turn
     * inside a resource and at least one across a boundary.
     */
    private fun assertForwardReadingRises(shape: List<Int>, from: Int) {
        var estimate = BookScreenEstimate()
        var last = 0
        (from until shape.size).forEach { index ->
            val start = index.toDouble() / shape.size
            val end = (index + 1).toDouble() / shape.size
            estimate = estimate.record(index, start, end, shape[index])
            (1..shape[index]).forEach { screen ->
                val page = estimate.pageAt(index, screen)
                    ?: error("no page for resource $index screen $screen")
                assertTrue(
                    "resuming at $from, page went $last -> $page at resource $index",
                    page > last,
                )
                assertTrue("page $page past the end", page <= estimate.totalScreens!!)
                last = page
            }
        }
    }

    @Test
    fun `a gap never visited is guessed at the density around it`() {
        // The reader jumped straight to the second half of the book.
        val estimate = BookScreenEstimate().record(5, 0.5, 0.6, 10)
        assertEquals(100, estimate.totalScreens)
        // Half a book of 100 screens lies ahead of it.
        assertEquals(51, estimate.pageAt(5, 1))
    }

    @Test
    fun `the page never runs past the end of the book`() {
        val estimate = BookScreenEstimate().record(0, 0.0, 0.5, 10)
        assertEquals(20, estimate.totalScreens)
        assertEquals(10, estimate.pageAt(0, 10))
        assertNull(estimate.pageAt(0, 11))
    }

    @Test
    fun `a book is never shorter than what has been counted in it`() {
        // A resource claiming almost the whole book still holds its
        // own screens, so the total cannot round below them.
        val estimate = BookScreenEstimate().record(0, 0.0, 1.0, 40)
        assertEquals(40, estimate.totalScreens)
    }

    @Test
    fun `nonsense spans and counts are refused`() {
        val empty = BookScreenEstimate()
        assertTrue(empty === empty.record(0, 0.5, 0.5, 12))
        assertTrue(empty === empty.record(0, 0.6, 0.5, 12))
        assertTrue(empty === empty.record(0, 0.0, 0.05, 0))
        assertTrue(empty === empty.record(0, Double.NaN, 0.05, 12))
        assertTrue(empty === empty.record(0, 0.0, Double.POSITIVE_INFINITY, 12))
        assertTrue(empty === empty.record(0, -0.1, 0.05, 12))
    }

    @Test
    fun `an unmeasured resource has no page`() {
        val estimate = BookScreenEstimate().record(0, 0.0, 0.05, 12)
        assertNull(estimate.pageAt(1, 3))
        assertNull(estimate.pageAt(0, 0))
    }

    @Test
    fun `the two occurrences of one file are counted apart`() {
        // A reading order listing the same href twice: the second
        // occurrence is its own stretch of the book, not a
        // re-measurement of the first.
        val estimate = BookScreenEstimate()
            .record(0, 0.0, 0.10, 12)
            .record(4, 0.40, 0.50, 12)
        assertEquals(2, estimate.samples.size)
        assertEquals(120, estimate.totalScreens)
        assertEquals(1, estimate.pageAt(0, 1))
        assertEquals(49, estimate.pageAt(4, 1))
    }
}
