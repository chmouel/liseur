package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesShelfTest {

    private fun book(
        title: String,
        series: String? = "Wheel of Time",
        index: Double? = null,
        author: String? = "Robert Jordan",
        finishedAt: Long? = null,
        archivedAt: Long? = null,
        state: DownloadState = DownloadState.DOWNLOADED,
    ) = Book(
        url = "https://example.test/$title",
        title = title,
        author = author,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        downloadState = state,
        finishedAt = finishedAt,
        archivedAt = archivedAt,
        seriesName = series,
        seriesIndex = index,
    )

    @Test
    fun `books without a series are not shelved`() {
        val shelves = listOf(book("Dune", series = null), book("Emma", series = "  "))
            .groupedIntoSeries()
        assertTrue(shelves.isEmpty())
    }

    @Test
    fun `two spellings of one series make one shelf`() {
        val shelves = listOf(
            book("The Eye of the World", series = "The Wheel of Time", index = 1.0),
            book("The Great Hunt", series = "Wheel of Time", index = 2.0),
        ).groupedIntoSeries()

        assertEquals(1, shelves.size)
        assertEquals(2, shelves.single().volumes.size)
    }

    @Test
    fun `volumes file by number, and the unnumbered come last`() {
        val shelves = listOf(
            book("Companion"),
            book("Two", index = 2.0),
            book("One and a half", index = 1.5),
            book("One", index = 1.0),
        ).groupedIntoSeries()

        assertEquals(
            listOf("One", "One and a half", "Two", "Companion"),
            shelves.single().volumes.map { it.book.title },
        )
    }

    @Test
    fun `the book part way through is the one to continue`() {
        val one = book("One", index = 1.0, finishedAt = 1)
        val two = book("Two", index = 2.0)
        val three = book("Three", index = 3.0)
        val shelf = listOf(one, two, three)
            .groupedIntoSeries(progressions = mapOf(three.url to 0.4))
            .single()

        assertEquals("Three", shelf.nextUp?.book?.title)
        assertTrue(shelf.nextUp!!.inProgress)
    }

    @Test
    fun `with nothing open the first unread is next`() {
        val shelf = listOf(
            book("One", index = 1.0, finishedAt = 1),
            book("Two", index = 2.0),
            book("Three", index = 3.0),
        ).groupedIntoSeries().single()

        assertEquals("Two", shelf.nextUp?.book?.title)
        assertFalse(shelf.complete)
    }

    @Test
    fun `a series read to the end has nothing next and shows its first cover`() {
        val shelf = listOf(
            book("One", index = 1.0, finishedAt = 1),
            book("Two", index = 2.0, finishedAt = 2),
        ).groupedIntoSeries().single()

        assertNull(shelf.nextUp)
        assertTrue(shelf.complete)
        assertEquals(2, shelf.finishedCount)
        assertEquals("One", shelf.cover.title)
    }

    @Test
    fun `only the numbers missing between what you have count as gaps`() {
        val shelf = listOf(
            book("Two", index = 2.0),
            book("Five", index = 5.0),
        ).groupedIntoSeries().single()

        assertEquals(listOf(3.0, 4.0), shelf.gaps)
    }

    @Test
    fun `a novella between two volumes is not a gap`() {
        val shelf = listOf(
            book("One", index = 1.0),
            book("One and a half", index = 1.5),
            book("Two", index = 2.0),
        ).groupedIntoSeries().single()

        assertTrue(shelf.gaps.isEmpty())
    }

    @Test
    fun `a single volume is missing nothing`() {
        val shelf = listOf(book("One", index = 1.0)).groupedIntoSeries().single()
        assertTrue(shelf.gaps.isEmpty())
    }

    @Test
    fun `a mis-parsed number does not produce a thousand gaps`() {
        val shelf = listOf(
            book("One", index = 1.0),
            book("Nineteen ninety-nine", index = 1999.0),
        ).groupedIntoSeries().single()

        assertTrue(shelf.gaps.isEmpty())
    }

    @Test
    fun `books still on the server count as missing`() {
        val shelf = listOf(
            book("One", index = 1.0),
            book("Two", index = 2.0, state = DownloadState.REMOTE),
        ).groupedIntoSeries().single()

        assertEquals(listOf("Two"), shelf.missing.map { it.book.title })
    }

    @Test
    fun `the shelf takes the spelling most of its volumes use`() {
        val shelf = listOf(
            book("One", series = "Wheel of Time", index = 1.0),
            book("Two", series = "Wheel of Time", index = 2.0),
            book("Three", series = "The Wheel of Time", index = 3.0),
        ).groupedIntoSeries().single()

        assertEquals("Wheel of Time", shelf.name)
        assertEquals("Robert Jordan", shelf.author)
    }

    @Test
    fun `shelves come out in name order`() {
        val shelves = listOf(
            book("A", series = "The Expanse", index = 1.0),
            book("B", series = "Dune", index = 1.0),
        ).groupedIntoSeries()

        assertEquals(listOf("Dune", "The Expanse"), shelves.map { it.name })
    }
}

class NextInSeriesTest {

    private fun book(
        title: String,
        series: String? = "Wheel of Time",
        index: Double? = null,
        finishedAt: Long? = null,
        archivedAt: Long? = null,
    ) = Book(
        url = "https://example.test/$title",
        title = title,
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        downloadState = DownloadState.DOWNLOADED,
        finishedAt = finishedAt,
        archivedAt = archivedAt,
        seriesName = series,
        seriesIndex = index,
    )

    @Test
    fun `the next number along is offered`() {
        val one = book("One", index = 1.0, finishedAt = 1)
        val library = listOf(one, book("Three", index = 3.0), book("Two", index = 2.0))

        assertEquals("Two", nextInSeries(one, library)?.title)
    }

