package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookEvaluation
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.domain.Contributor
import com.chmouel.liseur.domain.ContributorRoles
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.PersonalEvaluation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class GoodreadsCsvExportTest {

    private val addedAt = Instant.parse("2025-01-15T10:00:00Z").toEpochMilli()
    private val readAt = Instant.parse("2025-03-20T18:30:00Z").toEpochMilli()
    private val finishedAt = Instant.parse("2025-04-02T20:00:00Z").toEpochMilli()

    @Test
    fun `empty library writes only header row with crlf`() {
        val csv = goodreadsCsvExport(emptyList(), emptyList(), emptyList(), emptyList(), ZoneOffset.UTC)
        assertTrue(csv.endsWith("\r\n"))
        val rows = parseCsv(csv)
        assertEquals(1, rows.size)
        assertEquals(
            listOf(
                "Book Id", "Title", "Author", "Author l-f", "Additional Authors",
                "ISBN", "ISBN13", "My Rating", "Average Rating", "Publisher",
                "Binding", "Number of Pages", "Year Published", "Original Publication Year",
                "Date Read", "Date Added", "Bookshelves", "Bookshelves with positions",
                "Exclusive Shelf", "My Review", "Spoiler", "Private Notes",
                "Read Count", "Owned Copies", "Reading Progress", "Last Read",
                "Series", "Series Index",
            ),
            rows[0],
        )
    }

    @Test
    fun `finished book maps standard goodreads fields and escapes review`() {
        val book = sampleBook(
            url = "file:///dune.epub",
            title = "Dune",
            author = "Frank Herbert",
            addedAt = addedAt,
            finishedAt = finishedAt,
            seriesName = "Dune Chronicles",
            seriesIndex = 1.0,
        )
        val progress = ReadingProgress(
            bookUrl = book.url,
            locatorJson = "{}",
            totalProgression = 1.0,
            updatedAt = finishedAt,
            readAt = finishedAt,
        )
        val evaluations = listOf(
            BookEvaluation(book.url, PersonalEvaluation.RATING, "10", book.title, book.author.orEmpty(), updatedAt = finishedAt),
            BookEvaluation(book.url, PersonalEvaluation.REVIEW, "Great book, \"classic\"\nMust read.", book.title, book.author.orEmpty(), updatedAt = finishedAt),
        )
        val metadata = BookMetadata.of(
            book.url,
            MetadataSource.FILE,
            MetadataSnapshot(
                publishers = listOf("Chilton Books"),
                published = "1965-08-01",
                pageCount = 412,
                identifiers = listOf(
                    Identifier("isbn", "0-441-17271-7"),
                    Identifier("isbn", "urn:isbn:978-0-441-17271-9"),
                    Identifier("uuid", "not-an-isbn"),
                ),
            ),
        )

        val rows = parseCsv(goodreadsCsvExport(listOf(book), listOf(progress), evaluations, listOf(metadata), ZoneOffset.UTC))
        assertEquals(2, rows.size)
        val r = rows[0].zip(rows[1]).toMap()

        assertEquals("", r["Book Id"])
        assertEquals("Dune", r["Title"])
        assertEquals("Frank Herbert", r["Author"])
        assertEquals("Herbert, Frank", r["Author l-f"])
        assertEquals("", r["Additional Authors"])
        assertEquals("=\"0441172717\"", r["ISBN"])
        assertEquals("=\"9780441172719\"", r["ISBN13"])
        assertEquals("5", r["My Rating"])
        assertEquals("Chilton Books", r["Publisher"])
        assertEquals("ebook", r["Binding"])
        assertEquals("412", r["Number of Pages"])
        assertEquals("1965", r["Year Published"])
        assertEquals("2025/04/02", r["Date Read"])
        assertEquals("2025/01/15", r["Date Added"])
        assertEquals("", r["Bookshelves"])
        assertEquals("read", r["Exclusive Shelf"])
        assertEquals("Great book, \"classic\"\nMust read.", r["My Review"])
        assertEquals("Series: Dune Chronicles #1", r["Private Notes"])
        assertEquals("1", r["Read Count"])
        assertEquals("1", r["Owned Copies"])
        assertEquals("100%", r["Reading Progress"])
        assertEquals("2025/04/02", r["Last Read"])
        assertEquals("Dune Chronicles", r["Series"])
        assertEquals("1", r["Series Index"])
    }

    @Test
    fun `in progress book exports position in private notes and trailing columns`() {
        val book = sampleBook(
            url = "file:///omens.epub",
            title = "Good Omens",
            author = "Terry Pratchett, Neil Gaiman",
            addedAt = addedAt,
            remotePageCount = 300,
            seriesName = "Standalone",
            seriesIndex = 1.5,
        )
        val progress = ReadingProgress(
            bookUrl = book.url,
            locatorJson = "{}",
            totalProgression = 0.42,
            updatedAt = readAt,
            readAt = readAt,
        )
        val evaluations = listOf(
            BookEvaluation(book.url, PersonalEvaluation.RATING, "9", book.title, book.author.orEmpty(), updatedAt = readAt),
        )

        val rows = parseCsv(goodreadsCsvExport(listOf(book), listOf(progress), evaluations, emptyList(), ZoneOffset.UTC))
        val r = rows[0].zip(rows[1]).toMap()

        assertEquals("Terry Pratchett", r["Author"])
        assertEquals("Pratchett, Terry", r["Author l-f"])
        assertEquals("Neil Gaiman", r["Additional Authors"])
        assertEquals("5", r["My Rating"])
        assertEquals("300", r["Number of Pages"])
        assertEquals("", r["Date Read"])
        assertEquals("currently-reading", r["Exclusive Shelf"])
        assertEquals("currently-reading", r["Bookshelves"])
        assertEquals("Reading progress: 42% (page 126 of 300); Rating: 4.5/5; Series: Standalone #1.5", r["Private Notes"])
        assertEquals("0", r["Read Count"])
        assertEquals("42%", r["Reading Progress"])
        assertEquals("2025/03/20", r["Last Read"])
        assertEquals("Standalone", r["Series"])
        assertEquals("1.5", r["Series Index"])
    }

    @Test
    fun `maps paused dnf archived hidden and undownloaded books`() {
        val paused = sampleBook(url = "file:///a.epub", title = "A Paused Book", author = "Le Guin, Ursula K.")
        val dnf = sampleBook(
            url = "file:///b.epub",
            title = "B DNF Book",
            author = "Plato",
            downloadState = DownloadState.REMOTE,
            localUri = null,
        )
        val archived = sampleBook(
            url = "file:///c.epub",
            title = "C Archived Book",
            author = null,
            finishedAt = finishedAt,
            archivedAt = finishedAt,
        )
        val hidden = sampleBook(
            url = "file:///d.epub",
            title = "D Hidden Book",
            hiddenAt = finishedAt,
        )
        val progress = listOf(
            ReadingProgress(paused.url, "{}", 0.25, updatedAt = readAt, readAt = readAt, finishedOverride = FinishedOverride.PAUSED.ordinal),
            ReadingProgress(dnf.url, "{}", 0.10, updatedAt = readAt, readAt = readAt, finishedOverride = FinishedOverride.DID_NOT_FINISH.ordinal),
        )

        val rows = parseCsv(goodreadsCsvExport(listOf(hidden, archived, dnf, paused), progress, emptyList(), emptyList(), ZoneOffset.UTC))
        assertEquals(4, rows.size) // header + 3 visible books (hidden excluded)

        val byTitle = rows.drop(1).associate { row ->
            val map = rows[0].zip(row).toMap()
            map.getValue("Title") to map
        }

        val p = byTitle.getValue("A Paused Book")
        assertEquals("Le Guin, Ursula K.", p["Author"])
        assertEquals("Le Guin, Ursula K.", p["Author l-f"])
        assertEquals("currently-reading", p["Exclusive Shelf"])
        assertEquals("currently-reading, paused", p["Bookshelves"])
        assertEquals("Reading progress: 25%", p["Private Notes"])
        assertEquals("25%", p["Reading Progress"])

        val d = byTitle.getValue("B DNF Book")
        assertEquals("Plato", d["Author"])
        assertEquals("Plato", d["Author l-f"])
        assertEquals("to-read", d["Exclusive Shelf"])
        assertEquals("to-read, did-not-finish", d["Bookshelves"])
        assertEquals("0", d["Owned Copies"])

        val a = byTitle.getValue("C Archived Book")
        assertEquals("read", a["Exclusive Shelf"])
        assertEquals("archived", a["Bookshelves"])
        assertEquals("1", a["Read Count"])
        assertEquals("100%", a["Reading Progress"])
        assertEquals("0", a["My Rating"])
    }

    @Test
    fun `prefers structured author contributors for mononyms and handles delimiter only authors`() {
        val mononyms = sampleBook(
            url = "file:///dialogues.epub",
            title = "Dialogues",
            author = "Plato, Aristotle",
        )
        val overridden = sampleBook(
            url = "file:///overridden.epub",
            title = "Overridden",
            author = "Custom Author",
            authorOverridden = true,
        )
        val delimiterOnly = sampleBook(
            url = "file:///broken.epub",
            title = "Broken Author",
            author = "; ;",
        )
        val metadata = listOf(
            BookMetadata.of(
                mononyms.url,
                MetadataSource.FILE,
                MetadataSnapshot(
                    contributors = listOf(
                        Contributor(ContributorRoles.AUTHOR, "Plato"),
                        Contributor(ContributorRoles.AUTHOR, "Aristotle"),
                    ),
                ),
            ),
            BookMetadata.of(
                overridden.url,
                MetadataSource.FILE,
                MetadataSnapshot(
                    contributors = listOf(
                        Contributor(ContributorRoles.AUTHOR, "Plato"),
                        Contributor(ContributorRoles.AUTHOR, "Aristotle"),
                    ),
                ),
            ),
        )

        val rows = parseCsv(goodreadsCsvExport(listOf(mononyms, overridden, delimiterOnly), emptyList(), emptyList(), metadata, ZoneOffset.UTC))
        val byTitle = rows.drop(1).associate { row ->
            val map = rows[0].zip(row).toMap()
            map.getValue("Title") to map
        }

        val m = byTitle.getValue("Dialogues")
        assertEquals("Plato", m["Author"])
        assertEquals("Plato", m["Author l-f"])
        assertEquals("Aristotle", m["Additional Authors"])

        val o = byTitle.getValue("Overridden")
        assertEquals("Custom Author", o["Author"])
        assertEquals("", o["Additional Authors"])

        val b = byTitle.getValue("Broken Author")
        assertEquals("", b["Author"])
        assertEquals("", b["Author l-f"])
        assertEquals("", b["Additional Authors"])
    }

    @Test
    fun `neutralizes spreadsheet formulas in untrusted text while keeping isbn formulas`() {
        val book = sampleBook(
            url = "file:///formula.epub",
            title = "=HYPERLINK(\"https://example.invalid\",\"book\")",
            author = "@evil",
        )
        val evaluations = listOf(
            BookEvaluation(book.url, PersonalEvaluation.REVIEW, "+1+1", book.title, book.author.orEmpty(), updatedAt = addedAt),
        )
        val metadata = listOf(
            BookMetadata.of(
                book.url,
                MetadataSource.FILE,
                MetadataSnapshot(
                    publishers = listOf("-badpub"),
                    identifiers = listOf(Identifier("isbn", "0441172717")),
                ),
            ),
        )

        val rows = parseCsv(goodreadsCsvExport(listOf(book), emptyList(), evaluations, metadata, ZoneOffset.UTC))
        val r = rows[0].zip(rows[1]).toMap()

        assertEquals("'=HYPERLINK(\"https://example.invalid\",\"book\")", r["Title"])
        assertEquals("'@evil", r["Author"])
        assertEquals("'-badpub", r["Publisher"])
        assertEquals("'+1+1", r["My Review"])
        assertEquals("=\"0441172717\"", r["ISBN"])
    }

    private fun sampleBook(
        url: String,
        title: String,
        author: String? = "Author",
        authorOverridden: Boolean = false,
        addedAt: Long = this.addedAt,
        finishedAt: Long? = null,
        archivedAt: Long? = null,
        hiddenAt: Long? = null,
        remotePageCount: Int? = null,
        seriesName: String? = null,
        seriesIndex: Double? = null,
        downloadState: DownloadState = DownloadState.DOWNLOADED,
        localUri: String? = url,
    ): Book = Book(
        url = url,
        title = title,
        author = author,
        coverPath = null,
        source = null,
        addedAt = addedAt,
        lastOpenedAt = null,
        localUri = localUri,
        downloadState = downloadState,
        finishedAt = finishedAt,
        archivedAt = archivedAt,
        hiddenAt = hiddenAt,
        remotePageCount = remotePageCount,
        seriesName = seriesName,
        seriesIndex = seriesIndex,
        authorOverridden = authorOverridden,
    )

    /** Minimal RFC 4180 parser for verifying exported CSV structure and escaping. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row += cell.toString()
                        cell.clear()
                    }
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        row += cell.toString()
                        cell.clear()
                        rows += row.toList()
                        row.clear()
                    }
                    '\n' -> {
                        row += cell.toString()
                        cell.clear()
                        rows += row.toList()
                        row.clear()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row.toList()
        }
        return rows
    }
}
