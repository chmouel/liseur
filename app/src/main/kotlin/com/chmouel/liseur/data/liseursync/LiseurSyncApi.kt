package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.remote.RemoteUrl

/** The liseur-sync API, as paths rather than strings scattered about. */
object LiseurSyncApi {

    const val HEALTH = "/healthz"
    const val LOGIN = "/v1/login"
    const val TOKENS = "/v1/tokens"
    const val RESOLVE = "/v1/works/resolve"
    const val MERGE = "/v1/works/merge"
    const val OPS = "/v1/ops"
    const val CHANGES = "/v1/changes"
    const val HEADS = "/v1/heads"
    const val SESSIONS = "/v1/sessions"

    /** What a token is allowed to do. One scope per token, by design. */
    const val SCOPE_SYNC = "sync"
    const val SCOPE_INSIGHTS = "read-insights"

    fun url(baseUrl: String, path: String): String = RemoteUrl.api(baseUrl, path)

    fun changes(baseUrl: String, since: Long, limit: Int): String =
        url(baseUrl, "$CHANGES?since=$since&limit=$limit")

    /** The newest positions recorded for one book, newest first. */
    fun positions(baseUrl: String, workId: String, limit: Int): String =
        url(baseUrl, "/v1/works/$workId/positions?limit=$limit")

    fun insightsSummary(baseUrl: String, range: String): String =
        url(baseUrl, "/v1/insights/summary?range=$range")

    fun insightsCalendar(baseUrl: String, year: Int): String =
        url(baseUrl, "/v1/insights/calendar?year=$year")

    fun workInsights(baseUrl: String): String =
        url(baseUrl, "/v1/insights/works")

    fun workInsights(baseUrl: String, workId: String): String =
        url(baseUrl, "/v1/insights/works/$workId")
}
