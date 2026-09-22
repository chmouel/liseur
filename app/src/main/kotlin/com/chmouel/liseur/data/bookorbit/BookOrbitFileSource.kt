package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.FileSource
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.RemoteHttp
import okhttp3.Request

/**
 * Asking BookOrbit for a book's bytes.
 *
 * The file id comes from the stored binding through `Book.downloadHref`,
 * which this app wrote when it chose the file — not from the server's
 * idea of which EPUB is primary today. A book whose chosen file has gone
 * from the server has no link and is handed back as "nothing to fetch"
 * rather than as whichever file replaced it.
 *
 * The request is signed here rather than by a shared interceptor because
 * the download worker owns its own client and path. Asking the session
 * for a token is a blocking call on the worker's IO thread on purpose:
 * it is the one place a download can notice that its access token
 * expired and renew it before fetching a whole book. There is no 401
 * retry afterwards, so a request built while the session is unreachable
 * is not built at all.
 */
class BookOrbitFileSource(private val session: BookOrbitSession) : FileSource {

    private val auth = BookOrbitNetworkAuth(session)
    private val transport = RemoteHttp(
        RemoteHttp.forDownloads().newBuilder()
            .addInterceptor(auth)
            // An authenticated redirect is another server's decision
            // about where a bearer goes. Store the canonical address
            // instead of following it.
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    )

    override fun downloadRequest(
        server: RemoteServer,
        credentials: com.chmouel.liseur.data.remote.RemoteCredentials,
        book: Book,
    ): Request.Builder? {
        val fileId = BookOrbitUrl.fileIdOf(book.downloadHref) ?: return null
        val context = BookOrbitRequestContext.from(server) ?: return null
        return Request.Builder()
            .url(BookOrbitUrl.api(context.baseUrl, "/books/files/$fileId/download"))
            .tag(BookOrbitRequestContext::class.java, context)
    }

    override fun downloadHttp(): RemoteHttp = transport
}
