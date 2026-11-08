package com.chmouel.liseur.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFormatsTest {

    private val compatible = DownloadFormat("/b/compatible", "application/epub+zip", "Recommended compatible epub", 1)
    private val advanced = DownloadFormat("/b/advanced", "application/epub+zip", "Advanced epub", 2)
    private val formats = listOf(compatible, advanced)

    @Test
    fun `the catalog's preference wins when the book offers it`() {
        // Another book from the same catalog: different href, same kind of file.
        val chosen = DownloadFormats.pick(formats, advanced.copy(href = "/other/advanced").key)

        assertEquals(advanced, chosen)
    }

    @Test
    fun `a preference the book does not offer falls back to the default`() {
        assertEquals(compatible, DownloadFormats.pick(formats, "application/kepub+zip|kobo kepub epub"))
        assertEquals(compatible, DownloadFormats.pick(formats, null))
        assertNull(DownloadFormats.pick(emptyList(), advanced.key))
    }

    @Test
    fun `a chosen format without a length stays unknown`() {
        assertEquals(1L, DownloadFormats.sizeOf(compatible, 99L))
        assertNull(DownloadFormats.sizeOf(compatible.copy(sizeBytes = null), 99L))
        assertEquals(99L, DownloadFormats.sizeOf(null, 99L))
    }

    @Test
    fun `round-trips through the stored text, and stores nothing without a choice`() {
        assertEquals(formats, DownloadFormats.decode(DownloadFormats.encode(formats)))
        assertNull(DownloadFormats.encode(listOf(compatible)))
        assertEquals(emptyList<DownloadFormat>(), DownloadFormats.decode("not json"))
    }

    @Test
    fun `untitled files of one type stay separate choices that survive storage`() {
        val first = DownloadFormat("/b/one", "application/epub+zip")
        val second = DownloadFormat("/b/two", "application/epub+zip")

        val distinct = DownloadFormats.distinct(listOf(first, second, first))

        assertEquals(listOf("/b/one", "/b/two"), distinct.map { it.href })
        assertEquals(2, distinct.map { it.key }.toSet().size)
        val stored = DownloadFormats.decode(DownloadFormats.encode(distinct))
        assertEquals(distinct[1], DownloadFormats.pick(stored, distinct[1].key))
    }
}
