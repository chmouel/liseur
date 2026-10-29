package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.remote.BookProvenance
import com.chmouel.liseur.data.remote.ServerKind
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookDetailsTest {

    @Test
    fun provenanceComesFromTheRowNotTheConnectedServer() {
        assertEquals(BookProvenance.Server(ServerKind.KOMGA), BookProvenance.of("komga:abc", null))
        assertEquals(
            BookProvenance.Server(ServerKind.LISEUR_SYNC),
            BookProvenance.of("browse:7:liseur-sync:42", null),
        )
        assertEquals(BookProvenance.Opds, BookProvenance.of("custom:fp:1", null))
        assertEquals(BookProvenance.Opds, BookProvenance.of("browse:3:custom:fp:1", null))
        assertEquals(BookProvenance.Grimmory, BookProvenance.of("grimmory:12", null))
        assertEquals(
            BookProvenance.Folder("content://tree/books"),
            BookProvenance.of("content://tree/books/document/a.epub", "content://tree/books"),
        )
        assertEquals(BookProvenance.File, BookProvenance.of("content://downloads/a.epub", null))
    }

    @Test
    fun aFingerprintIsShownOnlyForTheFileOnThisDevice() {
        val row = BookFingerprintRow("calibre:1", "aa", "bb", 10, fileModifiedAt = 500, computedAt = 1)
        val book = Book(
            url = "calibre:1",
            title = "T",
            author = null,
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = null,
            downloadState = DownloadState.DOWNLOADED,
            fileModifiedAt = 500,
        )
        assertEquals(row, currentFingerprint(book, row))
        assertNull(currentFingerprint(book.copy(fileModifiedAt = 501), row))
        assertNull(currentFingerprint(book.copy(downloadState = DownloadState.REMOTE), row))
        assertNull(currentFingerprint(book, null))
    }

    @Test
    fun wcagProfilesNameTheirVersionAndLevel() {
        assertEquals("2.0" to "AA", wcagConformance("http://www.idpf.org/epub/a11y/accessibility-20170105.html#wcag-aa"))
        assertEquals("2.1" to "AA", wcagConformance("https://www.w3.org/TR/epub-a11y-11#wcag-2.1-aa"))
        assertEquals("2.2" to "AAA", wcagConformance("https://www.w3.org/TR/epub-a11y-11#wcag-2.2-aaa"))
        assertNull(wcagConformance("https://example.com/profile"))
        assertNull(wcagConformance("https://example.com/#wcag-aaaa"))
    }

    @Test
    fun aLanguageTagNamesItsLanguageOrStaysAsWritten() {
        assertEquals("French", languageName("fr", Locale.ENGLISH))
        assertEquals("Anglais (Canada)", languageName("en-CA", Locale.FRENCH))
        assertEquals("qq", languageName("qq", Locale.ENGLISH))
        assertEquals("not a tag", languageName("not a tag", Locale.ENGLISH))
    }
}
