package com.chmouel.liseur.data.komga

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.FileSource
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import okhttp3.Request

/**
 * Where a book's file is on Komga.
 *
 * The URL is built from the book's id rather than from anything stored,
 * because Komga's is fixed and derivable: a row saved by an older
 * catalog pass, or one whose link was never recorded, still downloads.
 */
class KomgaFileSource(private val http: RemoteHttp = RemoteHttp()) : FileSource {

    override fun downloadRequest(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): Request.Builder? {
        val id = book.remoteUuid ?: return null
        return http.request(KomgaUrl.api(baseUrl, "/api/v1/books/$id/file"), credentials)
    }
}
