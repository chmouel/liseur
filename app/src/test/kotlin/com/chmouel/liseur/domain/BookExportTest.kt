package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookExportTest {
    @Test
    fun `names use the title and optional author`() {
        assertEquals("A Book - An Author.epub", bookExportFileName("A Book", "An Author"))
        assertEquals("A Book.epub", bookExportFileName(" A Book ", "  "))
        assertEquals("book.epub", bookExportFileName("...", null))
    }

    @Test
    fun `names preserve Unicode and cannot introduce paths or control characters`() {
        assertEquals("Été _ hiver - Zoë.epub", bookExportFileName("Été / hiver", "Zoë"))
        val name = bookExportFileName("../book\\name:\n?*\"<>|\u0085\u009f", null)
        assertFalse(name.any { it.isISOControl() || it in "/\\:*?\"<>|" })
        assertFalse(name.startsWith('.'))
    }

    @Test
    fun `long names are limited in UTF8 without splitting characters`() {
        val name = bookExportFileName("📚é".repeat(100), "Author")
        val stem = name.removeSuffix(".epub")
        assertTrue(stem.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(stem, stem.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertTrue(name.endsWith(".epub"))
    }
}
