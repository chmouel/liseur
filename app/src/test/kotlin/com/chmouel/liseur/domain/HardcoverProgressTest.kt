package com.chmouel.liseur.domain

import com.chmouel.liseur.domain.HardcoverStep.AddAsReading
import com.chmouel.liseur.domain.HardcoverStep.LogPages
import com.chmouel.liseur.domain.HardcoverStep.MarkRead
import com.chmouel.liseur.domain.HardcoverStep.MarkReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a push to Hardcover is made of, and what it leaves alone. */
class HardcoverProgressTest {

    private val absent = HardcoverShelfState(null, null, null, null)

    private fun shelved(status: Int, readId: Long? = null, pages: Int? = null) =
        HardcoverShelfState(userBookId = 7, statusId = status, openReadId = readId, openReadPages = pages)

    private fun plan(
        remote: HardcoverShelfState,
        progression: Double?,
        finished: Boolean = false,
        editionPages: Int? = 300,
        local: ReadingStatus = if (finished) ReadingStatus.FINISHED else ReadingStatus.READING,
    ) = HardcoverProgress.plan(remote, local, progression, editionPages)

    @Test
    fun `a share of the book becomes a page of the edition`() {
        assertEquals(150, HardcoverProgress.pagesFor(0.5, 300, finished = false))
        assertEquals(0, HardcoverProgress.pagesFor(0.0, 300, finished = false))
    }

    @Test
    fun `an unfinished book never reports its last page`() {
        assertEquals(299, HardcoverProgress.pagesFor(0.999, 300, finished = false))
        assertEquals(299, HardcoverProgress.pagesFor(1.0, 300, finished = false))
        assertEquals(300, HardcoverProgress.pagesFor(0.97, 300, finished = true))
    }

    @Test
    fun `an edition with no page count has no page to report`() {
        assertNull(HardcoverProgress.pagesFor(0.5, null, finished = false))
        assertNull(HardcoverProgress.pagesFor(0.5, 0, finished = false))
    }

    @Test
    fun `a book not in the library is added as reading, then logged`() {
        assertEquals(listOf(AddAsReading, LogPages(150)), plan(absent, 0.5))
    }

    @Test
    fun `a book wanted or paused is set to reading, then logged`() {
        val expected = listOf(MarkReading, LogPages(150))
        assertEquals(expected, plan(shelved(HardcoverStatus.WANT_TO_READ), 0.5))
        assertEquals(expected, plan(shelved(HardcoverStatus.PAUSED), 0.5))
    }

    @Test
    fun `a book being read only has its pages logged`() {
        val remote = shelved(HardcoverStatus.CURRENTLY_READING, readId = 9, pages = 40)
        assertEquals(listOf(LogPages(150)), plan(remote, 0.5))
    }

    @Test
    fun `progress never goes backwards and is not resent unchanged`() {
        val remote = shelved(HardcoverStatus.CURRENTLY_READING, readId = 9, pages = 150)
        assertEquals(emptyList<HardcoverStep>(), plan(remote, 0.5))
        assertEquals(emptyList<HardcoverStep>(), plan(remote, 0.2))
    }

    @Test
    fun `finishing marks the book read and leaves the pages to the server`() {
        val remote = shelved(HardcoverStatus.CURRENTLY_READING, readId = 9, pages = 280)
        assertEquals(listOf(MarkRead), plan(remote, 0.98, finished = true))
    }

    @Test
    fun `finishing a book hardcover never saw starts it first`() {
        assertEquals(listOf(AddAsReading, MarkRead), plan(absent, 1.0, finished = true))
    }

    @Test
    fun `a book already read is not turned into a re-read by opening it`() {
        assertEquals(emptyList<HardcoverStep>(), plan(shelved(HardcoverStatus.READ), 0.1))
        assertEquals(
            emptyList<HardcoverStep>(),
            plan(shelved(HardcoverStatus.READ), 1.0, finished = true),
        )
    }

    @Test
    fun `a book put down as did-not-finish stays so`() {
        assertEquals(emptyList<HardcoverStep>(), plan(shelved(HardcoverStatus.DID_NOT_FINISH), 0.6))
    }

    @Test
    fun `a book that is unread, wanted, paused or put down says nothing`() {
        val quiet = listOf(
            ReadingStatus.READY_TO_READ,
            ReadingStatus.WANT_TO_READ,
            ReadingStatus.PAUSED,
            ReadingStatus.DID_NOT_FINISH,
        )
        for (status in quiet) {
            assertEquals(emptyList<HardcoverStep>(), plan(absent, 0.4, local = status))
        }
    }

    @Test
    fun `without a page count the status still moves`() {
        assertEquals(listOf(AddAsReading), plan(absent, 0.5, editionPages = null))
    }
}
