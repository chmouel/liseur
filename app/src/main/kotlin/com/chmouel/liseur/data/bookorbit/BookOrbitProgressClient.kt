package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A saved zero has an updatedAt; the server's unopened default has none. */
data class BookOrbitFileProgress(
    val percentage: Double,
    val cfi: String?,
    val lastReadAt: String?,
    val textUpdatedAt: String?,
    val updatedAt: String?,
) {
    val isSaved: Boolean get() = updatedAt != null
    val displayTime: String? get() = lastReadAt ?: textUpdatedAt ?: updatedAt

    companion object {
        fun parse(json: JSONObject): BookOrbitFileProgress {
            if (!json.has("cfi")) malformed()
            val percentage = (json.opt("percentage") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: malformed()
            val cfi = json.nullableString("cfi")
            val lastReadAt = json.nullableString("lastReadAt")
            val textUpdatedAt = json.nullableString("textUpdatedAt")
            val updatedAt = json.nullableString("updatedAt")
            if (updatedAt != null &&
                (json.longOrNull("bookFileId")?.takeIf { it > 0 } == null || !json.has("pageNumber"))
            ) malformed()
            if (updatedAt == null) {
                val defaultFields = listOf(
                    "cfi", "pageNumber", "positionSeconds", "mediaOverlayFragment",
                    "mediaOverlaySectionIndex", "koboLocationSource", "koboLocationType",
                    "koboLocationValue", "koboContentSourceProgressPercent", "koreaderProgress",
                    "narrationPercentage", "narrationUpdatedAt", "textUpdatedAt",
                )
                if (percentage != 0.0 || lastReadAt != null ||
                    defaultFields.any { !json.has(it) || !json.isNull(it) }
                ) malformed()
            }
            return BookOrbitFileProgress(percentage, cfi, lastReadAt, textUpdatedAt, updatedAt)
        }

        private fun JSONObject.nullableString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: malformed()
        }

        private fun malformed(): Nothing = throw RemoteHttpFailure(SyncFailure.Malformed)
    }
}

class BookOrbitProgressClient(
    private val http: BookOrbitHttp,
    private val cfis: BookOrbitCfiRepository,
) {
    suspend fun read(context: BookOrbitCfiContext): BookOrbitFileProgress = withContext(Dispatchers.IO) {
        cfis.check(context)
        val url = BookOrbitUrl.api(context.request.baseUrl, "/books/files/${context.fileId}/progress")
        val body = http.getObject(context.request, url)
        if (body.has("bookFileId") && body.longOrNull("bookFileId") != context.fileId) {
            throw RemoteHttpFailure(SyncFailure.Malformed)
        }
        val progress = BookOrbitFileProgress.parse(body)
        cfis.check(context)
        progress
    }
}

/** Transport only; Phase 3 must reconcile a read-back before acknowledging a write. */
class BookOrbitProgressMutationTransport(
    private val http: BookOrbitHttp,
    private val cfis: BookOrbitCfiRepository,
) {
    suspend fun send(
        context: BookOrbitCfiContext,
        payload: JSONObject,
    ): BookOrbitHttp.MutationResult = withContext(Dispatchers.IO) {
        cfis.check(context)
        val url = BookOrbitUrl.api(context.request.baseUrl, "/books/files/${context.fileId}/progress")
        val result = http.postProgress(context.request, url, payload)
        cfis.check(context)
        result
    }
}
