package com.chmouel.liseur.reader.footnotes

/**
 * What counts as a note, in one place.
 *
 * Two things need this answer and they must not disagree. [FootnoteResolver]
 * asks it of a Jsoup document, off the main thread, to decide whether a tap
 * pops a card up or throws the reader into another chapter. [FootnoteLayout]
 * asks it of the live DOM, inside the book's own web view, to decide what to
 * hide. A book where one says yes and the other says no is a book that shows
 * the note twice or not at all, so the vocabulary lives here and both read it
 * from the same set.
 *
 * The words are the three ways a book says "this is a note", which between
 * them cover everything worth opening: the EPUB vocabulary, the ARIA one, and
 * the bare `<aside>` that the EPUB specification itself suggests notes be
 * written as.
 */
internal object NoteVocabulary {

    /** `epub:type` words that name a note. */
    val NOTE_TYPES: Set<String> = setOf("footnote", "endnote", "rearnote", "note")

    /** ARIA roles that name a note. */
    val NOTE_ROLES: Set<String> = setOf("doc-footnote", "doc-endnote")

    /** The element a note is written as when it is not labelled at all. */
    const val NOTE_TAG: String = "aside"

    /** `epub:type` words that name a reference *to* a note. */
    val REF_TYPES: Set<String> = setOf("noteref")

    /** ARIA roles that name a reference to a note. */
    val REF_ROLES: Set<String> = setOf("doc-noteref")

    /**
     * Whether an element carrying [epubType], [role] and [tagName] is a note.
     *
     * Children are never consulted: a chapter that happens to contain a note
     * is not a note, and a note nested in a section is found by its id.
     */
    fun isNote(epubType: String, role: String, tagName: String): Boolean =
        names(epubType, NOTE_TYPES) ||
            names(role, NOTE_ROLES) ||
            tagName.lowercase() == NOTE_TAG

    /** Whether an anchor carrying [epubType] and [role] points at a note. */
    fun isNoteRef(epubType: String, role: String): Boolean =
        names(epubType, REF_TYPES) || names(role, REF_ROLES)

    /**
     * Whether [attribute] names any of [vocabulary].
     *
     * The value is a space-separated list, and each word may carry a
     * namespace prefix the sanitiser never flattened, so only what follows
     * the last colon is compared.
     */
    private fun names(attribute: String, vocabulary: Set<String>): Boolean =
        attribute.split(' ').any { it.substringAfterLast(':') in vocabulary }
}