    @Test
    fun `a gap does not stop the offer`() {
        val one = book("One", index = 1.0, finishedAt = 1)
        val library = listOf(one, book("Four", index = 4.0))

        assertEquals("Four", nextInSeries(one, library)?.title)
    }

    @Test
    fun `a book with no series has nothing next`() {
        val alone = book("Dune", series = null, finishedAt = 1)
        assertNull(nextInSeries(alone, listOf(alone, book("Two", index = 2.0))))
    }

    @Test
    fun `a book with no number has nothing next`() {
        val vague = book("Companion", finishedAt = 1)
        assertNull(nextInSeries(vague, listOf(vague, book("Two", index = 2.0))))
    }

    @Test
    fun `the last volume has nothing next`() {
        val last = book("Two", index = 2.0, finishedAt = 1)
        assertNull(nextInSeries(last, listOf(book("One", index = 1.0), last)))
    }

    @Test
    fun `an already read next volume is not offered again`() {
        val one = book("One", index = 1.0, finishedAt = 1)
        val library = listOf(one, book("Two", index = 2.0, finishedAt = 2), book("Three", index = 3.0))

        assertEquals("Three", nextInSeries(one, library)?.title)
    }

    @Test
    fun `a volume already begun is not offered as new`() {
        val one = book("One", index = 1.0, finishedAt = 1)
        val two = book("Two", index = 2.0)
        val library = listOf(one, two, book("Three", index = 3.0))

        assertEquals(
            "Three",
            nextInSeries(one, library, progressions = mapOf(two.url to 0.3))?.title,
        )
    }

    @Test
    fun `an archived volume is not offered`() {
        val one = book("One", index = 1.0, finishedAt = 1)
        val library = listOf(one, book("Two", index = 2.0, archivedAt = 9))

        assertNull(nextInSeries(one, library))
    }

    @Test
    fun `another series is not consulted`() {
        val one = book("One", index = 1.0, finishedAt = 1)
        val library = listOf(one, book("Dune Messiah", series = "Dune", index = 2.0))

        assertNull(nextInSeries(one, library))
    }
}

class SeriesShelfOrderTest {

    private fun shelf(
        series: String,
        titles: List<String>,
        addedAt: Long = 0,
        lastOpenedAt: Long? = null,
        author: String? = null,
        state: DownloadState = DownloadState.DOWNLOADED,
    ) = titles.mapIndexed { i, title ->
        Book(
            url = "https://example.test/$title",
            title = title,
            author = author,
            coverPath = null,
            source = null,
            addedAt = addedAt + i,
            lastOpenedAt = lastOpenedAt,
            downloadState = state,
            seriesName = series,
            seriesIndex = (i + 1).toDouble(),
        )
    }

    private val library = shelf("Dune", listOf("Dune", "Dune Messiah"), addedAt = 100) +
        shelf("The Expanse", listOf("Leviathan Wakes"), addedAt = 500, lastOpenedAt = 9_000)

    @Test
    fun `by title the shelves file by name`() {
        assertEquals(
            listOf("Dune", "The Expanse"),
            library.groupedIntoSeries()
                .arrangedBy(LibrarySort.TITLE, reversed = false)
                .map { it.name },
        )
    }

    @Test
    fun `reversing turns the shelves around`() {
        assertEquals(
            listOf("The Expanse", "Dune"),
            library.groupedIntoSeries()
                .arrangedBy(LibrarySort.TITLE, reversed = true)
                .map { it.name },
        )
    }

    @Test
    fun `a series speaks with its newest volume when sorting by what is new`() {
        assertEquals(
            listOf("The Expanse", "Dune"),
            library.groupedIntoSeries()
                .arrangedBy(LibrarySort.ADDED)
                .map { it.name },
        )
    }

    @Test
    fun `a series read elsewhere rises like a book does`() {
        val shelves = library.groupedIntoSeries()
        assertEquals(
            listOf("The Expanse", "Dune"),
            shelves.arrangedBy(LibrarySort.RECENT).map { it.name },
        )
        // A position that arrived from another device counts as reading,
        // exactly as it does on the plain shelf.
        assertEquals(
            listOf("Dune", "The Expanse"),
            shelves.arrangedBy(
                LibrarySort.RECENT,
                readAt = mapOf("https://example.test/Dune Messiah" to 99_000L),
            ).map { it.name },
        )
    }

    @Test
    fun `recent keeps reading ahead of downloaded and remote shelves`() {
        val books = shelf(
            "New on the server",
            listOf("Remote"),
            addedAt = 30_000,
            state = DownloadState.REMOTE,
        ) + shelf(
            "On the device",
            listOf("Downloaded"),
            addedAt = 20_000,
        ) + shelf(
            "Being read",
            listOf("Started"),
            addedAt = 10,
            lastOpenedAt = 100,
        )

        assertEquals(
            listOf("Being read", "On the device", "New on the server"),
            books.groupedIntoSeries().arrangedBy(LibrarySort.RECENT).map { it.name },
        )
    }

    @Test
    fun `author orders shelves by their authors and leaves unknown last`() {
        val books = shelf("Zed", listOf("One"), author = "Alice") +
            shelf("Able", listOf("Two"), author = "Zora") +
            shelf("Middle", listOf("Three"), author = null)

        assertEquals(
            listOf("Zed", "Able", "Middle"),
            books.groupedIntoSeries().arrangedBy(LibrarySort.AUTHOR).map { it.name },
        )
        assertEquals(
            listOf("Able", "Zed", "Middle"),
            books.groupedIntoSeries()
                .arrangedBy(LibrarySort.AUTHOR, reversed = true)
                .map { it.name },
        )
    }
}
