package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataSanitizerTest {

    @Test
    fun `sanitizeAuthor formats piped Calibre names correctly`() {
        assertEquals("Philip K. Dick", sanitizeAuthor("Dick|Philip K."))
        assertEquals("mEm Inc", sanitizeAuthor("Inc| mEm"))
        assertEquals("Author A, Author B, Author C", sanitizeAuthor("Author A|Author B|Author C"))
        assertEquals("James Clavell", sanitizeAuthor("James Clavell"))
        assertNull(sanitizeAuthor(null))
        assertNull(sanitizeAuthor("   "))
    }

    @Test
    fun `sanitizeTitle removes repeated subtitle phrases`() {
        assertEquals("The Warehouse: A Novel", sanitizeTitle("The Warehouse: A Novel: A Novel"))
        assertEquals("Shogun - Intégrale", sanitizeTitle("Shogun - Intégrale"))
        assertEquals("Spinoza: L'homme qui a tué Dieu", sanitizeTitle("Spinoza: L'homme qui a tué Dieu"))
    }
}
