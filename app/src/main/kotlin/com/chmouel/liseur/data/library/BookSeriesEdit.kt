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
 *
 * With [expected], nothing is written unless the book is still that
 * generation, checked in the same [inTransaction] as the write.
 */
suspend fun fileBookInSeries(
    bookDao: BookDao,
    catalog: RemoteCatalogRepository,
    book: Book,
    name: String?,
    index: Double?,
    expected: Book? = null,
    inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
): Boolean {
    val clean = name?.trim()?.takeIf { it.isNotEmpty() }
    var written = false
    inTransaction {
        if (expected != null && bookDao.getByUrl(book.url)?.sameGeneration(expected) != true) return@inTransaction
        bookDao.setSeriesOverride(book.url, clean, index)
        written = true
    }
    if (!written) return false
    if (book.browseServerId != null) {
        bookDao.settleBrowseSeriesEdit(book.url)
    } else {
        catalog.retryPendingSeriesClaims()
    }
    return true
}
