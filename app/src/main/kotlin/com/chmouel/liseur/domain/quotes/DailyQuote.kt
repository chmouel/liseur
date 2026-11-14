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
 *
 * [pick] also takes the key of the line it chose last time, because the
 * candidates are not fixed: a scan finishing, a book becoming eligible
 * or a highlight arriving from another device all change the set under a
 * day and nonce that did not move, and the reader would watch the line
 * be replaced for no reason they could see.
 */
object DailyQuote {
    const val MAX_LENGTH = 280

    /** Word joiners and soft hyphens some books put around dashes; they only get in the way off the page. */
    private val INVISIBLE = Regex("[\u00AD\u200B\u2060\uFEFF]")
    private const val TOP = 12

    /**
     * A stable name for a candidate.
     *
     * What Home keeps beside the day and the nonce, so the line already
     * shown can be asked for again: the candidate set changes under a
     * scan finishing or a book becoming eligible, and the same day and
     * nonce would otherwise pick a different line out of it.
     */
    fun key(candidate: QuoteCandidate): String = "${candidate.bookUrl}\u0000${candidate.text}"

    fun pick(
        candidates: List<QuoteCandidate>,
        epochDay: Long,
        nonce: Int,
        keep: String? = null,
    ): QuoteCandidate? {
        // The day's line stays the day's line while it is still there
        // to be shown.
        keep?.let { chosen -> candidates.firstOrNull { key(it) == chosen }?.let { return it } }
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
