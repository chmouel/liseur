package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesPickerTest {

    private fun option(
        name: String,
        author: String? = null,
        volumes: Int = 2,
        lastReadAt: Long? = null,
        maxIndex: Double? = null,
    ) = SeriesPickOption(
        key = seriesKey(name),
        name = name,
        author = author,
        volumeCount = volumes,
        cover = Book(
            url = "https://example.test/$name",
            title = name,
            author = author,
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = null,
            downloadState = DownloadState.DOWNLOADED,
            finishedAt = null,
            archivedAt = null,
            seriesName = name,
            seriesIndex = null,
        ),
        lastReadAt = lastReadAt,
        maxIndex = maxIndex,
    )

    private fun names(ranked: List<RankedSeries>) = ranked.map { it.option.name }

    @Test
    fun `an empty query offers the whole shelf, alphabetically`() {
        val ranked = rankSeriesOptions(
            "",
            listOf(option("The Expanse"), option("Discworld"), option("Amber")),
        )
        // The article is set aside for sorting, the way the library
        // sorts everywhere else: Expanse files under E.
        assertEquals(listOf("Amber", "Discworld", "The Expanse"), names(ranked))
        assertTrue(ranked.all { it.matches.isEmpty() })
    }

    @Test
    fun `an exact name beats a prefix, which beats a word, which beats a scatter`() {
        val ranked = rankSeriesOptions(
            "expanse",
            listOf(
                option("Expanded Universe"),
                option("The Expanse"),
                option("Expanse Companion"),
                option("Expanse"),
            ),
        )
        assertEquals(
            listOf("Expanse", "Expanse Companion", "The Expanse"),
            names(ranked),
        )
    }

    @Test
    fun `an author is a way in, below every match on the name`() {
        val ranked = rankSeriesOptions(
            "corey",
            listOf(option("The Expanse", author = "James S. A. Corey"), option("Coreyville")),
        )
        assertEquals(listOf("Coreyville", "The Expanse"), names(ranked))
    }

    @Test
    fun `a name ending in sigma answers to both its spellings`() {
        // Greek has two lower-case sigmas — ς at the end of a word, σ
        // elsewhere — and which one a name or a keyboard produces is a
        // spelling accident, not a different series. Both fold onto σ,
        // or the picker (which folds names a character at a time, where
        // lowercasing never yields ς) could not find its own rows.
        assertEquals(
            listOf("Κόσμος"),
            names(rankSeriesOptions("κοσμοσ", listOf(option("Κόσμος")))),
        )
        assertEquals(
            listOf("Κόσμος"),
            names(rankSeriesOptions("κόσμος", listOf(option("Κόσμος")))),
        )
    }

    @Test
    fun `accents are not something the reader has to type`() {
        val ranked = rankSeriesOptions("eloge", listOf(option("Éloge de la Fuite")))
        assertEquals(listOf("Éloge de la Fuite"), names(ranked))
        // The range is into the name as written, accent included, so a
        // row emboldens the five letters it found and no more.
        assertEquals(listOf(0 until 5), ranked.single().matches)
    }

    @Test
    fun `half-remembered fragments still find the shelf`() {
        val ranked = rankSeriesOptions(
            "wheel time",
            listOf(option("The Wheel of Time"), option("Time Machine")),
        )
        assertEquals(listOf("The Wheel of Time"), names(ranked))
    }

    @Test
    fun `a match nowhere in the name or the author is not offered`() {
        assertEquals(emptyList<String>(), names(rankSeriesOptions("dune", listOf(option("Amber")))))
    }

    @Test
    fun `within a tier the shelf read most recently comes first`() {
        val ranked = rankSeriesOptions(
            "the",
            listOf(
                option("The Expanse", lastReadAt = 10),
                option("The Wheel of Time", lastReadAt = 500),
                option("The Dark Tower"),
            ),
        )
        assertEquals(
            listOf("The Wheel of Time", "The Expanse", "The Dark Tower"),
            names(ranked),
        )
    }

    @Test
    fun `with nothing read the bigger shelf wins`() {
        val ranked = rankSeriesOptions(
            "the",
            listOf(option("The Expanse", volumes = 2), option("The Dark Tower", volumes = 8)),
        )
        assertEquals(listOf("The Dark Tower", "The Expanse"), names(ranked))
    }

    @Test
    fun `every occurrence of every word is marked`() {
        val ranked = rankSeriesOptions("a", listOf(option("Anna and Anna")))
        assertEquals(listOf(0 until 1, 3 until 4, 5 until 6, 9 until 10, 12 until 13), ranked.single().matches)
    }

    @Test
    fun `a title announcing its series is suggested before anything is typed`() {
        val suggested = suggestedSeries(
            title = "The Expanse: Nemesis Games",
            author = "James S. A. Corey",
            options = listOf(option("Discworld"), option("The Expanse")),
        )
        assertEquals(listOf("The Expanse"), suggested.map { it.name })
    }

    @Test
    fun `the author's other shelf is suggested, below the title's own`() {
        val suggested = suggestedSeries(
            title = "The Expanse: Nemesis Games",
            author = "James S. A. Corey",
            options = listOf(
                option("Corey Shorts", author = "James S. A. Corey"),
                option("The Expanse"),
            ),
        )
        assertEquals(listOf("The Expanse", "Corey Shorts"), suggested.map { it.name })
    }

    @Test
    fun `only shelves with something read are recent`() {
        val recent = recentSeries(
            listOf(
                option("Amber", lastReadAt = 5),
                option("Discworld"),
                option("The Expanse", lastReadAt = 50),
            ),
        )
        assertEquals(listOf("The Expanse", "Amber"), recent.map { it.name })
    }

    @Test
    fun `a series files under the letter it sorts under`() {
        assertEquals("E", seriesInitial("The Expanse"))
        assertEquals("E", seriesInitial("Éloge"))
        assertEquals(OTHER_INITIAL, seriesInitial("1984"))
        assertEquals(OTHER_INITIAL, seriesInitial(""))
    }

    @Test
    fun `create row hidden when typed name matches via seriesKey`() {
        val typed = "Expanse"
        val options = listOf(option("The Expanse"))
        val ranked = rankSeriesOptions(typed, options)
        val typedKey = seriesKey(typed)
        assertTrue(typedKey.isNotEmpty() && ranked.any { it.option.key == typedKey })
    }

    @Test
    fun `create row appears when typed name is genuinely new`() {
        val typed = "Dune"
        val options = listOf(option("The Expanse"))
        val ranked = rankSeriesOptions(typed, options)
        val typedKey = seriesKey(typed)
        assertFalse(typedKey.isNotEmpty() && ranked.any { it.option.key == typedKey })
    }

    @Test
    fun `series starting with a digit sort after all lettered series`() {
        val names = listOf("1984", "Amber", "Zinc")
        val sorted = names.sortedWith(
            compareBy(
                { if (seriesInitial(it) == OTHER_INITIAL) 1 else 0 },
                { seriesInitial(it) },
                { sortKey(it) },
            ),
        )
        assertEquals(listOf("Amber", "Zinc", "1984"), sorted)
    }

    @Test
    fun `the volume offered is the next one along`() {
        assertEquals(9.0, suggestedVolume(option("Amber", maxIndex = 8.0))!!, 0.0)
        // A novella at 7.5 means the next whole volume is 8, not 8.5.
        assertEquals(8.0, suggestedVolume(option("Amber", maxIndex = 7.5))!!, 0.0)
        assertNull(suggestedVolume(option("Amber")))
    }
}
