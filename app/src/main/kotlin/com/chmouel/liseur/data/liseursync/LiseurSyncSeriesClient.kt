package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.SeriesClaimSync
import com.chmouel.liseur.data.remote.SeriesLayers
import com.chmouel.liseur.data.remote.SeriesName
import com.chmouel.liseur.data.remote.SeriesNameTaken
import org.json.JSONArray
import org.json.JSONObject

class LiseurSyncSeriesClient(
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
) : SeriesClaimSync {

    override suspend fun setPersonalSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
        name: String?,
        index: Double?,
    ): SeriesLayers? {
        val id = book.remoteUuid ?: return null
        val item = name?.let {
            JSONObject().put("name", it).also { json ->
                if (index != null) json.put("position", index)
            }
        }
        val body = JSONObject()
            .put("scope", PERSONAL)
            .put("series", JSONArray().also { array -> if (item != null) array.put(item) })
        book.userSeriesUpdatedAt?.let { body.put("client_ts", SyncOps.formatTime(it)) }
        book.personalSeriesUpdatedAt?.let { body.put("if_updated_at", SyncOps.formatTime(it)) }
        return layers(http.put(LiseurSyncApi.bookSeries(baseUrl, id), credentials, body))
    }

    override suspend fun resetPersonalSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): SeriesLayers? = resetSeries(baseUrl, credentials, book, PERSONAL)

    override suspend fun resetSharedSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): SeriesLayers? = resetSeries(baseUrl, credentials, book, SHARED)

    private suspend fun resetSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
        scope: String,
    ): SeriesLayers? {
        val id = book.remoteUuid ?: return null
        val clientTs = book.userSeriesUpdatedAt?.let(SyncOps::formatTime)
        val ifUpdatedAt = book.personalSeriesUpdatedAt?.let(SyncOps::formatTime)
        return layers(
            http.delete(
                LiseurSyncApi.bookSeries(baseUrl, id, scope, clientTs, ifUpdatedAt),
                credentials,
            ),
        )
    }

    override suspend fun reorderPersonalSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        booksInOrder: List<Book>,
    ): Boolean {
        val first = booksInOrder.firstOrNull() ?: return false
        val seriesId = first.seriesId ?: return false
        // The server places a book it finds unplaced, so the wrong id
        // here would not merely misnumber the shelf, it would refile
        // every book on it — and a series id now names a shelf across
        // the whole library, so a stale one can reach a folder this
        // reader was not even looking at. A shelf whose books disagree
        // about which series they are in is not one this route can
        // speak for.
        if (booksInOrder.any { it.seriesId != seriesId }) return false
        val order = JSONArray().also { array ->
            booksInOrder.forEachIndexed { index, book ->
                val id = book.remoteUuid ?: return false
                array.put(JSONObject().put("book_id", id).put("position", index + 1.0))
            }
        }
        val body = JSONObject().put("scope", PERSONAL).put("order", order)
        http.putNoContent(LiseurSyncApi.seriesOrder(baseUrl, seriesId), credentials, body)
        return true
    }

    override suspend fun renameSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        seriesId: String,
        name: String,
    ): SeriesName? {
        val body = JSONObject().put("scope", PERSONAL).put("name", name)
        // 409 is the server saying another shelf answers to that name.
        // It is a refusal with a meaning, not a failed request, so it is
        // asked for rather than left to become a generic failure.
        val json = try {
            http.put(LiseurSyncApi.seriesName(baseUrl, seriesId), credentials, body, setOf(409))
        } catch (rejection: LiseurSyncRejection) {
            if (rejection.code == 409) throw SeriesNameTaken()
            throw rejection
        }
        return seriesName(json)
    }

    override suspend fun resetSeriesName(
        baseUrl: String,
        credentials: RemoteCredentials,
        seriesId: String,
    ): SeriesName? = seriesName(
        http.delete(LiseurSyncApi.seriesName(baseUrl, seriesId, PERSONAL), credentials),
    )

    companion object {
        const val PERSONAL = "personal"
        const val SHARED = "shared"

        fun seriesName(json: JSONObject): SeriesName? {
            val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null
            return SeriesName(
                name = name,
                scannedName = json.optString("scanned_name").takeIf { it.isNotEmpty() },
                source = json.optString("name_source").takeIf { it.isNotEmpty() },
            )
        }
        fun layers(json: JSONObject): SeriesLayers = SeriesLayers(
            bookId = json.optString("book_id"),
            source = json.optString("source").takeIf { it.isNotEmpty() },
            series = series(json.optJSONArray("series")),
            folder = series(json.optJSONArray("folder")),
            shared = json.optJSONArray("shared")?.let(::series),
            personal = json.optJSONArray("personal")?.let(::series),
            sharedUpdatedAt = SyncOps.parseTime(json.optString("shared_updated_at")),
            personalUpdatedAt = SyncOps.parseTime(json.optString("personal_updated_at")),
            outcome = json.optString("outcome").takeIf { it.isNotEmpty() },
        )
    }
}
