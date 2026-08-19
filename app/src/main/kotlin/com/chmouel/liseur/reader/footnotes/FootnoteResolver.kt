package com.chmouel.liseur.reader.footnotes

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * Lifting a note out of the page it was banished to.
 *
 * Readium recognises one spelling of a footnote, `epub:type="noteref"`, and
 * hands its content back before the tap is even forwarded. That covers books
 * made carefully — Standard Ebooks, most EPUB3 — and misses everything else:
 * a converted EPUB2 whose markers are plain anchors, a book that uses the
 * ARIA vocabulary and not the EPUB one, an `<aside>` sitting quietly at the
 * foot of the chapter.
 *
 * So the target is judged by what it *is* rather than by how it was linked
 * to. Three ways of saying "this is a note" are accepted, because between
 * them they cover every book worth opening:
 *
 *  - `epub:type` naming a note of any kind;
 *  - the ARIA `doc-footnote` / `doc-endnote` role;
 *  - an `<aside>`, which is what the EPUB spec itself suggests notes be.
 *
 * Everything else is a cross-reference, not a note, and the reader asked to
 * go there. That distinction is the whole point of this file: guessing wrong
 * in one direction pops up a chapter, and in the other throws the reader out
 * of the page for a single line of Latin.
 */
object FootnoteResolver {

    /** `epub:type` words that name a note. */
    private val NOTE_TYPES = setOf("footnote", "endnote", "rearnote", "note")

    /** ARIA roles that name a note. */
    private val NOTE_ROLES = setOf("doc-footnote", "doc-endnote")

    /**
     * The note identified by [fragment] in [html], or null if there is none.
     *
     * The returned HTML is sanitised the same way Readium sanitises its own,
     * so both paths hand the same shape of thing to the card and neither can
     * carry a script into it.
     */
    fun noteAt(html: String, fragment: String): String? {
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val element = document.getElementById(fragment) ?: return null
        if (!isNote(element)) return null

        // A list item is the usual home of an endnote, and the number in
        // front of it is drawn by the list, not stored in the note, so its
        // inner HTML loses nothing.
        val content = element.html()
        if (content.isBlank()) return null
        return Jsoup.clean(content, Safelist.relaxed()).takeIf { it.isNotBlank() }
    }

    /**
     * Whether [element] is a note rather than somewhere the reader asked to go.
     *
     * The element's children are not consulted: a chapter that happens to
     * contain a note is not a note, and a note nested in a section is found
     * by its id, never by looking down.
     */
    private fun isNote(element: Element): Boolean {
        // Jsoup lowercases attribute names but keeps the namespace prefix, so
        // the EPUB attribute survives as `epub:type`. `type` alone is the
        // shape it takes once a sanitiser has flattened the namespace.
        val epubType = element.attr("epub:type").ifEmpty { element.attr("type") }
        if (epubType.split(' ').any { it.substringAfterLast(':') in NOTE_TYPES }) return true
        if (element.attr("role").split(' ').any { it in NOTE_ROLES }) return true
        return element.normalName() == "aside"
    }
}
