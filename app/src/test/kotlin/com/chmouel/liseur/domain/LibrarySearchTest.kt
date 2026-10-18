package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {

    private fun matches(query: String) =
        matchesLibrarySearch(query, "Éloge de la Fuite", "Henri Laborit")

    @Test
    fun `an empty search matches everything`() {
        assertTrue(matches(""))
        assertTrue(matches("   "))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(matches("FUITE"))
    }

    @Test
    fun `an accent typed is found`() {
        assertTrue(matches("Éloge"))
    }

    @Test
    fun `an accent not typed is still found`() {
        // The point of folding: a phone keyboard makes é awkward, and a
        // French library is full of them.
        assertTrue(matches("eloge"))
    }

    @Test
    fun `an accent typed finds an unaccented title`() {
        assertTrue(matchesLibrarySearch("Stär", "Morning Star", "Pierce Brown"))
    }

    @Test
    fun `which sigma a Greek word ends in does not matter`() {
        // ς and σ are the same letter with two spellings; a title
        // stored with one must answer a keyboard that produced the
        // other, and lowercasing alone does not promise that.
        assertTrue(matchesLibrarySearch("κοσμοσ", "Κόσμος", null))
        assertTrue(matchesLibrarySearch("κόσμος", "ΚΟΣΜΟΣ", null))
    }

    @Test
    fun `the author is searched too`() {
        assertTrue(matches("laborit"))
    }

    @Test
    fun `words are looked for separately`() {
        // Half-remembered fragments from two different fields.
        assertTrue(matches("fuite henri"))
    }

    @Test
    fun `every word has to be somewhere`() {
        assertFalse(matches("fuite dickens"))
    }

    @Test
    fun `a word that is nowhere does not match`() {
        assertFalse(matches("dickens"))
    }

    @Test
    fun `a book with no author is searchable by title`() {
        assertTrue(matchesLibrarySearch("morning", "Morning Star", null))
        assertFalse(matchesLibrarySearch("brown", "Morning Star", null))
    }

    @Test
    fun `part of a word matches`() {
        assertTrue(matches("labo"))
    }

    @Test
    fun `a series name finds every volume of it`() {
        assertTrue(
            matchesLibrarySearch(
                "wheel of time",
                "The Great Hunt",
                "Robert Jordan",
                series = "The Wheel of Time",
            ),
        )
    }

    @Test
    fun `a series and a word from the title narrow together`() {
        assertTrue(
            matchesLibrarySearch("wheel hunt", "The Great Hunt", null, series = "Wheel of Time"),
        )
        assertFalse(
            matchesLibrarySearch("wheel dune", "The Great Hunt", null, series = "Wheel of Time"),
        )
    }

    @Test
    fun `a series name and volume index narrow to one volume`() {
        assertTrue(
            matchesLibrarySearch(
                "expanse 3",
                "Caliban's War",
                "James S. A. Corey",
                series = "The Expanse",
                seriesIndex = 3.0,
            ),
        )
        assertFalse(
            matchesLibrarySearch(
                "expanse 2",
                "Caliban's War",
                "James S. A. Corey",
                series = "The Expanse",
                seriesIndex = 3.0,
            ),
        )
    }

    @Test
    fun `a volume number is not found inside a longer one`() {
        // Asking for the third book and being handed the thirteenth,
        // the thirtieth and everything up to the thirty-ninth.
        assertFalse(
            matchesLibrarySearch(
                "expanse 3",
                "Persepolis Rising",
                "James S. A. Corey",
                series = "The Expanse",
                seriesIndex = 13.0,
            ),
        )
    }

    @Test
    fun `a half volume can still be asked for by its number`() {
        assertTrue(
            matchesLibrarySearch(
                "expanse 1.5",
                "The Butcher of Anderson Station",
                "James S. A. Corey",
                series = "The Expanse",
                seriesIndex = 1.5,
            ),
        )
    }

    @Test
    fun `a number in a title is still searchable`() {
        // The number is matched whole *as a volume*; as text it is part
        // of the title like any other word.
        assertTrue(
            matchesLibrarySearch(
                "livre 3",
                "Red Rising - Livre 3 - Morning Star",
                "Pierce Brown",
                series = "Red Rising",
                seriesIndex = 3.0,
            ),
        )
    }

    @Test
    fun `a book in no series is unaffected`() {
        assertTrue(matchesLibrarySearch("emma", "Emma", null, series = null))
        assertFalse(matchesLibrarySearch("wheel", "Emma", null, series = null))
    }

    private var nextId = 1L

    private fun book(
        title: String,
        author: String? = null,
        series: String? = null,
        index: Double? = null,
        finishedAt: Long? = null,
        archivedAt: Long? = null,
        hiddenAt: Long? = null,
        fileSeries: String? = null,
        catalogSeries: String? = null,
        seriesOverridden: Boolean = false,
    ) = Book(
        id = nextId++,
        url = "https://example.test/$title",
        title = title,
        author = author,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        seriesName = series,
        seriesIndex = index,
        finishedAt = finishedAt,
        archivedAt = archivedAt,
        hiddenAt = hiddenAt,
        fileSeriesName = fileSeries,
        catalogSeriesName = catalogSeries,
        seriesOverridden = seriesOverridden,
    )

    private fun search(query: String, vararg books: Book) =
        searchLibrary(query, books.toList()).map { it.title }

    @Test
    fun `a blank query is not a search`() {
        assertEquals(emptyList<String>(), search("  ", book("Dune")))
    }

    @Test
    fun `finished and archived books are found, hidden ones are not`() {
        // The shelf hides finished books by default and keeps archived
        // ones apart; a search looking for them must not.
        assertEquals(
            listOf("Dune", "Dune Messiah"),
            search(
                "dune",
                book("Dune", finishedAt = 1),
                book("Dune Messiah", archivedAt = 1),
                book("Dune Bootleg", hiddenAt = 1),
            ),
        )
    }

    @Test
    fun `a series name lists each volume in reading order`() {
        assertEquals(
            listOf("Leviathan Wakes", "Caliban's War", "Abaddon's Gate"),
            search(
                "expanse",
                book("Abaddon's Gate", series = "The Expanse", index = 3.0),
                book("Leviathan Wakes", series = "The Expanse", index = 1.0),
                book("Caliban's War", series = "The Expanse", index = 2.0),
            ),
        )
    }

    @Test
    fun `a title match comes before a series match and an author match`() {
        assertEquals(
            listOf("Stars Above", "Morning Star", "Golden Son", "Leviathan"),
            search(
                "star",
                book("Leviathan", author = "Ann Starling"),
                book("Golden Son", series = "Star Saga", index = 2.0),
                book("Morning Star"),
                book("Stars Above"),
            ),
        )
    }

    @Test
    fun `an author-only match ranks below a series match`() {
        assertEquals(
            listOf("Caliban's War", "Tiamat's Wrath"),
            search(
                "corey",
                book("Tiamat's Wrath", author = "James S. A. Corey"),
                book("Caliban's War", series = "Corey's World", index = 1.0),
            ),
        )
    }

    @Test
    fun `a series name with a volume number ranks as a series match`() {
        assertEquals(
            listOf("Caliban's War", "Notes"),
            search(
                "expanse 3",
                book("Notes", author = "Expanse 3 Fan Club"),
                book("Caliban's War", series = "The Expanse", index = 3.0),
                book("Abaddon's Gate", series = "The Expanse", index = 4.0),
            ),
        )
    }

    @Test
    fun `the file and catalog series names are searched`() {
        assertEquals(
            listOf("Book A", "Book B"),
            search(
                "discworld",
                book("Book A", series = "City Watch", fileSeries = "Discworld"),
                book("Book B", series = "City Watch", catalogSeries = "Discworld"),
            ),
        )
    }

    @Test
    fun `a series the reader took the book out of does not find it`() {
        assertEquals(
            emptyList<String>(),
            search(
                "discworld",
                book("Book A", fileSeries = "Discworld", seriesOverridden = true),
            ),
        )
    }

    @Test
    fun `match ranges point into the text as written`() {
        // Folding drops the accent's combining mark; the range must
        // still cover the whole of *Éloge*.
        assertEquals(listOf(0 until 5), searchMatchRanges("Éloge de la Fuite", "eloge"))
    }

    @Test
    fun `overlapping query words make one range`() {
        assertEquals(listOf(4 until 11), searchMatchRanges("The Expanse", "ex expanse"))
    }
}
