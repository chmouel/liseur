package com.chmouel.liseur.data.grimmory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrimmoryIdTest {

    @Test
    fun `an ordinary id is kept`() {
        assertEquals("1", GrimmoryId.parse("1"))
        assertEquals("4207", GrimmoryId.parse("4207"))
    }

    @Test
    fun `nothing that could steer a request survives`() {
        // These are the ones that matter: the id becomes a URL path
        // segment, and a parser that decoded `%2E%2E` before resolving
        // dot segments would leave no escaping that helps.
        assertNull(GrimmoryId.parse(".."))
        assertNull(GrimmoryId.parse("."))
        assertNull(GrimmoryId.parse("/"))
        assertNull(GrimmoryId.parse("1/../2"))
        assertNull(GrimmoryId.parse("../../etc/passwd"))
        assertNull(GrimmoryId.parse("1?x=2"))
        assertNull(GrimmoryId.parse("1#top"))
        assertNull(GrimmoryId.parse("1 2"))
    }

    @Test
    fun `an id that is not a plain positive number is refused`() {
        assertNull(GrimmoryId.parse(null))
        assertNull(GrimmoryId.parse(""))
        assertNull(GrimmoryId.parse("0"))
        assertNull(GrimmoryId.parse("01"))
        assertNull(GrimmoryId.parse("-1"))
        assertNull(GrimmoryId.parse("1.0"))
        assertNull(GrimmoryId.parse("1e3"))
        assertNull(GrimmoryId.parse("abc"))
        assertNull(GrimmoryId.parse("12abc"))
    }

    @Test
    fun `a number too large for Grimmory to hold is refused`() {
        // Passes the pattern and looks entirely plausible, but no row
        // has that id, so carrying it only buys a puzzling 404 later.
        assertEquals("9223372036854775807", GrimmoryId.parse("9223372036854775807"))
        assertNull(GrimmoryId.parse("9223372036854775808"))
        assertNull(GrimmoryId.parse("99999999999999999999999999"))
    }
}
