package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.BookMetadataDao
import com.chmouel.liseur.data.library.descriptionText
import com.chmouel.liseur.domain.MetadataSource

/**
 * The `catalog` row for [book] filed under [bookUrl], or null when the
 * catalog said nothing worth a row.
 */
internal fun catalogMetadataRow(bookUrl: String, book: RemoteBook): BookMetadata? {
    val snapshot = book.metadata ?: return null
    val text = snapshot.copy(description = descriptionText(snapshot.description, book.descriptionHtml))
    return BookMetadata.of(bookUrl, MetadataSource.CATALOG, text)
        .takeUnless { it == BookMetadata(bookUrl, MetadataSource.CATALOG.key) }
}

/**
 * Replaces the catalog rows of [books], keyed by the library URL each
 * is filed under. A field the catalog stopped sending is cleared with
 * the rest of the row, so a description deleted on the server goes here
 * too. Rows that did not change are not written.
 */
internal suspend fun BookMetadataDao.storeCatalog(books: Map<String, RemoteBook>) {
    books.keys.chunked(CATALOG_CHUNK).forEach { urls ->
        val stored = ofSource(urls, MetadataSource.CATALOG.key).associateBy { it.bookUrl }
        val gone = mutableListOf<String>()
        urls.forEach { url ->
            val row = catalogMetadataRow(url, books.getValue(url))
            val old = stored[url]
            when {
                row == null -> if (old != null) gone += url
                row != old -> upsert(row)
            }
        }
        if (gone.isNotEmpty()) deleteSource(gone, MetadataSource.CATALOG.key)
    }
}

/** Under SQLite's 999 bound variables, with room for the source. */
private const val CATALOG_CHUNK = 500
