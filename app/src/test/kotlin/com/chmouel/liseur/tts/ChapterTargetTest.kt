package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ChapterTargetTest {
    private val readingOrder = listOf("ch1.xhtml", "ch2.xhtml", "ch3.xhtml").map { Link(href = Url(it)!!) }

    @Test
    fun `forward opens the next resource`() {
        assertEquals(readingOrder[1], ChapterTarget.of(readingOrder, Url("ch1.xhtml")!!, atStart = false, forward = true))
        assertEquals(readingOrder[2], ChapterTarget.of(readingOrder, Url("ch2.xhtml")!!, atStart = true, forward = true))
    }

    @Test
    fun `forward at the last resource goes nowhere`() {
        assertNull(ChapterTarget.of(readingOrder, Url("ch3.xhtml")!!, atStart = false, forward = true))
    }

    @Test
    fun `back starts the resource being read again`() {
        assertEquals(readingOrder[1], ChapterTarget.of(readingOrder, Url("ch2.xhtml")!!, atStart = false, forward = false))
    }

    @Test
    fun `back at the first sentence of a resource opens the one before`() {
        assertEquals(readingOrder[0], ChapterTarget.of(readingOrder, Url("ch2.xhtml")!!, atStart = true, forward = false))
    }

    @Test
    fun `back at the first resource starts the book again, or goes nowhere from its first sentence`() {
        assertEquals(readingOrder[0], ChapterTarget.of(readingOrder, Url("ch1.xhtml")!!, atStart = false, forward = false))
        assertNull(ChapterTarget.of(readingOrder, Url("ch1.xhtml")!!, atStart = true, forward = false))
    }

    @Test
    fun `a book of one resource has no chapter to move to`() {
        val single = listOf(Link(href = Url("only.xhtml")!!))
        assertNull(ChapterTarget.of(single, Url("only.xhtml")!!, atStart = false, forward = true))
        assertNull(ChapterTarget.of(single, Url("only.xhtml")!!, atStart = true, forward = false))
        assertEquals(single[0], ChapterTarget.of(single, Url("only.xhtml")!!, atStart = false, forward = false))
    }

    @Test
    fun `a resource outside the reading order goes nowhere`() {
        val outside = Url("notes.xhtml")!!
        assertNull(ChapterTarget.of(readingOrder, outside, atStart = false, forward = true))
        assertNull(ChapterTarget.of(readingOrder, outside, atStart = true, forward = false))
    }

    @Test
    fun `an empty reading order goes nowhere`() {
        assertNull(ChapterTarget.of(emptyList(), Url("ch1.xhtml")!!, atStart = false, forward = true))
    }
}
