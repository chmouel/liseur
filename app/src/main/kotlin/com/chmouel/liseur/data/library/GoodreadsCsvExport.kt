package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookEvaluation
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.readingStatusFor
import com.chmouel.liseur.domain.resolveMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Exports the library as a Goodreads-compatible CSV.
 *
 * The first 24 columns match Goodreads' standard import/export schema so the file
 * imports cleanly into Goodreads, StoryGraph, Hardcover, BookWyrm and similar tools.
 * Goodreads has no native reading-progress column and ignores unknown trailing columns,
 * so reading progress (and half-star ratings / series) are recorded both in `Private Notes`
 * for Goodreads and in dedicated trailing columns for spreadsheets and other tools.
 */
fun goodreadsCsvExport(
    books: List<Book>,
    progress: List<ReadingProgress>,
    evaluations: List<BookEvaluation>,
    metadata: List<BookMetadata>,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val progressByUrl = progress.associateBy { it.bookUrl }
    val evaluationsByUrl = evaluations.groupBy { it.bookUrl }
    val metadataByUrl = metadata.groupBy { it.bookUrl }.mapValues { (_, rows) ->
        resolveMetadata(
            rows.mapNotNull { row -> MetadataSource.of(row.source)?.let { it to row.toSnapshot() } }.toMap(),
        )
    }

    val visibleBooks = books
        .filterNot { it.hidden }
        .sortedWith(compareBy<Book, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.url })

    val out = StringBuilder()
    out.appendCsvRow(HEADERS)
    for (book in visibleBooks) {
        val bookProgress = progressByUrl[book.url]
        val bookEvaluations = evaluationsByUrl[book.url].orEmpty().associateBy { it.field }
        val bookMetadata = metadataByUrl[book.url]
        out.appendCsvRow(rowFor(book, bookProgress, bookEvaluations, bookMetadata, zone))
    }
    return out.toString()
}

private fun rowFor(
    book: Book,
    progress: ReadingProgress?,
    evaluations: Map<String, BookEvaluation>,
    metadata: com.chmouel.liseur.domain.MetadataSnapshot?,
    zone: ZoneId,
): List<String> {
    val (primaryAuthor, additionalAuthors) = splitAuthors(book.author)
    val authorLastFirst = toLastFirst(primaryAuthor)
    val (isbn10, isbn13) = extractIsbns(
        metadata?.identifiers.orEmpty().map { it.value } + listOfNotNull(book.workId),
    )

    val status = when {
        progress != null -> readingStatusFor(progress.totalProgression, progress.override, progress.partialCoverage)
        book.finishedAt != null -> ReadingStatus.FINISHED
        else -> ReadingStatus.READY_TO_READ
    }
    val exclusiveShelf = when (status) {
        ReadingStatus.FINISHED -> "read"
        ReadingStatus.READING, ReadingStatus.PAUSED -> "currently-reading"
        ReadingStatus.READY_TO_READ, ReadingStatus.WANT_TO_READ, ReadingStatus.DID_NOT_FINISH -> "to-read"
    }
    val bookshelves = buildList {
        if (exclusiveShelf != "read") add(exclusiveShelf)
        if (status == ReadingStatus.PAUSED) add("paused")
        if (status == ReadingStatus.DID_NOT_FINISH) add("did-not-finish")
        if (book.archived) add("archived")
    }.distinct().joinToString(", ")

    val ratingRaw = evaluations[PersonalEvaluation.RATING]?.value?.toIntOrNull()?.takeIf { it in 2..10 }
    val myRating = ratingRaw?.let { ((it + 1) / 2).coerceIn(1, 5) } ?: 0
    val myReview = evaluations[PersonalEvaluation.REVIEW]?.value?.trim().orEmpty()

    val pageCount = metadata?.pageCount?.takeIf { it > 0 } ?: book.remotePageCount?.takeIf { it > 0 }
    val progression = progress?.totalProgression?.coerceIn(0.0, 1.0)
    val progressPercent = progression?.let { (it * 100.0).roundToInt() }
    val readingProgressCell = when {
        progressPercent != null -> "$progressPercent%"
        status == ReadingStatus.FINISHED -> "100%"
        else -> ""
    }

    val seriesName = book.seriesName?.trim().orEmpty()
    val seriesIndex = formatSeriesIndex(book.seriesIndex)

    val privateNotes = buildList {
        if (status != ReadingStatus.FINISHED && progressPercent != null && progressPercent > 0) {
            if (pageCount != null) {
                val currentPage = (progression * pageCount).roundToInt().coerceIn(1, pageCount)
                add("Reading progress: $progressPercent% (page $currentPage of $pageCount)")
            } else {
                add("Reading progress: $progressPercent%")
            }
        }
        if (ratingRaw != null && ratingRaw % 2 == 1) {
            add("Rating: ${ratingRaw / 2}.5/5")
        }
        if (seriesName.isNotEmpty()) {
            add(if (seriesIndex.isNotEmpty()) "Series: $seriesName #$seriesIndex" else "Series: $seriesName")
        }
    }.joinToString("; ")

    val dateRead = if (status == ReadingStatus.FINISHED) {
        formatDate(book.finishedAt ?: progress?.readAt, zone)
    } else {
        ""
    }
    val dateAdded = formatDate(book.addedAt, zone)
    val lastRead = formatDate(progress?.readAt ?: book.lastOpenedAt, zone)

    val publisher = metadata?.publishers?.firstOrNull().orEmpty()
    val yearPublished = extractYear(metadata?.published).orEmpty()
    val readCount = if (status == ReadingStatus.FINISHED) "1" else "0"
    val ownedCopies = if (book.localUri != null || book.downloadState == DownloadState.DOWNLOADED) "1" else "0"

    return listOf(
        "",
        book.title.trim(),
        primaryAuthor,
        authorLastFirst,
        additionalAuthors,
        isbn10?.let { "=\"$it\"" }.orEmpty(),
        isbn13?.let { "=\"$it\"" }.orEmpty(),
        myRating.toString(),
        "",
        publisher,
        "ebook",
        pageCount?.toString().orEmpty(),
        yearPublished,
        "",
        dateRead,
        dateAdded,
        bookshelves,
        "",
        exclusiveShelf,
        myReview,
        "",
        privateNotes,
        readCount,
        ownedCopies,
        readingProgressCell,
        lastRead,
        seriesName,
        seriesIndex,
    )
}

