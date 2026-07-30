package com.chmouel.liseur.data.remote

/**
 * A book as a server's catalog describes it.
 *
 * The common denominator of an OPDS entry and a Komga `BookDto`: enough
 * to show the book in the library, fetch its cover, and download it.
 * Anything a single server knows and no other does stays inside that
 * server's package.
 */
data class RemoteBook(
    /**
     * The book's identity on the server, and the stable half of the
     * library's own [com.chmouel.liseur.data.db.Book.url]. A calibre-web
     * UUID, or a Komga book id.
     */
    val remoteId: String,
    val title: String,
    val author: String?,
    /** Where the cover is, absolute or relative to the server. */
    val coverHref: String?,
    /** Where the file is, absolute or relative to the server. */
    val downloadHref: String?,
    val sizeBytes: Long? = null,
    /** When the server last changed the book, in epoch milliseconds. */
    val updatedAt: Long? = null,
    /**
     * How many pages the server counts in the book, when it counts them
     * at all. Komga needs one back when a locator cannot be pushed; OPDS
     * never mentions it.
     */
    val pageCount: Int? = null,
    /**
     * calibre's integer book id, which its delete route needs and
     * nothing else uses.
     */
    val calibreBookId: Int? = null,
)
