package com.chmouel.liseur.domain

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
    fun `a closed search bar leaves the shelf alone`() {
        assertTrue(
            survivesLibrarySearch(
                query = "dickens",
                searchActive = false,
                title = "Morning Star",
                author = "Pierce Brown",
            ),
        )
    }

    @Test
    fun `an open search bar narrows the shelf`() {
        assertFalse(
            survivesLibrarySearch(
                query = "dickens",
                searchActive = true,
                title = "Morning Star",
                author = "Pierce Brown",
            ),
        )
        assertTrue(
            survivesLibrarySearch(
                query = "brown",
                searchActive = true,
                title = "Morning Star",
                author = "Pierce Brown",
            ),
        )
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
    fun `a book in no series is unaffected`() {
        assertTrue(matchesLibrarySearch("emma", "Emma", null, series = null))
        assertFalse(matchesLibrarySearch("wheel", "Emma", null, series = null))
    }
}