private fun splitAuthors(raw: String?): Pair<String, String> {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return "" to ""
    val parts = when {
        ';' in trimmed -> trimmed.split(';').map(String::trim).filter(String::isNotEmpty)
        ", " in trimmed -> {
            val candidates = trimmed.split(", ").map(String::trim).filter(String::isNotEmpty)
            // Keep "Last, First" (including compound surnames like "Le Guin, Ursula K.") intact.
            val looksLikeLastFirst = candidates.size == 2 && (
                ' ' !in candidates[0] ||
                    ' ' !in candidates[1] ||
                    candidates[0].substringBefore(' ').lowercase(Locale.ROOT) in SURNAME_PARTICLES ||
                    candidates[1].endsWith('.')
                )
            if (candidates.size >= 2 && !looksLikeLastFirst) candidates else listOf(trimmed)
        }
        else -> listOf(trimmed)
    }
    return parts.first() to parts.drop(1).joinToString(", ")
}

private val SURNAME_PARTICLES = setOf(
    "al", "da", "de", "del", "della", "des", "di", "du", "el", "la", "le", "st", "st.", "van", "von",
)

private fun toLastFirst(author: String): String {
    if (author.isEmpty() || ',' in author) return author
    val words = author.split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.size < 2) return author
    return "${words.last()}, ${words.dropLast(1).joinToString(" ")}"
}

private fun extractIsbns(candidates: List<String>): Pair<String?, String?> {
    var isbn10: String? = null
    var isbn13: String? = null
    for (raw in candidates) {
        val valid = normalizedIsbn(raw) ?: continue
        when (valid.length) {
            10 -> if (isbn10 == null) isbn10 = valid
            13 -> if (isbn13 == null) isbn13 = valid
        }
        if (isbn10 != null && isbn13 != null) break
    }
    return isbn10 to isbn13
}

private fun normalizedIsbn(raw: String): String? {
    val digits = raw.trim()
        .replace(Regex("^(urn:)?isbn:", RegexOption.IGNORE_CASE), "")
        .filterNot { it == '-' || it == ' ' }
        .uppercase(Locale.ROOT)
    val valid = when {
        digits.matches(ISBN13_REGEX) ->
            digits.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum() % 10 == 0
        digits.matches(ISBN10_REGEX) ->
            digits.mapIndexed { i, c -> (if (c == 'X') 10 else c - '0') * (10 - i) }.sum() % 11 == 0
        else -> false
    }
    return digits.takeIf { valid }
}

private fun formatDate(epochMillis: Long?, zone: ZoneId): String {
    if (epochMillis == null || epochMillis <= 0L) return ""
    return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(GOODREADS_DATE)
}

private fun extractYear(published: String?): String? =
    published?.trim()?.let { YEAR_REGEX.find(it)?.groupValues?.get(1) }

private fun formatSeriesIndex(index: Double?): String {
    if (index == null || !index.isFinite() || index <= 0.0) return ""
    return BigDecimal.valueOf(index).stripTrailingZeros().toPlainString()
}

private fun StringBuilder.appendCsvRow(cells: List<String>) {
    cells.forEachIndexed { i, cell ->
        if (i > 0) append(',')
        appendCsvCell(cell)
    }
    append("\r\n")
}

private fun StringBuilder.appendCsvCell(value: String) {
    val needsQuoting = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
    if (!needsQuoting) {
        append(value)
        return
    }
    append('"')
    for (ch in value) {
        if (ch == '"') append("\"\"") else append(ch)
    }
    append('"')
}

private val GOODREADS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT)
private val ISBN10_REGEX = Regex("\\d{9}[\\dX]")
private val ISBN13_REGEX = Regex("97[89]\\d{10}")
private val YEAR_REGEX = Regex("^(\\d{4})")

private val HEADERS = listOf(
    "Book Id",
    "Title",
    "Author",
    "Author l-f",
    "Additional Authors",
    "ISBN",
    "ISBN13",
    "My Rating",
    "Average Rating",
    "Publisher",
    "Binding",
    "Number of Pages",
    "Year Published",
    "Original Publication Year",
    "Date Read",
    "Date Added",
    "Bookshelves",
    "Bookshelves with positions",
    "Exclusive Shelf",
    "My Review",
    "Spoiler",
    "Private Notes",
    "Read Count",
    "Owned Copies",
    "Reading Progress",
    "Last Read",
    "Series",
    "Series Index",
)
