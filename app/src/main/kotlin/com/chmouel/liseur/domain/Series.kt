package com.chmouel.liseur.domain

/**
 * The name two spellings of the same series agree on.
 *
 * Which books make up a series is decided by this and never by a
 * server's own id, because a server id cannot merge the two copies a
 * reader most often has: the one downloaded from calibre-web or Komga,
 * and the loose EPUB of the same series that was already in a folder on
 * the phone. Grouping by name puts them on one shelf; grouping by id
 * leaves two piles of the same books.
 *
 * The folding is the one already used to search the library and to file
 * it — accents dropped, case ignored, a leading article set aside — so
 * *L'Étranger* and *Etranger* are the same series, and *The Expanse*
 * files with *Expanse*.
 *
 * The cost is that two genuinely different series sharing a title become
 * one. That is rare, and the alternative gets the common case wrong.
 */
fun seriesKey(name: String?): String {
    val folded = name?.trim().orEmpty()
    if (folded.isEmpty()) return ""
    return sortKey(folded.foldForSearch())
}

/**
 * How a book's place in its series reads: `#3`, or `#7.5` when a novella
 * sits between two volumes.
 *
 * Whole numbers lose their decimal point because that is how a series is
 * numbered on a cover; anything else keeps as much as it needs and no
 * more, so `7.50` from a feed that pads its numbers still reads `#7.5`.
 */
fun seriesIndexLabel(index: Double?): String? {
    if (index == null || !index.isFinite()) return null
    return if (index == Math.floor(index) && Math.abs(index) < 1e9) {
        index.toLong().toString()
    } else {
        java.math.BigDecimal.valueOf(index).stripTrailingZeros().toPlainString()
    }
}
