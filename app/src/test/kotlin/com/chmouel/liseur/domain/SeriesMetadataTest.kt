package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesMetadataTest {

    private val fromFeed = SeriesMetadata(name = "Wheel of Time", index = 3.0, id = "s-1")
    private val fromFile = SeriesMetadata(name = "The Wheel of Time", index = 4.0)

    @Test
    fun `the catalog wins where it has something to say`() {
        val merged = mergeSeries(catalog = fromFeed, file = fromFile)
        assertEquals("Wheel of Time", merged.name)
        assertEquals(3.0, merged.index!!, 0.0)
        assertEquals("s-1", merged.id)
    }

    @Test
    fun `the file fills in what the catalog left out`() {
        val merged = mergeSeries(
            catalog = SeriesMetadata(name = "Wheel of Time"),
            file = SeriesMetadata(index = 3.0),
        )
        assertEquals("Wheel of Time", merged.name)
        assertEquals(3.0, merged.index!!, 0.0)
    }

    @Test
    fun `a number with no series to belong to is dropped`() {
        val merged = mergeSeries(catalog = SeriesMetadata.None, file = SeriesMetadata(index = 3.0))
        assertNull(merged.name)
        assertNull(merged.index)
        assertTrue(merged.isEmpty)
    }

    @Test
    fun `unlinking takes the server id and leaves the series`() {
        val merged = mergeSeries(catalog = SeriesMetadata.None, file = fromFile)
        assertEquals("The Wheel of Time", merged.name)
        assertEquals(4.0, merged.index!!, 0.0)
        assertNull(merged.id)
    }

    @Test
    fun `two sources that know nothing say nothing`() {
        assertTrue(mergeSeries(SeriesMetadata.None, SeriesMetadata.None).isEmpty)
    }
}
