package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.remote.RemoteCatalogRepository

/**
 * Files a book into a series by hand, or out of every series when
 * [name] is blank.
 *
 * Out of every series is a decision and is stored as one: a book
 * taken off a shelf must not be put back on it by the next catalog
 * refresh, which is exactly what a cleared field would let happen.
 */
suspend fun fileBookInSeries(
    bookDao: BookDao,
    catalog: RemoteCatalogRepository,
    book: Book,
    name: String?,
    index: Double?,
) {
    val clean = name?.trim()?.takeIf { it.isNotEmpty() }
    bookDao.setSeriesOverride(book.url, clean, index)
    if (book.browseServerId != null) {
        bookDao.settleBrowseSeriesEdit(book.url)
    } else {
        catalog.retryPendingSeriesClaims()
    }
}
