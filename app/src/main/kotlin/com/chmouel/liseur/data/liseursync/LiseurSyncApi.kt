package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.remote.RemoteUrl

/** The liseur-sync API, as paths rather than strings scattered about. */
object LiseurSyncApi {

    const val HEALTH = "/healthz"
    const val LOGIN = "/v1/login"
    const val TOKENS = "/v1/tokens"

    /** What the calling token itself may do (ADR-0016). */
    const val TOKEN = "/v1/token"
    const val RESOLVE = "/v1/works/resolve"
    const val MERGE = "/v1/works/merge"
    const val OPS = "/v1/ops"
    const val CHANGES = "/v1/changes"
    const val HEADS = "/v1/heads"
    const val SESSIONS = "/v1/sessions"

    /** The folders the server watches, which is where a catalog starts. */
    const val FOLDERS = "/v1/folders"

    /** The scopes the app asks a token for. */
    const val SCOPE_SYNC = "sync"
    const val SCOPE_INSIGHTS = "read-insights"
    const val SCOPE_LIBRARY_READ = "library-read"
    const val SCOPE_LIBRARY_MANAGE = "library-manage"

    /**
     * Every scope a full account wants, in one mint.
     *
     * Books still reach a liseur-sync server only by being put in a
     * watched folder. The one catalog thing the app can now write is a
     * reader's claim about which series a book belongs to.
     */
    val SCOPES_FULL = listOf(
        SCOPE_SYNC,
        SCOPE_INSIGHTS,
        SCOPE_LIBRARY_READ,
        SCOPE_LIBRARY_MANAGE,
    )

    fun url(baseUrl: String, path: String): String = RemoteUrl.api(baseUrl, path)

    fun changes(baseUrl: String, since: Long, limit: Int): String =
        url(baseUrl, "$CHANGES?since=$since&limit=$limit")

    /** The newest positions recorded for one book, newest first. */
    fun positions(baseUrl: String, workId: String, limit: Int): String =
        url(baseUrl, "/v1/works/$workId/positions?limit=$limit")

    /** One page of the folders this server watches. */
    fun folders(baseUrl: String, after: String?, limit: Int): String =
        url(
            baseUrl,
            "$FOLDERS?limit=$limit" +
                (after?.let { "&after=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""),
        )

    fun folderBooks(baseUrl: String, folder: String, cursor: String?, limit: Int): String =
        url(
            baseUrl,
            "$FOLDERS/$folder/books?limit=$limit" +
                (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""),
        )

    fun folderSearch(baseUrl: String, folder: String, query: String): String =
        url(
            baseUrl,
            "$FOLDERS/$folder/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8"),
        )

    /** Joins a catalog book to the caller's own work (ADR-0006). */
    fun resolveBook(baseUrl: String, bookId: String): String =
        url(baseUrl, "/v1/books/$bookId/resolve")

    fun bookDownload(baseUrl: String, bookId: String): String =
        url(baseUrl, "/v1/books/$bookId/download")

    /** The book itself. */
    fun book(baseUrl: String, bookId: String): String =
        url(baseUrl, "/v1/books/$bookId")

    fun bookSeries(baseUrl: String, bookId: String): String =
        url(baseUrl, "/v1/books/$bookId/series")

    fun bookSeries(
        baseUrl: String,
        bookId: String,
        scope: String,
        clientTs: String? = null,
        ifUpdatedAt: String? = null,
    ): String =
        url(
            baseUrl,
            "/v1/books/$bookId/series?scope=" +
                java.net.URLEncoder.encode(scope, "UTF-8") +
                (clientTs?.let { "&client_ts=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "") +
                (ifUpdatedAt?.let {
                    "&if_updated_at=" + java.net.URLEncoder.encode(it, "UTF-8")
                } ?: ""),
        )

    fun seriesOrder(baseUrl: String, seriesId: String): String =
        url(baseUrl, "/v1/entities/series/$seriesId/order")

    /** What a reader calls a series, over what the scan called it. */
    fun seriesName(baseUrl: String, seriesId: String): String =
        url(baseUrl, "/v1/entities/series/$seriesId/name")

    fun seriesName(baseUrl: String, seriesId: String, scope: String): String =
        url(
            baseUrl,
            "/v1/entities/series/$seriesId/name?scope=" +
                java.net.URLEncoder.encode(scope, "UTF-8"),
        )

    fun bookCover(baseUrl: String, bookId: String): String =
        url(baseUrl, "/v1/books/$bookId/cover")

    fun insightsSummary(baseUrl: String, range: String): String =
        url(baseUrl, "/v1/insights/summary?range=$range")

    fun insightsCalendar(baseUrl: String, year: Int): String =
        url(baseUrl, "/v1/insights/calendar?year=$year")

    fun workInsights(baseUrl: String): String =
        url(baseUrl, "/v1/insights/works")

    fun workInsights(baseUrl: String, workId: String): String =
        url(baseUrl, "/v1/insights/works/$workId")
}
