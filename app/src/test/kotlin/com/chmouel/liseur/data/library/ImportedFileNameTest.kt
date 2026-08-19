package com.chmouel.liseur.data.library

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a book brought in from another app is called once it is in the
 * app's own storage.
 *
 * The name is the whole of the de-duplication: nothing records that a
 * book has been imported before, so opening the same file twice has to
 * arrive at the same path by itself.
 */
class ImportedFileNameTest {

    @Test
    fun `the same bytes always land on the same name`() {
        assertEquals(importedFileName(digestOf("a book")), importedFileName(digestOf("a book")))
    }

    @Test
    fun `different books do not collide`() {
        assertNotEquals(
            importedFileName(digestOf("one book")),
            importedFileName(digestOf("another book")),
        )
    }

    @Test
    fun `the name is an epub`() {
        assertTrue(importedFileName(digestOf("a book")).endsWith(".epub"))
    }

    /**
     * The digest is written as hex, so nothing in a file's contents can
     * put a separator or a dot into the path it is stored at.
     */
    @Test
    fun `the name is hex and nothing else`() {
        val name = importedFileName(digestOf("a book")).removeSuffix(".epub")
        assertEquals(64, name.length)
        assertTrue(name.all { it in "0123456789abcdef" })
    }

    @Test
    fun `a leading zero byte is not swallowed`() {
        assertTrue(importedFileName(byteArrayOf(0, 0, 15)).startsWith("00000f"))
    }

    private fun digestOf(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
}
