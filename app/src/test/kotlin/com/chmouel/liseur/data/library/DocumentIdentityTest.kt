package com.chmouel.liseur.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun `utf-8 escapes decode to the correct characters`() {
        val encoded = "content://authority/document/books%2Fr%C3%A9sum%C3%A9.epub"
        assertEquals("books/résumé.epub", documentId(encoded))
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

    @Test
    fun `a book under a child folder is held by the most specific tree`() {
        val parent =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments/document/primary%3ADocuments"
        val child =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments%2Fbooks/document/primary%3ADocuments%2Fbooks"
        val book =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments/document/primary%3ADocuments%2Fbooks%2Fbook.epub"

        assertEquals(child, folderContainingDocument(book, listOf(parent, child)))
        assertEquals(parent, folderContainingDocument(book, listOf(parent)))
        assertNull(folderContainingDocument(book, emptyList()))
    }

    @Test
    fun `a tree-only folder uri still holds its books`() {
        val parent = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        val child = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fbooks"
        val book =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ADocuments%2Fbooks%2Fbook.epub"

        assertEquals(child, folderContainingDocument(book, listOf(parent, child)))
        assertEquals(parent, folderContainingDocument(book, listOf(parent)))
    }

    @Test
    fun `a sibling folder with a shared prefix does not adopt a book`() {
        // "Books2" starts with "Books", but it is not inside it. Matching
        // on the bare prefix would rehome the book onto the wrong tree,
        // and removing its real folder would then leave it pointing at a
        // file it cannot open.
        val books = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
        val books2 = "content://com.android.externalstorage.documents/tree/primary%3ABooks2"
        val book =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ABooks2%2Fbook.epub"

        assertEquals(books2, folderContainingDocument(book, listOf(books, books2)))
        assertNull(folderContainingDocument(book, listOf(books)))
    }

    @Test
    fun `folders from another provider do not adopt a book`() {
        val book =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ADocuments%2Fbooks%2Fbook.epub"
        val otherProvider =
            "content://com.other.provider/tree/books/document/books"

        assertNull(folderContainingDocument(book, listOf(otherProvider)))
    }

    @Test
    fun `a provider's own answer is taken over the document ids`() {
        // The ids say the book is under the parent too, but the provider
        // is the authority on its own tree and it named the child.
        val parent = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
        val child = "content://com.android.externalstorage.documents/tree/primary%3ABooks%2FSF"
        val book =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ABooks%2FSF%2Fbook.epub"

        assertEquals(child, askHoldingFolder(book, listOf(parent, child)) { it == child }.held)
    }

    @Test
    fun `a folder that will not answer is reported rather than denied`() {
        // A refusal must not read as a "no": the caller walks the tree
        // instead, and only guesses from the ids if that fails too.
        val folder = "content://com.example.cloud/tree/drive%3Abooks"
        val book = "content://com.example.cloud/document/drive%3Abooks%2Fbook.epub"

        val answer = askHoldingFolder(book, listOf(folder)) { null }

        assertNull(answer.held)
        assertEquals(listOf(folder), answer.unanswered)
    }

    @Test
    fun `one provider saying no does not answer for another that refused`() {
        // Survivors can span two authorities. The local provider answers
        // for its own tree and says the book is not there, which is true
        // and says nothing at all about the cloud tree that would not be
        // asked. Reading that pair as a "no" deletes a shelved book.
        val local = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
        val cloud = "content://com.example.cloud/tree/drive%3Abooks"
        val book = "content://com.example.cloud/document/drive%3Abooks%2Fbook.epub"

        val answer = askHoldingFolder(book, listOf(local, cloud)) { folder ->
            if (folder == local) false else null
        }

        // The local tree is not even a candidate: different provider.
        assertNull(answer.held)
        assertEquals(listOf(cloud), answer.unanswered)
    }

    @Test
    fun `a folder that answered no is not second-guessed`() {
        // The book's ids sit under this tree, but the provider said no:
        // the file was moved or deleted and only the stale row remains.
        val folder = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
        val book =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ABooks%2Fbook.epub"

        val answer = askHoldingFolder(book, listOf(folder)) { false }

        assertNull(answer.held)
        assertEquals(emptyList<String>(), answer.unanswered)
    }

    @Test
    fun `a folder from another provider is never even asked`() {
        // Document ids are unique within one authority and meaningless
        // outside it. Asking the other provider about this book's id asks
        // it about whatever it keeps under that id, and a "yes" rehomes
        // the row onto a different file.
        val cloud = "content://com.example.cloud/tree/drive%3ABooks"
        val book =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ABooks%2Fbook.epub"

        val asked = mutableListOf<String>()
        val answer = askHoldingFolder(book, listOf(cloud)) { folder ->
            asked += folder
            true
        }

        assertNull(answer.held)
        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<String>(), answer.unanswered)
    }

    @Test
    fun `a byte that is not text is not confused with a document named after it`() {
        // `%FF` is a byte no UTF-8 decode accepts; `%25FF` is a document
        // genuinely called "%FF". Spelling the first as the second made
        // two different files one identity, and folder removal deletes
        // by identity.
        val raw = "content://com.example/document/%FF.epub"
        val named = "content://com.example/document/%25FF.epub"

        assertEquals("com.example\u0000%FF.epub", documentIdentity(named))
        assertNotNull(documentIdentity(raw))
        assertNotEquals(documentIdentity(raw), documentIdentity(named))
    }

    @Test
    fun `an id carrying such a byte is not handed back to the framework`() {
        // Rebuilding a URI from it would re-encode the placeholder and
        // name some other document. Comparing it is still exact, so only
        // the addressing is refused.
        assertNull(documentId("content://com.example/document/%FF.epub"))
        assertEquals("%FF.epub", documentId("content://com.example/document/%25FF.epub"))
    }

    @Test
    fun `a distinct malformed escape keeps its own identity`() {
        // Decoding leniently turns every malformed byte run into U+FFFD,
        // which would make these two the same book on the way to
        // deleting one of them.
        val one = "content://com.example/document/%FF.epub"
        val two = "content://com.example/document/%FE.epub"

        assertNotNull(documentIdentity(one))
        assertNotEquals(documentIdentity(one), documentIdentity(two))
    }

    @Test
    fun `a valid multi-byte escape still decodes`() {
        // "e" + combining acute, as UTF-8, so the strict decoder has to
        // accept a genuine multi-byte run rather than rejecting the lot.
        val url = "content://com.example/document/caf%C3%A9.epub"

        assertEquals("com.example\u0000caf\u00e9.epub", documentIdentity(url))
    }
}
