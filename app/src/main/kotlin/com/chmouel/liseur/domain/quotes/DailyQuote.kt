package com.chmouel.liseur.domain.quotes

import kotlin.random.Random

/** Something a book could be quoted with on Home. */
data class QuoteCandidate(
    val bookUrl: String,
    val text: String,
    /** Where it is in the book, as a Readium locator; null when it cannot be opened at. */
    val locatorJson: String?,
    val chapter: String?,
    val score: Double = 0.0,
    /** The reader's own highlight rather than a line found in the text. */
    val highlight: Boolean = false,
)

/**
 * Today's quote.
 *
 * The same all day, and across restarts, for a given [nonce]; asking for
 * another bumps the nonce, which moves on to the next book in the day's
 * order, so a second ask never shows the same book again while there is
 * another to show. A book with highlights is quoted from them, since the
 * reader chose those; otherwise from its best lines, the better ones
 * more often.
 */
object DailyQuote {
    const val MAX_LENGTH = 280

    /** Word joiners and soft hyphens some books put around dashes; they only get in the way off the page. */
    private val INVISIBLE = Regex("[\u00AD\u200B\u2060\uFEFF]")
    private const val TOP = 12

    fun pick(candidates: List<QuoteCandidate>, epochDay: Long, nonce: Int): QuoteCandidate? {
        val byBook = candidates.groupBy { it.bookUrl }
        if (byBook.isEmpty()) return null
        val order = byBook.keys.sorted().shuffled(Random(epochDay))
        val book = order[Math.floorMod(nonce, order.size)]
        val quotes = byBook.getValue(book)
        val random = Random(epochDay * 31 + nonce)
        val highlights = quotes.filter { it.highlight }.sortedBy { it.text }
        if (highlights.isNotEmpty()) return highlights[random.nextInt(highlights.size)]
        val lines = quotes.sortedWith(compareByDescending<QuoteCandidate> { it.score }.thenBy { it.text }).take(TOP)
        // Rank-weighted: the best line is TOP times as likely as the last.
        var ticket = random.nextInt(lines.size * (lines.size + 1) / 2)
        lines.forEachIndexed { rank, line ->
            ticket -= lines.size - rank
            if (ticket < 0) return line
        }
        return lines.first()
    }

    /** [text] on one line, cut at a word under [max] characters with an ellipsis when too long. */
    fun shorten(text: String, max: Int = MAX_LENGTH): String {
        val flat = text.replace(INVISIBLE, "").replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        val cut = flat.substring(0, max - 1)
        val space = cut.lastIndexOf(' ')
        val kept = if (space > max / 2) cut.substring(0, space) else cut
        return kept.trimEnd(',', ';', ':', ' ', '—', '–', '-') + "…"
    }
}
