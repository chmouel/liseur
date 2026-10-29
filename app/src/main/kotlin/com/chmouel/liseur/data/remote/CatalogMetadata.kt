package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.BookMetadataDao
import com.chmouel.liseur.data.db.CatalogWrite
import com.chmouel.liseur.data.library.descriptionText
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource

/**
 * The `catalog` row for [book] filed under [bookUrl], or null when the
 * catalog said nothing worth a row.
 */
internal fun catalogMetadataRow(bookUrl: String, book: RemoteBook): BookMetadata? {
    val snapshot = book.metadata ?: MetadataSnapshot()
    // Komga and BookOrbit report pages beside the snapshot, not in it.
    val text = snapshot.copy(
        description = descriptionText(snapshot.description, book.descriptionHtml),
        pageCount = snapshot.pageCount ?: book.pageCount,
    )
    return BookMetadata.of(bookUrl, MetadataSource.CATALOG, text)
        .takeUnless { it == BookMetadata(bookUrl, MetadataSource.CATALOG.key) }
}

/** A catalog entry and the [com.chmouel.liseur.data.db.Book.linkGeneration] its book had when the fetch began. */
internal data class CatalogRead(val remote: RemoteBook, val linkGeneration: Long)

/**
 * Replaces the catalog rows of [books], keyed by the library URL each
 * is filed under. A field the catalog stopped sending is cleared with
 * the rest of the row, so a description deleted on the server goes here
 * too. Rows that did not change are not written, and neither is any
 * book whose link moved since its entry was read.
 */
internal suspend fun BookMetadataDao.storeCatalog(books: Map<String, CatalogRead>) {
    books.keys.chunked(CATALOG_CHUNK).forEach { urls ->
        replaceCatalog(
            urls.associateWith { url ->
                val read = books.getValue(url)
                CatalogWrite(read.remote.remoteId, read.linkGeneration, catalogMetadataRow(url, read.remote))
            },
        )
    }
}

/** Under SQLite's 999 bound variables. */
private const val CATALOG_CHUNK = 500
