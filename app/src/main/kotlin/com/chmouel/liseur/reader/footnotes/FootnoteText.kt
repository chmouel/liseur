package com.chmouel.liseur.reader.footnotes

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * Getting a note ready to be read where it was referenced.
 *
 * A note written for the back of the book carries furniture that only makes
 * sense back there. The worst of it is the backlink — the little `↩` that
 * exists solely to undo a journey the reader is no longer making. Popped up
 * in place it is at best noise and at worst a trap, because it is the one
 * thing on the card that would throw the reader out of the page again.
 *
 * Images go too. The card is text, and a note whose point is a picture is
 * exactly the note that wants opening in full.
 *
 * What is left is narrowed to the handful of tags that survive the trip into
 * an `AnnotatedString`: emphasis, sub- and superscripts, paragraphs, breaks.
 * Anything else would be dropped later anyway, and dropping it here keeps the
 * decision in a function that can be tested without an emulator.
 */
object FootnoteText {

    /**
     * Characters a backlink is made of.
     *
     * A backlink is recognised by what it says rather than by how it is
     * marked, because by the time Readium has sanitised a note the `role` and
     * `epub:type` that named it are gone and only the arrow is left.
     */
    private const val BACKLINK_CHARS = "↩↰⏎←↑⤴«‹^"

    /** Tags an [androidx.compose.ui.text.AnnotatedString] can still show. */
    private val KEEP: Safelist = Safelist.none()
        .addTags(
            "b", "strong", "i", "em", "u", "s", "sup", "sub",
            "p", "br", "span", "small", "cite", "q", "blockquote",
        )

    /**
     * [html] with its back-matter furniture removed, or null if nothing is
     * left worth showing.
     */
    fun forCard(html: String): String? {
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null

        document.select("img, svg, figure").remove()
        document.select("a").forEach { anchor ->
            if (isBacklink(anchor.attr("role"), anchor.attr("epub:type"), anchor.text())) {
                anchor.remove()
            } else {
                // A note may legitimately quote a cross-reference. The words
                // stay; the target does not, because a card is not a place
                // to start a second journey from.
                anchor.unwrap()
            }
        }

        val cleaned = Jsoup.clean(document.body().html(), KEEP)
        val trimmed = collapse(cleaned)
        return trimmed.takeIf { plainText(it).isNotBlank() }
    }

    /** [html] as the words alone, for talkback and for tests. */
    fun plainText(html: String): String =
        collapse(Jsoup.parse(html).wholeText())

    private fun isBacklink(role: String, epubType: String, text: String): Boolean {
        if (role.split(' ').any { it == "doc-backlink" }) return true
        if (epubType.split(' ').any { it.substringAfterLast(':') == "backlink" }) return true
        // Variation selectors and word joiners ride along with these arrows
        // often enough that stripping them is what makes the test pass on a
        // real book rather than on a hand-written one.
        val bare = text.filterNot { it.isWhitespace() || it in "\uFE0E\uFE0F\u200B\u2060\uFEFF" }
        return bare.isNotEmpty() && bare.all { it in BACKLINK_CHARS }
    }

    private fun collapse(text: String): String =
        text.replace('\u00A0', ' ')
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
