package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class OpenableUriTest {

    @Test
    fun `a shelved book opens through its watched folder`() {
        val parent =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments/document/primary%3ADocuments"
        val child =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments%2Fbooks/document/primary%3ADocuments%2Fbooks"
        val stored =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments/document/primary%3ADocuments%2Fbooks%2Fbook.epub"
        val book = sample(url = stored, source = child)

        assertEquals(
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments%2Fbooks/document/primary%3ADocuments%2Fbooks%2Fbook.epub",
            book.openableUri(),
        )
    }

    @Test
    fun `a hand-picked book keeps its bare document uri`() {
        val picked =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ADocuments%2Fbooks%2Fbook.epub"
        val book = sample(url = picked, source = null)

        assertEquals(picked, book.openableUri())
    }

    @Test
    fun `accented document ids rebuild without double encoding`() {
        val tree =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments/document/primary%3ADocuments"
        val stored =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ADocuments%2Fr%C3%A9sum%C3%A9.epub"
        val book = sample(url = stored, source = tree)

        assertEquals(documentId(stored), documentId(book.openableUri()!!))
    }

    @Test
    fun `a remote-only book has no openable uri`() {
        val book = sample(url = "calibre:uuid", source = null)
            .copy(downloadState = DownloadState.REMOTE)

        assertNull(book.openableUri())
    }

    private fun sample(url: String, source: String?) = Book(
        url = url,
        title = "book",
        author = null,
        coverPath = null,
        source = source,
        addedAt = 0,
        lastOpenedAt = null,
    )
}
