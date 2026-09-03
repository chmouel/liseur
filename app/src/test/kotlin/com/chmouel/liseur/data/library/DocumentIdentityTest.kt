package com.chmouel.liseur.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIdentityTest {

    private val tree =
        "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADocuments%2Fmybooks/document/" +
            "primary%3ADocuments%2Fmybooks%2Fbook.epub"

    private val picked =
        "content://com.android.externalstorage.documents/document/" +
            "primary%3ADocuments%2Fmybooks%2Fbook.epub"

    @Test
    fun `a scanned file and the same file picked by hand agree`() {
        // Issue #147: one EPUB, two spellings, two shelf entries.
        assertEquals(documentIdentity(tree), documentIdentity(picked))
        assertTrue(sameDocument(tree, picked))
    }

    @Test
    fun `different files in the same folder differ`() {
        val other = picked.replace("book.epub", "other.epub")
        assertNotEquals(documentIdentity(picked), documentIdentity(other))
        assertFalse(sameDocument(tree, other))
    }

    @Test
    fun `the same document id under another provider differs`() {
        val elsewhere = picked.replace(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
        )
        assertNotEquals(documentIdentity(picked), documentIdentity(elsewhere))
    }

    @Test
    fun `a query or fragment is not part of what the document is`() {
        assertEquals(documentIdentity(picked), documentIdentity("$picked?page=3"))
        assertEquals(documentIdentity(picked), documentIdentity("$picked#chapter"))
    }

    @Test
    fun `escaping is compared decoded`() {
        val decoded =
            "content://com.android.externalstorage.documents/document/" +
                "primary:Documents/mybooks/book.epub"
        assertEquals(documentIdentity(picked), documentIdentity(decoded))
    }

    @Test
    fun `a malformed escape is left alone rather than losing the id`() {
        val trailing = "content://authority/document/book%"
        assertEquals("authority\u0000book%", documentIdentity(trailing))
    }

    @Test
    fun `a plus stays a plus`() {
        // URLDecoder would read this as a space and merge two documents.
        assertNotEquals(
            documentIdentity("content://authority/document/a+b.epub"),
            documentIdentity("content://authority/document/a b.epub"),
        )
    }

    @Test
    fun `urls with no document have no identity`() {
        assertNull(documentIdentity("file:///storage/emulated/0/Books/book.epub"))
        assertNull(documentIdentity("https://example.org/book.epub"))
        assertNull(documentIdentity("content://com.example.provider/books/7"))
        assertNull(documentIdentity("content:///document/x"))
        assertNull(documentIdentity("content://authority/document/"))
        assertNull(documentIdentity(""))
    }

    @Test
    fun `urls without an identity fall back to being compared as strings`() {
        val file = "file:///storage/emulated/0/Books/book.epub"
        assertTrue(sameDocument(file, file))
        assertFalse(sameDocument(file, "file:///storage/emulated/0/Books/other.epub"))
    }
}
