package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {

    private fun book(
        title: String,
        author: String? = null,
        addedAt: Long = 0,
        lastOpenedAt: Long? = null,
        downloadedAt: Long? = null,
        state: DownloadState = DownloadState.REMOTE,
    ) = Book(
        url = "https://example.test/$title",
        title = title,
        author = author,
        coverPath = null,
        source = null,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        downloadedAt = downloadedAt,
        downloadState = state,
    )

    private fun List<Book>.titles() = map { it.title }

    @Test
    fun `a leading article does not decide where a book files`() {
        assertEquals("whale", sortKey("The Whale"))
        assertEquals("chartreuse de parme", sortKey("La Chartreuse de Parme"))
        assertEquals("etranger", sortKey("L'Étranger".replace("É", "E")))
        assertEquals("etranger", sortKey("L\u2019Etranger"))
    }

    @Test
    fun `a word that merely starts with an article is left alone`() {
        assertEquals("theory of everything", sortKey("Theory of Everything"))
        assertEquals("android", sortKey("Android"))
    }

    @Test
    fun `a title that is nothing but an article still files under itself`() {
        assertEquals("the", sortKey("The"))
        assertEquals("l'", sortKey("L'"))
        assertEquals("", sortKey(null))
        assertEquals("", sortKey("   "))
    }

    @Test
    fun `title order ignores articles and case`() {
        val books = listOf(book("Zeno"), book("The Whale"), book("a Passage"))
        assertEquals(
            listOf("a Passage", "The Whale", "Zeno"),
            books.arrangedBy(LibrarySort.TITLE).titles(),
        )
        assertEquals(
            listOf("Zeno", "The Whale", "a Passage"),
            books.arrangedBy(LibrarySort.TITLE, reversed = true).titles(),
        )
    }

    @Test
    fun `author order puts unknown authors last whichever way round`() {
        val books = listOf(
            book("Nobody", author = null),
            book("Whale", author = "Herman Melville"),
            book("Dune", author = "Frank Herbert"),
            book("Blank", author = "  "),
        )
        assertEquals(
            listOf("Dune", "Whale", "Blank", "Nobody"),
            books.arrangedBy(LibrarySort.AUTHOR).titles(),
        )
        val reversed = books.arrangedBy(LibrarySort.AUTHOR, reversed = true).titles()
        assertEquals(listOf("Whale", "Dune"), reversed.take(2))
        assertEquals(setOf("Blank", "Nobody"), reversed.drop(2).toSet())
    }

    @Test
    fun `recent puts what you are reading first, then what is on the device`() {
        val books = listOf(
            book("Waiting", state = DownloadState.REMOTE, addedAt = 500),
            book("Downloaded", state = DownloadState.DOWNLOADED, downloadedAt = 100),
            book("Reading", lastOpenedAt = 50, state = DownloadState.DOWNLOADED),
        )
        assertEquals(
            listOf("Reading", "Downloaded", "Waiting"),
            books.arrangedBy(LibrarySort.RECENT).titles(),
        )
        assertEquals(
            listOf("Waiting", "Downloaded", "Reading"),
            books.arrangedBy(LibrarySort.RECENT, reversed = true).titles(),
        )
    }

    @Test
    fun `recent keeps the newest of each kind at the top`() {
        val books = listOf(
            book("Older", lastOpenedAt = 10, state = DownloadState.DOWNLOADED),
            book("Newer", lastOpenedAt = 99, state = DownloadState.DOWNLOADED),
        )
        assertEquals(listOf("Newer", "Older"), books.arrangedBy(LibrarySort.RECENT).titles())
    }

    @Test
    fun `recently added counts from when the book joined the library`() {
        val books = listOf(book("First", addedAt = 1), book("Last", addedAt = 9))
        assertEquals(listOf("Last", "First"), books.arrangedBy(LibrarySort.ADDED).titles())
        assertEquals(
            listOf("First", "Last"),
            books.arrangedBy(LibrarySort.ADDED, reversed = true).titles(),
        )
    }

    @Test
    fun `books that are otherwise equal always come out in the same places`() {
        val books = listOf(
            book("Beta", addedAt = 5),
            book("Alpha", addedAt = 5),
            book("Gamma", addedAt = 5),
        )
        val once = books.arrangedBy(LibrarySort.ADDED).titles()
        val again = books.shuffled().arrangedBy(LibrarySort.ADDED).titles()
        assertEquals(listOf("Alpha", "Beta", "Gamma"), once)
        assertEquals(once, again)
    }

    @Test
    fun `an unknown id falls back to the default order`() {
        assertEquals(LibrarySort.RECENT, LibrarySort.fromId(null))
        assertEquals(LibrarySort.RECENT, LibrarySort.fromId("nonsense"))
        for (sort in LibrarySort.entries) {
            assertEquals(sort, LibrarySort.fromId(sort.id))
        }
    }

    @Test
    fun `a book read on another device counts as started`() {
        val here = book("Opened Here", lastOpenedAt = 100)
        val elsewhere = book("Read Elsewhere")
        val untouched = book("Untouched", state = DownloadState.DOWNLOADED, downloadedAt = 900)

        val ordered = listOf(untouched, here, elsewhere).arrangedBy(
            LibrarySort.RECENT,
            readAt = mapOf(elsewhere.url to 200L),
        )

        assertEquals(
            listOf("Read Elsewhere", "Opened Here", "Untouched"),
            ordered.titles(),
        )
    }

    @Test
    fun `reading elsewhere lifts a book above one merely opened here`() {
        val stale = book("Stale", lastOpenedAt = 500)
        val fresh = book("Fresh", lastOpenedAt = 100)

        val ordered = listOf(stale, fresh).arrangedBy(
            LibrarySort.RECENT,
            readAt = mapOf(fresh.url to 900L),
        )

        assertEquals(listOf("Fresh", "Stale"), ordered.titles())
    }

    @Test
    fun `opening a book without reading it still lifts it`() {
        val opened = book("Opened", lastOpenedAt = 900)
        val read = book("Read", lastOpenedAt = 100)

        val ordered = listOf(read, opened).arrangedBy(
            LibrarySort.RECENT,
            readAt = mapOf(read.url to 500L),
        )

        assertEquals(listOf("Opened", "Read"), ordered.titles())
    }

    @Test
    fun `with no positions at all the order is unchanged`() {
        val a = book("A", lastOpenedAt = 300)
        val b = book("B", state = DownloadState.DOWNLOADED, downloadedAt = 200)
        val c = book("C", addedAt = 100)

        assertEquals(
            listOf("A", "B", "C"),
            listOf(c, b, a).arrangedBy(LibrarySort.RECENT).titles(),
        )
    }

    private fun inSeries(title: String, series: String?, index: Double?) =
        book(title).copy(seriesName = series, seriesIndex = index)

    @Test
    fun `series order runs by name and then by number`() {
        val books = listOf(
            inSeries("Dune Messiah", "Dune", 2.0),
            inSeries("Leviathan Wakes", "The Expanse", 1.0),
            inSeries("Dune", "Dune", 1.0),
        )

        assertEquals(
            listOf("Dune", "Dune Messiah", "Leviathan Wakes"),
            books.arrangedBy(LibrarySort.SERIES, reversed = false).titles(),
        )
    }

    @Test
    fun `books in no series file after the ones that are`() {
        val books = listOf(
            inSeries("Emma", null, null),
            inSeries("Dune", "Dune", 1.0),
        )

        assertEquals(
            listOf("Dune", "Emma"),
            books.arrangedBy(LibrarySort.SERIES, reversed = false).titles(),
        )
        // Reversing the shelf still does not bury the series under the
        // standalone books.
        assertEquals(
            listOf("Dune", "Emma"),
            books.arrangedBy(LibrarySort.SERIES, reversed = true).titles(),
        )
    }

    @Test
    fun `reversing turns the shelf around and never a series`() {
        val books = listOf(
            inSeries("Dune", "Dune", 1.0),
            inSeries("Dune Messiah", "Dune", 2.0),
            inSeries("Leviathan Wakes", "The Expanse", 1.0),
        )

        assertEquals(
            listOf("Leviathan Wakes", "Dune", "Dune Messiah"),
            books.arrangedBy(LibrarySort.SERIES, reversed = true).titles(),
        )
    }

    @Test
    fun `an unnumbered companion sits under the numbered volumes`() {
        val books = listOf(
            inSeries("Companion", "Dune", null),
            inSeries("Dune Messiah", "Dune", 2.0),
        )

        assertEquals(
            listOf("Dune Messiah", "Companion"),
            books.arrangedBy(LibrarySort.SERIES, reversed = false).titles(),
        )
    }
}
