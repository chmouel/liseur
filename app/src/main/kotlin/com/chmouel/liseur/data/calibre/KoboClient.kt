package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import com.chmouel.liseur.data.remote.remoteCall
import com.chmouel.liseur.domain.ReadingState
import com.chmouel.liseur.domain.ReadingStatus
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A reading position the server holds for one book. */
data class RemoteReadingState(val uuid: String, val state: ReadingState)

/**
 * Talks the slice of the Kobo protocol calibre-web implements, which is
 * what carries reading positions between devices.
 *
 * The sync token is the only credential; it goes in the path, so every
 * URL here already carries it.
 */
class KoboClient(private val http: CalibreHttp = CalibreHttp()) {

    /**
     * Walks `/v1/library/sync` to the end, collecting every reading state
     * the server reports. An empty result is normal: a user may have
     * limited syncing to a few shelves.
     *
     * [syncToken] is the opaque marker from the last run; passing it back
     * asks for only what changed. The token to keep for next time is
     * returned alongside the states.
     */
    suspend fun pullReadingStates(
        koboBaseUrl: String,
        syncToken: String?,
    ): RemoteResult<SyncPage> = withContext(Dispatchers.IO) {
        remoteCall { walkSyncFeed(koboBaseUrl, syncToken) }
    }

    private fun walkSyncFeed(koboBaseUrl: String, syncToken: String?): SyncPage {
        val states = mutableMapOf<String, ReadingState>()
        var token = syncToken
        var page = 0

        while (page < MAX_PAGES) {
            val request = http.request("$koboBaseUrl/v1/library/sync", credentials = null)
                .apply { token?.let { header(SYNC_TOKEN_HEADER, it) } }
                .build()

            val finished = http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RemoteHttpFailure(failureForCode(response.code))
                token = response.header(SYNC_TOKEN_HEADER) ?: token
                collectStates(response.body?.string().orEmpty(), into = states)
                !response.header(CONTINUE_HEADER).equals("continue", ignoreCase = true)
            }
            if (finished) return SyncPage(states, token)
            page++
        }
        Log.i(TAG, "Stopped following sync pages after $MAX_PAGES")
        return SyncPage(states, token)
    }

    /**
     * Reads back one book's position, for a book the sync feed did not
     * mention. A book the server has no position for is not a failure:
     * that answer comes back as no state rather than as an error.
     */
    suspend fun readState(koboBaseUrl: String, uuid: String): RemoteResult<ReadingState?> =
        withContext(Dispatchers.IO) {
            remoteCall {
                val request = http.request("$koboBaseUrl/v1/library/$uuid/state", null).build()
                http.client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> null
                        !response.isSuccessful ->
                            throw RemoteHttpFailure(failureForCode(response.code))

                        else -> {
                            // Reading one book back gives a bare array, while
                            // writing takes an object; only the read shape
                            // matters here.
                            val body = response.body?.string().orEmpty()
                            readStates(JSONArray(body)).firstOrNull()
                        }
                    }
                }
            }
        }

    /**
     * Writes a position back.
     *
     * All three of `CurrentBookmark`, `Statistics` and `StatusInfo` are
     * sent even when empty: the server indexes those keys directly and
     * answers 400 if any is missing. `Location` stays null, which leaves
     * whatever kepub anchor the server holds untouched rather than
     * clearing it — Readium has no equivalent to write there.
     */
    suspend fun pushState(
        koboBaseUrl: String,
        uuid: String,
        state: ReadingState,
    ): RemoteResult<Unit> = withContext(Dispatchers.IO) {
        remoteCall {
            val percent = ((state.progression ?: 0.0) * 100).coerceIn(0.0, 100.0)
            val bookmark = JSONObject()
                .put("ProgressPercent", percent)
                .put("ContentSourceProgressPercent", percent)
                .put("Location", JSONObject.NULL)
            val payload = JSONObject().put(
                "ReadingStates",
                JSONArray().put(
                    JSONObject()
                        .put("CurrentBookmark", bookmark)
                        .put("Statistics", JSONObject.NULL)
                        .put("StatusInfo", JSONObject().put("Status", state.status.wireName)),
                ),
            )

            val request = http.request("$koboBaseUrl/v1/library/$uuid/state", null)
                .put(payload.toString().toRequestBody(JSON))
                .build()
            http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RemoteHttpFailure(failureForCode(response.code))
                }
            }
        }
    }

    /**
     * Unreadable JSON here is deliberately fatal rather than treated as
     * an empty feed. Shrugging it off would let the caller commit the new
     * sync token, and the token is one-way: whatever that page held would
     * never be offered again.
     */
    private fun collectStates(body: String, into: MutableMap<String, ReadingState>) {
        val entities = JSONArray(body)
        for (i in 0 until entities.length()) {
            val entity = entities.optJSONObject(i) ?: continue
            for (key in entity.keys()) {
                val wrapped = entity.optJSONObject(key) ?: continue
                val state = wrapped.optJSONObject("ReadingState")
                    ?: wrapped.takeIf { it.has("EntitlementId") && it.has("StatusInfo") }
                    ?: continue
                parseState(state)?.let { into[it.uuid] = it.state }
            }
        }
    }

    private fun readStates(states: JSONArray): List<ReadingState> =
        (0 until states.length()).mapNotNull { i ->
            states.optJSONObject(i)?.let { parseState(it)?.state }
        }

    private fun parseState(state: JSONObject): RemoteReadingState? {
        val uuid = state.optString("EntitlementId").takeIf { it.isNotBlank() } ?: ""
        val bookmark = state.optJSONObject("CurrentBookmark")
        val percent = bookmark?.opt("ProgressPercent")
            ?.takeIf { it != JSONObject.NULL }
            ?.let { (it as? Number)?.toDouble() }
        val status = ReadingStatus.fromWire(
            state.optJSONObject("StatusInfo")?.optString("Status"),
        )
        val modified = parseTimestamp(
            state.optString("LastModified").ifBlank {
                bookmark?.optString("LastModified").orEmpty()
            },
        )
        return RemoteReadingState(
            uuid = uuid,
            state = ReadingState(
                progression = percent?.div(100.0),
                status = status,
                updatedAt = modified,
            ),
        )
    }

    /**
     * calibre-web stamps times as ISO-8601 UTC. An unreadable stamp
     * becomes 0 so the local position wins, which is the safe way round:
     * it never silently throws away reading someone has just done.
     */
    private fun parseTimestamp(value: String): Long {
        if (value.isBlank()) return 0L
        for (pattern in TIMESTAMP_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    companion object {
        private const val TAG = "KoboSync"
        private const val SYNC_TOKEN_HEADER = "x-kobo-synctoken"
        private const val CONTINUE_HEADER = "x-kobo-sync"
        private const val MAX_PAGES = 50
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val TIMESTAMP_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
        )
    }
}

/** Everything one walk of the sync feed found, plus the marker for next time. */
data class SyncPage(
    val states: Map<String, ReadingState>,
    val syncToken: String?,
)
