package com.chmouel.liseur.reader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How many pages the footer says are left before the next chapter.
 *
 * The count is only as trustworthy as the boundary it counts to, and
 * the book is the one that draws that boundary. Where it has drawn
 * none, there is nothing to count to and the footer says so by saying
 * nothing.
 */
class PagesLeftInChapterTest {

    private val chapter = BookChapter(
        title = "The Sign of Four",
        firstPosition = 40,
        lastPosition = 58,
    )

    @Test
    fun `counts the positions after the current one`() {
        assertEquals(18, pagesLeftInChapter(chapter, 40))
        assertEquals(1, pagesLeftInChapter(chapter, 57))
    }

    @Test
    fun `the chapter's last position has none left`() {
        assertEquals(0, pagesLeftInChapter(chapter, 58))
    }

    @Test
    fun `a position outside the chapter's range counts nothing`() {
        // Readium's resource lookup and the position range can disagree on
        // a resource that shares its href with another one in the reading
        // order. Claiming the last page of that wrong chapter is false,
        // whichever side of the range the position falls on.
        assertNull(pagesLeftInChapter(chapter, 61))
        assertNull(pagesLeftInChapter(chapter, 39))
    }

    @Test
    fun `a one-position chapter is its own last page`() {
        val single = BookChapter(title = "Colophon", firstPosition = 7, lastPosition = 7)
        assertEquals(0, pagesLeftInChapter(single, 7))
    }

    @Test
    fun `a nameless chapter counts nothing`() {
        // A book with no table of contents comes out of BookPositions
        // as one untitled chapter spanning the whole text. Counting to
        // the end of that is counting to the end of the book, which is
        // not what the label says.
        assertNull(pagesLeftInChapter(chapter.copy(title = null), 40))
    }

    @Test
    fun `no chapter at all counts nothing`() {
        assertNull(pagesLeftInChapter(null, 40))
    }
}
