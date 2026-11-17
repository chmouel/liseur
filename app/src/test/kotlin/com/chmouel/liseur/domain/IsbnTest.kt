package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading an ISBN out of whatever an EPUB put in `dc:identifier`. */
class IsbnTest {

    @Test
    fun `a urn isbn is read with its isbn10 beside it`() {
        assertEquals(Isbn("9780547928227", "054792822X"), Isbn.from("urn:isbn:9780547928227"))
    }

    @Test
    fun `hyphens, spaces, case and a bare prefix do not matter`() {
        val expected = Isbn("9780547928227", "054792822X")
        assertEquals(expected, Isbn.from("978-0-547-92822-7"))
        assertEquals(expected, Isbn.from(" ISBN:978 0 547 92822 7 "))
        assertEquals(expected, Isbn.from("URN:ISBN:9780547928227"))
    }

    @Test
    fun `an isbn10 is given its isbn13`() {
        assertEquals(Isbn("9780261102217", "0261102214"), Isbn.from("isbn:0-261-10221-4"))
        assertEquals(Isbn("9780547928227", "054792822X"), Isbn.from("054792822x"))
    }

    @Test
    fun `a 979 isbn has no isbn10`() {
        assertEquals(Isbn("9791032305690", null), Isbn.from("9791032305690"))
    }

    @Test
    fun `a wrong check digit is not an isbn`() {
        assertNull(Isbn.from("9780547928228"))
        assertNull(Isbn.from("0261102215"))
    }

    @Test
    fun `thirteen digits that are not a book are not an isbn`() {
        // A valid EAN-13 check digit, but not in the 978 or 979 range.
        assertNull(Isbn.from("4006381333931"))
    }

    @Test
    fun `a uuid, a calibre id and nothing at all are not isbns`() {
        assertNull(Isbn.from("urn:uuid:2f1b0a3c-6f0e-4d0a-9d55-0a1b2c3d4e5f"))
        assertNull(Isbn.from("calibre:1234"))
        assertNull(Isbn.from(""))
        assertNull(Isbn.from(null))
    }
}
