package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.FileSource
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import okhttp3.Request

/**
 * Where a book's file is on calibre-web.
 *
 * The OPDS entry usually carries an acquisition link, and that is what
 * gets used. The fallback exists because entries fetched by older
 * versions of the app were stored without one; calibre-web's download
 * URL is derivable from the integer book id, which those rows do have.
 */
class CalibreFileSource(private val http: RemoteHttp = RemoteHttp()) : FileSource {

    override fun downloadRequest(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): Request.Builder? {
        val href = book.downloadHref
            ?: book.remoteBookId?.let { "/opds/download/$it/epub/" }
            ?: return null
        return http.request(CalibreUrl.resolve(baseUrl, href), credentials)
    }
}
