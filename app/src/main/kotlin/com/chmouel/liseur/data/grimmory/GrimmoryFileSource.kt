package com.chmouel.liseur.data.grimmory

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.FileSource
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import okhttp3.Request

/**
 * Where a book's file is on Grimmory's Komga-compatible shim.
 *
 * The URL is built from the book's id rather than from anything stored,
 * because the shim's shape is fixed and derivable: a row saved by an
 * older catalog pass, or one whose link was never recorded, still
 * downloads.
 */
class GrimmoryFileSource(private val http: RemoteHttp = RemoteHttp()) : FileSource {

    override fun downloadRequest(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): Request.Builder? {
        // Checked again on the way out, not just on the way in. A stored
        // row is not a trusted one: it may have been written by an older
        // version, restored from a backup, or edited underneath us, and
        // this id is about to become a URL path segment.
        val id = GrimmoryId.parse(book.remoteUuid) ?: return null
        return http.request(GrimmoryUrl.api(baseUrl, "/api/v1/books/$id/file"), credentials)
    }
}
