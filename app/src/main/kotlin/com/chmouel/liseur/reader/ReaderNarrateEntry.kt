package com.chmouel.liseur.reader

/**
 * The read-aloud item in the reader's ⋮ menu, shown while this book is
 * not being read: [Start] reads the page aloud from here, [SetUp] opens
 * the voice sheet first, since nothing is set up to read it with.
 */
internal enum class ReaderNarrateEntry {
    Start,
    SetUp,
    ;

    companion object {
        /** Null hides the item: no read aloud in this build, a book that is not reflowable text. */
        fun of(available: Boolean, reflowable: Boolean, configured: Boolean): ReaderNarrateEntry? =
            when {
                !available || !reflowable -> null
                configured -> Start
                else -> SetUp
            }
    }
}
