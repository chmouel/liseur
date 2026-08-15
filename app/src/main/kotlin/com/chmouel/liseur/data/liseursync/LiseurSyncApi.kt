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
    const val LIBRARIES = "/v1/libraries"

    /** The scopes the app asks a token for. */
    const val SCOPE_SYNC = "sync"
    const val SCOPE_INSIGHTS = "read-insights"
    const val SCOPE_LIBRARY_READ = "library-read"
    const val SCOPE_LIBRARY_MANAGE = "library-manage"

    /** Every scope a full account wants, in one mint. */
    val SCOPES_FULL = listOf(
        SCOPE_SYNC,
        SCOPE_INSIGHTS,
        SCOPE_LIBRARY_READ,
        SCOPE_LIBRARY_MANAGE,
    )

    /** What is left to ask for when a server will not grant manage. */
    val SCOPES_READ = SCOPES_FULL - SCOPE_LIBRARY_MANAGE

    fun url(baseUrl: String, path: String): String = RemoteUrl.api(baseUrl, path)

    fun changes(baseUrl: String, since: Long, limit: Int): String =
        url(baseUrl, "$CHANGES?since=$since&limit=$limit")

    /** The newest positions recorded for one book, newest first. */
    fun positions(baseUrl: String, workId: String, limit: Int): String =
        url(baseUrl, "/v1/works/$workId/positions?limit=$limit")

    fun libraryBooks(baseUrl: String, library: String, cursor: String?, limit: Int): String =
        url(
            baseUrl,
            "$LIBRARIES/$library/books?limit=$limit" +
                (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""),
        )

    fun librarySearch(baseUrl: String, library: String, query: String): String =
        url(
            baseUrl,
            "$LIBRARIES/$library/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8"),
        )

    /** Joins a catalog book to the caller's own work (ADR-0006). */
    fun resolveBook(baseUrl: String, bookId: String): String =
        url(baseUrl, "/v1/books/$bookId/resolve")

    fun bookDownload(baseUrl: String, bookId: String): String =
        url(baseUrl, "/v1/books/$bookId/download")

    fun bookCover(baseUrl: String, bookId: String): String =
        url(baseUrl, "/v1/books/$bookId/cover")

    fun upload(baseUrl: String, library: String): String =
        url(baseUrl, "$LIBRARIES/$library/upload")

    fun ingestJob(baseUrl: String, jobId: String): String =
        url(baseUrl, "/v1/ingest/jobs/$jobId")

    fun insightsSummary(baseUrl: String, range: String): String =
        url(baseUrl, "/v1/insights/summary?range=$range")

    fun insightsCalendar(baseUrl: String, year: Int): String =
        url(baseUrl, "/v1/insights/calendar?year=$year")

    fun workInsights(baseUrl: String): String =
        url(baseUrl, "/v1/insights/works")

    fun workInsights(baseUrl: String, workId: String): String =
        url(baseUrl, "/v1/insights/works/$workId")
}
