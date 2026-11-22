package com.chmouel.liseur.hardcover

import java.text.Normalizer

/** The editions Hardcover itself shows for a book, one per kind. */
data class HardcoverDefaultEditions(
    val ebook: HardcoverEdition?,
    val physical: HardcoverEdition?,
    val mostRead: HardcoverEdition?,
)

/**
 * Deciding which Hardcover book a file is when it carries no ISBN.
 *
 * A search answers with whatever ranks best, and the best-ranked result
 * for a title nobody on Hardcover has read is somebody else's book.
 * Marking the wrong book as read on the reader's account is worse than
 * asking them, so a hit is only adopted when both its title and an
 * author agree with what the file says; anything less is left for the
 * reader to choose in Book details.
 *
 * Agreement is checked on folded text (no case, accents or punctuation)
 * so that `Perfect Victims` from the file matches Hardcover's
 * `Perfect Victims: And the Politics of Appeal`, and `Tolkien, J. R. R.`
 * matches `J.R.R. Tolkien`.
 */
object HardcoverMatch {

    /** The first hit whose title and author both agree with the file, or null. */
    fun confident(title: String?, author: String?, hits: List<HardcoverBook>): HardcoverBook? {
        val wanted = Title.of(title) ?: return null
        // A title alone names a shelf of books ("Poems", "Collected
        // Stories"); without an author to check against, the reader picks.
        val names = nameTokens(author)
        if (names.isEmpty()) return null
        return hits.firstOrNull { hit ->
            val found = Title.of(hit.title) ?: return@firstOrNull false
            wanted.agreesWith(found) && hit.authors.any { sameAuthor(names, nameTokens(it)) }
        }
    }

    /**
     * Every kept word of one name appears in the other, so `Mary Shelley`
     * is `Shelley, Mary Wollstonecraft`, and one of several authors on the
     * file still counts. A shared first name alone does not: `William
     * Blake` is not `William Wordsworth`.
     */
    private fun sameAuthor(file: Set<String>, hit: Set<String>): Boolean =
        hit.isNotEmpty() && (file.containsAll(hit) || hit.containsAll(file))

    /**
     * The edition to report against: an ebook edition when Hardcover has
     * one with a page count, since that is what is being read, then the
     * physical default, then the most read. A page count is preferred
     * over the format, because without one there is no page to log.
     */
    fun preferredEdition(defaults: HardcoverDefaultEditions): HardcoverEdition? {
        val order = listOfNotNull(defaults.ebook, defaults.physical, defaults.mostRead)
        return order.firstOrNull { (it.pages ?: 0) > 0 } ?: order.firstOrNull()
    }

    /** A title and its main part, the words before a subtitle or a bracket. */
    private data class Title(val whole: String, val main: String) {
        /**
         * The same title, or the same title with a subtitle only one side
         * gives. Two different subtitles are two different books:
         * "Dune: Messiah" is not "Dune: The Graphic Novel".
         */
        fun agreesWith(other: Title): Boolean =
            whole == other.whole ||
                (whole == main && whole == other.main) ||
                (other.whole == other.main && other.whole == main)

        companion object {
            fun of(text: String?): Title? {
                val whole = fold(text) ?: return null
                // Gutenberg-style titles put the subtitle after a
                // semicolon: "Frankenstein; Or, The Modern Prometheus".
                val main = fold(text!!.split(':', ';', '(', '[').first()) ?: whole
                return Title(whole, main)
            }
        }
    }

    /**
     * The words in a name that are worth comparing. Initials are dropped:
     * two authors sharing a "J" share nothing.
     */
    private fun nameTokens(name: String?): Set<String> =
        fold(name)?.split(' ').orEmpty().filter { it.length >= 3 }.toSet()

    private fun fold(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val flattened = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase()
        val kept = buildString(flattened.length) {
            for (character in flattened) {
                when {
                    character.isLetterOrDigit() -> append(character)
                    isNotEmpty() && last() != ' ' -> append(' ')
                }
            }
        }
        return kept.trim().ifEmpty { null }
    }

    private val COMBINING = Regex("\\p{Mn}+")
}
