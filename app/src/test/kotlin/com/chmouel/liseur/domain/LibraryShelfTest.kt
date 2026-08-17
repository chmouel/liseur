package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryShelfTest {

    private fun book(
        title: String,
        series: String? = null,
        index: Double? = null,
        author: String? = "Someone",
        addedAt: Long = 0,
        lastOpenedAt: Long? = null,
        state: DownloadState = DownloadState.DOWNLOADED,
    ) = Book(
        url = "https://example.test/$title",
        title = title,
        author = author,
        coverPath = null,
        source = null,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        downloadState = state,
        seriesName = series,
        seriesIndex = index,
    )

    private fun List<ShelfEntry>.names(): List<String> = map {
        when (it) {
            is ShelfEntry.Single -> it.book.title
            is ShelfEntry.Pile -> it.shelf.name
        }
    }

    private fun shelves(books: List<Book>): List<SeriesShelf> =
        books.groupedIntoSeries().worthShowing()

    @Test
    fun `a series files between the standalones under its name`() {
        val books = listOf(
            book("Anna Karenina"),
            book("Leviathan Wakes", series = "The Expanse", index = 1.0),
            book("Caliban's War", series = "The Expanse", index = 2.0),
            book("Zorba the Greek"),
        )
        val entries = mixedShelf(books, shelves(books), LibrarySort.TITLE)

        assertEquals(
            listOf("Anna Karenina", "The Expanse", "Zorba the Greek"),
            entries.names(),
        )
        assertTrue(entries[1] is ShelfEntry.Pile)
    }

    @Test
    fun `a book whose series is on the shelf is folded into its pile`() {
        val books = listOf(
            book("Leviathan Wakes", series = "The Expanse", index = 1.0),
            book("Caliban's War", series = "The Expanse", index = 2.0),
        )
        val entries = mixedShelf(books, shelves(books), LibrarySort.TITLE)

        assertEquals(1, entries.size)
        assertTrue(entries.single() is ShelfEntry.Pile)
    }

    @Test
    fun `a book of a series too small to show stays a single`() {
        val books = listOf(
            book("Dune", series = "Dune Chronicles", index = 1.0),
            book("Emma"),
        )
        // No shelf worth showing: Dune Chronicles has one volume.
        val entries = mixedShelf(books, shelves(books), LibrarySort.TITLE)

        assertEquals(listOf("Dune", "Emma"), entries.names())
        assertTrue(entries.all { it is ShelfEntry.Single })
    }

    @Test
    fun `title order reversed reads back to front`() {
        val books = listOf(
            book("Anna Karenina"),
            book("Leviathan Wakes", series = "The Expanse", index = 1.0),
            book("Caliban's War", series = "The Expanse", index = 2.0),
            book("Zorba the Greek"),
        )
        val entries = mixedShelf(books, shelves(books), LibrarySort.TITLE, reversed = true)

        assertEquals(
            listOf("Zorba the Greek", "The Expanse", "Anna Karenina"),
            entries.names(),
        )
    }

    @Test
    fun `recent order lifts the pile with the volume being read`() {
        val books = listOf(
            book("Anna Karenina", lastOpenedAt = 50),
            book("Leviathan Wakes", series = "The Expanse", index = 1.0, lastOpenedAt = 100),
            book("Caliban's War", series = "The Expanse", index = 2.0),
            book("Zorba the Greek", state = DownloadState.REMOTE),
        )
        val entries = mixedShelf(books, shelves(books), LibrarySort.RECENT)

        assertEquals(
            listOf("The Expanse", "Anna Karenina", "Zorba the Greek"),
            entries.names(),
        )
    }

    @Test
    fun `added order dates a pile by its newest volume`() {
        val books = listOf(
            book("Anna Karenina", addedAt = 500),
            book("Leviathan Wakes", series = "The Expanse", index = 1.0, addedAt = 100),
            book("Caliban's War", series = "The Expanse", index = 2.0, addedAt = 900),
        )
        val entries = mixedShelf(books, shelves(books), LibrarySort.ADDED)

        assertEquals(listOf("The Expanse", "Anna Karenina"), entries.names())
    }

    @Test
    fun `author order files the authorless last`() {
        val books = listOf(
            book("Orphan", author = null),
            book("Leviathan Wakes", series = "The Expanse", index = 1.0, author = "James Corey"),
            book("Caliban's War", series = "The Expanse", index = 2.0, author = "James Corey"),
            book("Anna Karenina", author = "Leo Tolstoy"),
        )
        val entries = mixedShelf(books, shelves(books), LibrarySort.AUTHOR)

        assertEquals(
            listOf("The Expanse", "Anna Karenina", "Orphan"),
            entries.names(),
        )
    }

    @Test
    fun `series order puts the seriesless last and keeps small series filed`() {
        val books = listOf(
            book("Emma"),
            book("Leviathan Wakes", series = "The Expanse", index = 1.0),
            book("Caliban's War", series = "The Expanse", index = 2.0),
            book("Dune", series = "Dune Chronicles", index = 1.0),
        )
        val entries = mixedShelf(books, shelves(books), LibrarySort.SERIES)

        assertEquals(listOf("Dune", "The Expanse", "Emma"), entries.names())
    }

    @Test
    fun `a filtered-out volume does not resurface as a single`() {
        val all = listOf(
            book("Leviathan Wakes", series = "The Expanse", index = 1.0),
            book("Caliban's War", series = "The Expanse", index = 2.0),
        )
        // The filter kept only one volume, but the pile is still shown:
        // the survivor must fold into it, not stand beside it.
        val entries = mixedShelf(all.take(1), shelves(all), LibrarySort.TITLE)

        assertEquals(1, entries.size)
        assertTrue(entries.single() is ShelfEntry.Pile)
    }

    @Test
    fun `grid keys are distinct across kinds`() {
        val books = listOf(
            book("Emma"),
            book("Leviathan Wakes", series = "The Expanse", index = 1.0),
            book("Caliban's War", series = "The Expanse", index = 2.0),
        )
        val entries = mixedShelf(books, shelves(books), LibrarySort.TITLE)

        assertEquals(entries.size, entries.map { it.gridKey }.toSet().size)
    }
}
