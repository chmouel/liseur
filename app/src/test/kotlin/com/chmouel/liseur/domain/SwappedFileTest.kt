package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwappedFileTest {
    @Test
    fun `the book's own identifier is what identifies it`() {
        assertEquals(
            "urn:uuid:abc",
            workIdOf("urn:uuid:abc", "Bleak House", "Dickens"),
        )
    }

    @Test
    fun `case and stray spaces do not make it a different book`() {
        assertEquals(
            workIdOf("  URN:UUID:ABC ", null, null),
            workIdOf("urn:uuid:abc", null, null),
        )
    }

    @Test
    fun `a file with no identifier falls back to what it is called`() {
        assertEquals(
            "bleak house — dickens",
            workIdOf(null, "Bleak House", "Dickens"),
        )
    }

    @Test
    fun `an identifier every file shares is worse than no identifier`() {
        assertEquals(
            workIdOf(null, "Bleak House", "Dickens"),
            workIdOf("uuid_id", "Bleak House", "Dickens"),
        )
    }

    @Test
    fun `a file that says nothing about itself cannot be identified`() {
        assertEquals(null, workIdOf(null, null, null))
        assertEquals(null, workIdOf("   ", "  ", null))
    }

    @Test
    fun `a title alone is enough`() {
        assertEquals("bleak house", workIdOf(null, "Bleak House", null))
    }

    @Test
    fun `the same book downloaded again keeps what you had`() {
        assertTrue(isSameWork("urn:uuid:abc", "urn:uuid:abc"))
    }

    @Test
    fun `a different book at the same path is a different book`() {
        assertFalse(isSameWork("urn:uuid:abc", "urn:uuid:def"))
    }

    @Test
    fun `a book indexed before we recorded this is left alone`() {
        assertTrue(isSameWork(null, "urn:uuid:abc"))
    }

    @Test
    fun `a file that will not say what it is is left alone`() {
        assertTrue(isSameWork("urn:uuid:abc", null))
    }

    @Test
    fun `retitling a book with no identifier does count as replacing it`() {
        // The cost of being wrong this way round is losing marks on a book
        // whose file was rewritten with better metadata. The cost the other
        // way is highlights quoting sentences that are not there. Neither is
        // free; this is the one the reader can see and understand.
        assertFalse(
            isSameWork(
                workIdOf(null, "bleak house", null),
                workIdOf(null, "Great Expectations", null),
            ),
        )
    }
}
