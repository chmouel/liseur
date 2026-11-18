package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderNarrateEntryTest {

    private fun entry(
        available: Boolean = true,
        reflowable: Boolean = true,
        configured: Boolean = true,
    ) = ReaderNarrateEntry.of(available, reflowable, configured)

    @Test
    fun `a set-up voice reads the page`() {
        assertEquals(ReaderNarrateEntry.Start, entry())
    }

    @Test
    fun `nothing set up opens the voice sheet`() {
        assertEquals(ReaderNarrateEntry.SetUp, entry(configured = false))
    }

    @Test
    fun `hidden without read aloud or for fixed layout`() {
        assertNull(entry(available = false))
        assertNull(entry(available = false, configured = false))
        assertNull(entry(reflowable = false))
        assertNull(entry(reflowable = false, configured = false))
    }
}
