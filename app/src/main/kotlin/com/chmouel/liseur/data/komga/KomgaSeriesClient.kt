package com.chmouel.liseur.data.komga

import android.util.Log
import com.chmouel.liseur.data.remote.RemoteCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What Komga knows about a series beyond the books in it. */
data class KomgaSeries(
    val title: String?,
    val summary: String?,
    val status: String?,
    val totalBookCount: Int?,
)

/**
 * Asks Komga about one series.
 *
 * Only ever called because someone opened a series, never during a
 * catalog refresh: a request per series on every refresh would be a
 * request per series for something nobody is looking at.
 */
class KomgaSeriesClient(private val http: KomgaHttp = KomgaHttp()) {

    suspend fun series(
        baseUrl: String,
        credentials: RemoteCredentials,
        seriesId: String,
    ): KomgaSeries? = withContext(Dispatchers.IO) {
        // Everything here is decoration, so a failure is an absence and
        // never an error the reader has to be told about.
        runCatching {
            parse(http.getObject(KomgaUrl.api(baseUrl, "/api/v1/series/$seriesId"), credentials))
        }.onFailure {
            Log.i(TAG, "Could not read the series", it)
        }.getOrNull()
    }

    internal fun parse(json: JSONObject): KomgaSeries {
        val metadata = json.optJSONObject("metadata")
        return KomgaSeries(
            title = metadata?.stringOrNull("title") ?: json.stringOrNull("name"),
            summary = metadata?.stringOrNull("summary"),
            status = metadata?.stringOrNull("status"),
            totalBookCount = metadata?.optInt("totalBookCount")?.takeIf { it > 0 },
        )
    }

    private companion object {
        const val TAG = "komga-series"
    }
}
