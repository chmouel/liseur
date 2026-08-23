package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.remote.RemoteUrl
import java.time.LocalDate

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

    /** Adding a book to a folder that accepts one (ADR-0023). */
    const val SCOPE_LIBRARY_UPLOAD = "library-upload"

    /** Taking a book back out of one (ADR-0025). */
    const val SCOPE_LIBRARY_DELETE = "library-delete"

    /**
     * Every scope a full account wants, in one mint.
     *
     * Books reach a liseur-sync server by being put in a watched folder,
     * and `library-upload` is the app asking to be one of the things
     * that can put one there — for a folder an administrator marked, and
     * no other (ADR-0023). `library-delete` is the same folder in the
     * other direction (ADR-0025), and separate because sending your own
     * book and destroying everyone's are different questions. An older
     * server that does not know a scope refuses to mint it, which is
     * handled where the mint is read.
     */
    val SCOPES_FULL = listOf(
        SCOPE_SYNC,
        SCOPE_INSIGHTS,
        SCOPE_LIBRARY_READ,
        SCOPE_LIBRARY_MANAGE,
        SCOPE_LIBRARY_UPLOAD,
        SCOPE_LIBRARY_DELETE,
    )

    fun url(baseUrl: String, path: String): String = RemoteUrl.api(baseUrl, path)

    fun changes(baseUrl: String, since: Long, limit: Int): String =
        url(baseUrl, "$CHANGES?since=$since&limit=$limit")

    /** The newest positions recorded for one book, newest first. */
    fun positions(baseUrl: String, workId: String, limit: Int): String =
        url(baseUrl, "/v1/works/$workId/positions?limit=$limit")

    /**
     * Where a book is deleted from a folder that accepts one
     * (ADR-0025). `forget_reading` is the caller's own reading and only
     * theirs, and is left off unless the reader asked.
     */
    fun deleteBook(baseUrl: String, bookId: String, forgetReading: Boolean): String {
        val query = if (forgetReading) "?forget_reading=true" else ""
        return url(baseUrl, "/v1/books/$bookId$query")
    }

    /** Where a book is added to a folder that accepts one (ADR-0023). */
    fun uploadBook(baseUrl: String, folderId: String): String =
        url(baseUrl, "$FOLDERS/$folderId/books")

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

    /**
     * Insights for one span, named by its first and last day.
     *
     * Days rather than a count of them: the server resolves a count
     * against the moment the request arrives, which reaches back into a
     * further partial day and would not match what the device counted
     * for itself. Both bounds are inclusive, and a null [from] asks for
     * everything on record.
     */
    fun insightsSummary(baseUrl: String, from: LocalDate?, to: LocalDate): String =
        url(baseUrl, "/v1/insights/summary" + span(from, to))

    fun insightsCalendar(baseUrl: String, year: Int): String =
        url(baseUrl, "/v1/insights/calendar?year=$year")

    /**
     * A calendar bounded by days rather than by a year.
     *
     * A server that predates these parameters ignores them and answers
     * with the current year instead, which is why the caller checks the
     * echoed bounds before trusting the span.
     */
    fun insightsCalendar(baseUrl: String, from: LocalDate, to: LocalDate): String =
        url(baseUrl, "/v1/insights/calendar?from=$from&to=$to")

    /** Every work at once, over the same span as the summary. */
    fun allWorkInsights(baseUrl: String, from: LocalDate?, to: LocalDate): String =
        url(baseUrl, "/v1/insights/works" + span(from, to))

    fun workInsights(baseUrl: String, workId: String): String =
        url(baseUrl, "/v1/insights/works/$workId")

    /**
     * An insights span as query parameters.
     *
     * A span with no beginning is named rather than left out. Omitting
     * it would leave the summary to fall back on its own default of
     * thirty days and answer a question nobody asked; `range=all` says
     * what is meant, and a server too old to know the word says so by
     * reporting a `range_days` that is not nought.
     */
    private fun span(from: LocalDate?, to: LocalDate): String =
        if (from == null) "?range=all" else "?from=$from&to=$to"
}
