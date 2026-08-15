package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.FileSource
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import okhttp3.Request

/**
 * Where a book's file is on liseur-sync.
 *
 * Built from the book's id rather than from anything stored, because the
 * route is fixed: a row saved by an older catalog pass still downloads.
 * Ranges and conditional requests are the server's; resuming and the
 * partial file are the download worker's, unchanged.
 */
class LiseurSyncFileSource(private val http: RemoteHttp = RemoteHttp()) : FileSource {

    override fun downloadRequest(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): Request.Builder? {
        val id = book.remoteUuid ?: return null
        return http.request(LiseurSyncApi.bookDownload(baseUrl, id), credentials)
    }
}
