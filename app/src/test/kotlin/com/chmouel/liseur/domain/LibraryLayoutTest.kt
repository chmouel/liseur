package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutTest {
    private var nextId = 1L

    private fun book(
        title: String,
        series: String? = null,
        index: Double? = null,
        author: String? = null,
        addedAt: Long = 0,
        openedAt: Long? = null,
        finished: Boolean = false,
        archived: Boolean = false,
        pages: Int? = null,
        size: Long? = null,
    ) = Book(
        id = nextId++,
        url = "https://example.test/$title",
        title = title,
        author = author,
        coverPath = null,
        source = null,
        addedAt = addedAt,
        lastOpenedAt = openedAt,
        finishedAt = if (finished) 1 else null,
        archivedAt = if (archived) 1 else null,
        seriesName = series,
        seriesIndex = index,
        remotePageCount = pages,
        sizeBytes = size,
    )

    private fun titles(shelf: Shelf) = shelf.books.map { it.title }

    @Test
    fun `series shelves sort by volume and lone books go on the last shelf`() {
        val books = listOf(
            book("Lone", series = "Solo", index = 1.0),
            book("Dune 2", series = "Dune", index = 2.0),
            book("Loose"),
            book("Dune 1", series = "Dune", index = 1.0),
            book("Dune ?", series = "Dune"),
        )
        val shelves = shelvesOf(books, ShelfGrouping.SERIES, setOf("dune", "solo")) { LibraryFilterOption.UNREAD }

        assertEquals(listOf(ShelfLabel.Series("Dune", "dune"), ShelfLabel.Other), shelves.map { it.label })
        assertEquals(listOf("Dune 1", "Dune 2", "Dune ?"), titles(shelves[0]))
        assertEquals(listOf("Lone", "Loose"), titles(shelves[1]))
    }

    @Test
    fun `a series the library does not show is not a shelf`() {
        val books = listOf(book("A", series = "Hidden"), book("B", series = "Hidden"))
        val shelves = shelvesOf(books, ShelfGrouping.SERIES, emptySet()) { LibraryFilterOption.UNREAD }

        assertEquals(listOf(ShelfLabel.Other), shelves.map { it.label })
    }

    @Test
    fun `author shelves fold case, include single-book authors, and follow the library order`() {
        val books = listOf(
            book("B1", author = "Banks"),
            book("L1", author = "Le Guin"),
            book("B2", author = "banks "),
            book("S1", author = "Solo"),
            book("Anon"),
        )
        val shelves = shelvesOf(books, ShelfGrouping.AUTHOR, emptySet()) { LibraryFilterOption.UNREAD }

        assertEquals(
            listOf(listOf("B1", "B2"), listOf("L1"), listOf("S1"), listOf("Anon")),
            shelves.map(::titles),
        )
        assertEquals(ShelfLabel.Other, shelves.last().label)
    }

    @Test
    fun `planks wrap a lone other-books shelf into multi-row planks without a redundant header`() {
        val books = (1..7).map { book("Book $it") }
        val shelves = shelvesOf(books, ShelfGrouping.SERIES, emptySet()) { LibraryFilterOption.UNREAD }
        val planks = planksOf(shelves, columns = 3, packSmallShelves = false)

        assertEquals(3, planks.size)
        assertEquals(listOf(3, 3, 1), planks.map { it.segments.single().books.size })
        assertTrue(planks.all { it.segments.single().label == null })
    }

    @Test
    fun `planks pack small author shelves side by side up to the plank width`() {
        val books = listOf(
            book("A1", author = "Alpha"),
            book("B1", author = "Beta"),
            book("B2", author = "Beta"),
            book("G1", author = "Gamma"),
        )
        val shelves = shelvesOf(books, ShelfGrouping.AUTHOR, emptySet()) { LibraryFilterOption.UNREAD }
        val planks = planksOf(shelves, columns = 3, packSmallShelves = true)

        assertEquals(2, planks.size)
        assertEquals(listOf("Alpha", "Beta"), planks[0].segments.map { (it.label as ShelfLabel.Author).name })
        assertEquals(listOf(1, 2), planks[0].segments.map { it.books.size })
        assertEquals(listOf("Gamma"), planks[1].segments.map { (it.label as ShelfLabel.Author).name })
    }

    @Test
    fun `status shelves put reading first and skip empty states`() {
        val books = listOf(book("done", finished = true), book("new"), book("going"))
        val shelves = shelvesOf(books, ShelfGrouping.STATUS, emptySet()) {
            when (it.title) {
                "done" -> LibraryFilterOption.FINISHED
                "going" -> LibraryFilterOption.IN_PROGRESS
                else -> LibraryFilterOption.UNREAD
            }
        }

        assertEquals(
            listOf(LibraryFilterOption.IN_PROGRESS, LibraryFilterOption.UNREAD, LibraryFilterOption.FINISHED),
            shelves.map { (it.label as ShelfLabel.Status).state },
        )
    }

    @Test
    fun `home strips leave out the hero, finished and archived books`() {
        val hero = book("hero", openedAt = 50)
        val books = listOf(
            hero,
            book("older", openedAt = 10),
            book("newer", openedAt = 20),
            book("done", openedAt = 30, finished = true),
            book("boxed", openedAt = 40, archived = true),
            book("fresh", addedAt = 5),
            book("fresher", addedAt = 9),
            book("elsewhere", addedAt = 20),
            book("marked", addedAt = 30),
        )
        // Read on another device, so never opened here; marked finished
        // without being opened.
        val home = homeSectionsOf(books, emptyList(), current = hero.url) {
            when {
                it.finished || it.title == "marked" -> LibraryFilterOption.FINISHED
                it.title == "elsewhere" -> LibraryFilterOption.IN_PROGRESS
                else -> LibraryFilterOption.UNREAD
            }
        }

        assertEquals(listOf("newer", "older", "elsewhere"), home.recentlyOpened.map { it.title })
        assertEquals(listOf("fresher", "fresh"), home.newBooks.map { it.title })
    }

    @Test
    fun `home lists only series begun and not finished, most recent first`() {
        fun shelf(key: String, vararg volumes: Book): SeriesShelf {
            val vs = volumes.map { SeriesVolume(it, progression = null) }
            return SeriesShelf(key, key, null, vs, nextUp = vs.firstOrNull { !it.finished }, gaps = emptyList())
        }
        val untouched = shelf("untouched", book("u1"), book("u2"))
        val done = shelf("done", book("d1", finished = true), book("d2", finished = true))
        val early = shelf("early", book("e1", finished = true, openedAt = 10), book("e2"))
        val late = shelf("late", book("l1", finished = true, openedAt = 30), book("l2"))

        val home = homeSectionsOf(emptyList(), listOf(untouched, done, early, late), current = null) {
            LibraryFilterOption.UNREAD
        }

        assertEquals(listOf("late", "early"), home.seriesUnderway.map { it.key })
    }

    @Test
    fun `tracks use page counts when any book has one and never mix in sizes`() {
        val long = book("long", pages = 400)
        val short = book("short", pages = 100)
        val sized = book("sized", size = 9_000_000)
        val lengths = trackLengths(listOf(long, short, sized))

        assertEquals(1f, lengths.getValue(long.url), 0.0001f)
        assertEquals(SHORTEST_TRACK + (1f - SHORTEST_TRACK) * 0.5f, lengths.getValue(short.url), 0.0001f)
        assertTrue(sized.url !in lengths)
    }

    @Test
    fun `tracks fall back to file size and are empty with nothing to measure`() {
        val big = book("big", size = 4_000)
        val small = book("small", size = 1_000)

        assertEquals(SHORTEST_TRACK + (1f - SHORTEST_TRACK) * 0.5f, trackLengths(listOf(big, small)).getValue(small.url), 0.0001f)
        assertTrue(trackLengths(listOf(book("bare"))).isEmpty())
    }

    @Test
    fun `style ids round trip and unknown ids fall back to classic`() {
        LibraryStyle.entries.forEach { assertEquals(it, LibraryStyle.fromId(it.id)) }
        assertEquals(LibraryStyle.CLASSIC, LibraryStyle.fromId("bogus"))
    }
}
