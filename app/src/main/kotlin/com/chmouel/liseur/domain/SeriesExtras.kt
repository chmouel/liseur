package com.chmouel.liseur.domain

/**
 * What a server knows about a series that its books do not say.
 *
 * Decoration, every field of it, and every field optional: only Komga
 * has any of this, and a screen that looked unfinished without it would
 * make calibre-web and a folder of files feel like second-class ways to
 * read. Everything that matters — the volumes, their order, and where
 * the reader is in them — is worked out from the books themselves.
 */
data class SeriesExtras(
    val summary: String? = null,
    /** Komga's own word: `ONGOING`, `ENDED`, `ABANDONED`, `HIATUS`. */
    val status: String? = null,
    /** How many books the series is meant to have, when anyone counted. */
    val totalBookCount: Int? = null,
)
