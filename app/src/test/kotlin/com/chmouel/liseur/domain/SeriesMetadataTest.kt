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

    @Test
    fun `with nothing said by the reader the sources stand`() {
        val source = SeriesMetadata("The Expanse", 3.0, "komga-1")
        assertEquals(
            source,
            effectiveSeries(
                name = null,
                index = null,
                indexOverridden = false,
                source = source,
            ),
        )
    }

    @Test
    fun `the reader outranks the server`() {
        val filed = effectiveSeries(
            name = SeriesOverride("My Shelf", 1.0),
            index = 1.0,
            indexOverridden = true,
            source = SeriesMetadata("The Expanse", 3.0, "komga-1"),
        )
        assertEquals("My Shelf", filed.name)
        assertEquals(1.0, filed.index!!, 0.0)
    }

    @Test
    fun `taking a book out of a series is an answer and not a gap`() {
        val filed = effectiveSeries(
            name = SeriesOverride(null, null),
            index = null,
            indexOverridden = true,
            source = SeriesMetadata("The Expanse", 3.0, "komga-1"),
        )
        assertNull(filed.name)
        assertNull(filed.index)
    }

    @Test
    fun `a number without a series to belong to is dropped from an override too`() {
        val filed = effectiveSeries(
            name = SeriesOverride(null, 2.0),
            index = 2.0,
            indexOverridden = true,
            source = SeriesMetadata("The Expanse", 3.0),
        )
        assertNull(filed.index)
    }

    @Test
    fun `a dragged book keeps the name its server gave it`() {
        val filed = effectiveSeries(
            name = null,
            index = 2.0,
            indexOverridden = true,
            source = SeriesMetadata("The Expanse", 7.0, "komga-1"),
        )
        assertEquals("The Expanse", filed.name)
        assertEquals(2.0, filed.index!!, 0.0)
        assertEquals("komga-1", filed.id)
    }

    @Test
    fun `a book filed by hand does not carry its old number over`() {
        // Volume 4 of The Expanse refiled into Star Wars is not volume 4
        // of Star Wars, and which source supplied the 4 is already lost
        // by the time the merge is done.
        val filed = effectiveSeries(
            name = SeriesOverride("Star Wars", null),
            index = null,
            indexOverridden = false,
            source = SeriesMetadata("The Expanse", 4.0, "komga-1"),
        )
        assertEquals("Star Wars", filed.name)
        assertNull(filed.index)
    }

    @Test
    fun `a book filed by hand and then numbered keeps the number`() {
        val filed = effectiveSeries(
            name = SeriesOverride("Star Wars", 2.0),
            index = 2.0,
            indexOverridden = true,
            source = SeriesMetadata("The Expanse", 4.0),
        )
        assertEquals(2.0, filed.index!!, 0.0)
    }

    @Test
    fun `no series means no number, however the number was set`() {
        val filed = effectiveSeries(
            name = SeriesOverride(null, null),
            index = 3.0,
            indexOverridden = true,
            source = SeriesMetadata("The Expanse", 4.0),
        )
        assertNull(filed.name)
        assertNull(filed.index)
    }

    @Test
    fun `a source that names no series gives no number either`() {
        val filed = effectiveSeries(
            name = null,
            index = null,
            indexOverridden = false,
            source = SeriesMetadata(null, 4.0),
        )
        assertNull(filed.name)
        assertNull(filed.index)
    }
}
