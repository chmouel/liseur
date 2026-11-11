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

    private fun series(key: String, vararg volumes: Book, progress: Map<String, Double> = emptyMap()): SeriesShelf {
        val vs = volumes.map { SeriesVolume(it, progression = progress[it.url]) }
        return SeriesShelf(key, key, null, vs, nextUp = vs.firstOrNull { !it.finished }, gaps = emptyList())
    }

    private fun stateFrom(progress: Map<String, Double>): (Book) -> LibraryFilterOption = {
        when {
            it.finished -> LibraryFilterOption.FINISHED
            it.url in progress || it.lastOpenedAt != null -> LibraryFilterOption.IN_PROGRESS
            else -> LibraryFilterOption.UNREAD
        }
    }

    private fun desk(
        books: List<Book>,
        series: List<SeriesShelf> = emptyList(),
        current: String? = null,
        progress: Map<String, Double> = emptyMap(),
        now: Long = 100,
        monthFrom: Long = 0,
    ) = homeDeskOf(books, series, current, progress, now, monthFrom, stateFrom(progress))

    @Test
    fun `home desk counts the shelf tiles from the whole shelf with their notes`() {
        val books = listOf(
            book("almost", openedAt = 10),
            book("barely", openedAt = 20),
            book("fresh", addedAt = 99),
            book("old", addedAt = 1),
            book("doneEarly", finished = true).copy(finishedAt = 5),
            book("doneLate", finished = true).copy(finishedAt = 50),
            book("boxed", openedAt = 40, archived = true),
        )
        val progress = mapOf(books[0].url to 0.9, books[1].url to 0.1)

        val tiles = desk(books, progress = progress, now = 100, monthFrom = 40).tiles.associateBy { it.kind }

        assertEquals(2, tiles.getValue(HomeShelfKind.READING).count)
        assertEquals(1, tiles.getValue(HomeShelfKind.READING).note)
        assertEquals(2, tiles.getValue(HomeShelfKind.TO_READ).count)
        assertEquals(2, tiles.getValue(HomeShelfKind.TO_READ).note)
        assertEquals(2, tiles.getValue(HomeShelfKind.FINISHED).count)
        assertEquals(1, tiles.getValue(HomeShelfKind.FINISHED).note)
        assertEquals(listOf("doneLate", "doneEarly"), tiles.getValue(HomeShelfKind.FINISHED).covers.map { it.title })
    }

    @Test
    fun `pick up next puts books under way first, then the next volume of each series, never the hero`() {
        val hero = book("hero", openedAt = 50)
        val older = book("older", openedAt = 10)
        val newer = book("newer", openedAt = 20)
        val s1 = book("s1", finished = true, openedAt = 5)
        val s2 = book("s2")
        val loose = book("loose", addedAt = 30)
        val books = listOf(hero, older, newer, s1, s2, loose)

        val home = desk(books, series = listOf(series("saga", s1, s2)), current = hero.url)

        assertEquals(listOf("newer", "older", "s2"), home.pickUpNext.map { it.book.title })
        assertEquals(
            listOf(PickReason.IN_PROGRESS, PickReason.IN_PROGRESS, PickReason.NEXT_IN_SERIES),
            home.pickUpNext.map { it.reason },
        )
        assertEquals(listOf("loose"), home.recentlyAdded.map { it.title })
        assertEquals(null, home.starter)
    }

    @Test
    fun `an untouched library is offered unread picks and a starter instead of a hero`() {
        val books = listOf(book("a", addedAt = 1), book("b", addedAt = 2))

        val home = desk(books)

        assertEquals(listOf("b", "a"), home.pickUpNext.map { it.book.title })
        assertTrue(home.pickUpNext.all { it.reason == PickReason.UNREAD })
        // Everything is new, so a recently added strip would only repeat it.
        assertEquals(emptyList<Book>(), home.recentlyAdded)
        assertEquals("b", home.starter?.title)
    }

    @Test
    fun `series sets keep only series under way, with read count, next volume and a bar per volume`() {
        val untouched = series("untouched", book("u1"), book("u2"))
        val done = series("done", book("d1", finished = true), book("d2", finished = true))
        val e2 = book("e2", openedAt = 10)
        val early = series("early", book("e1", finished = true, openedAt = 10), e2, book("e3"), progress = mapOf(e2.url to 0.5))
        val late = series("late", book("l1", finished = true, openedAt = 30), book("l2"))

        val sets = desk(emptyList(), series = listOf(untouched, done, early, late)).seriesSets

        assertEquals(listOf("late", "early"), sets.map { it.shelf.key })
        val earlySet = sets[1]
        assertEquals(1, earlySet.read)
        assertEquals("e2", earlySet.next?.book?.title)
        assertEquals(listOf(1f, 0.5f, 0f), earlySet.volumeProgress)
    }

    @Test
    fun `reading pulse counts today's minutes and the day streak, and stays quiet with none`() {
        val zone = java.time.ZoneOffset.UTC
        val today = java.time.LocalDate.of(2026, 11, 11)
        fun at(day: java.time.LocalDate, minutes: Long) = com.chmouel.liseur.data.db.ReadingSession(
            bookUrl = "b",
            startedAt = day.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000,
            lastCheckpointAt = day.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000,
            durationMs = minutes * 60_000,
        )

        val pulse = readingPulseOf(
            listOf(at(today, 30), at(today, 12), at(today.minusDays(1), 5), at(today.minusDays(3), 9)),
            today,
            zone,
        )

        assertEquals(ReadingPulse(minutesToday = 42, streakDays = 2), pulse)
        assertEquals(null, readingPulseOf(emptyList(), today, zone))
    }

    @Test
    fun `a sitting past midnight counts on the day it ended`() {
        val zone = java.time.ZoneOffset.UTC
        val today = java.time.LocalDate.of(2026, 11, 11)
        val start = today.minusDays(1).atTime(23, 40).atZone(zone).toInstant().toEpochMilli()
        val session = com.chmouel.liseur.data.db.ReadingSession(
            bookUrl = "b",
            startedAt = start,
            endedAt = start + 40 * 60_000,
            lastCheckpointAt = start + 40 * 60_000,
            durationMs = 40 * 60_000,
        )

        assertEquals(40, readingPulseOf(listOf(session), today, zone)?.minutesToday)
    }

    @Test
    fun `time left follows the book's own pace and refuses to guess on thin evidence`() {
        fun session(from: Double, to: Double, minutes: Long) = com.chmouel.liseur.data.db.ReadingSession(
            bookUrl = "b",
            startedAt = 0,
            lastCheckpointAt = 0,
            durationMs = minutes * 60_000,
            startProgression = from,
            endProgression = to,
        )

        // 20 minutes for a tenth of the book: the remaining half is 100 minutes.
        assertEquals(100L * 60_000, timeLeftMs(listOf(session(0.0, 0.05, 10), session(0.05, 0.1, 10)), 0.5))
        assertEquals(null, timeLeftMs(listOf(session(0.0, 0.1, 2)), 0.5))
        assertEquals(null, timeLeftMs(listOf(session(0.0, 0.001, 30)), 0.5))
        assertEquals(null, timeLeftMs(listOf(session(0.0, 0.1, 20)), null))
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
    fun `style, backdrop and tone ids round trip and unknown ids fall back to defaults`() {
        LibraryStyle.entries.forEach { assertEquals(it, LibraryStyle.fromId(it.id)) }
        assertEquals(LibraryStyle.CLASSIC, LibraryStyle.fromId("bogus"))
        ShelfBackdrop.entries.forEach { assertEquals(it, ShelfBackdrop.fromId(it.id)) }
        assertEquals(ShelfBackdrop.BRICKS, ShelfBackdrop.fromId("bogus"))
        ShelfTone.entries.forEach { assertEquals(it, ShelfTone.fromId(it.id)) }
        assertEquals(ShelfTone.THEME, ShelfTone.fromId("bogus"))
        assertEquals(ShelfBackdrop.BRICKS, LibraryLayout().shelfBackdrop)
        assertEquals(ShelfTone.THEME, LibraryLayout().shelfTone)
        assertEquals(true, LibraryLayout().bottomBar)
    }
}
