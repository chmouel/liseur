package com.chmouel.liseur.reader

import com.chmouel.liseur.translate.PageTranslationModes

/** The translation item in the reader's ⋮ menu; [wholeBook] is whether a long press opens whole-book translation. */
internal sealed interface ReaderTranslateEntry {
    val wholeBook: Boolean

    /** "Translate into ‹target›": nothing is left to choose. */
    data class Into(val languages: PageTranslationModes.Mode, override val wholeBook: Boolean) : ReaderTranslateEntry

    /** "Translate book": opens Translation settings, since something has to be set up or chosen first. */
    data class SetUp(override val wholeBook: Boolean) : ReaderTranslateEntry

    companion object {
        /**
         * Null hides the item: no translation in this build, a book that
         * is not reflowable text, or a page already translated.
         */
        fun of(
            available: Boolean,
            reflowable: Boolean,
            translated: Boolean,
            languages: PageTranslationModes.Mode?,
            wholeBookEligible: Boolean,
            wholeBookSaved: Boolean,
        ): ReaderTranslateEntry? {
            if (!available || !reflowable || translated) return null
            val wholeBook = wholeBookEligible || wholeBookSaved
            return languages?.let { Into(it, wholeBook) } ?: SetUp(wholeBook)
        }
    }
}
