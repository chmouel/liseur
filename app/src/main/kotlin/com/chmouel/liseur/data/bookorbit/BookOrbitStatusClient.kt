package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import org.json.JSONObject

/** Reads or changes a book-level status without touching file progress. */
class BookOrbitStatusClient(
    private val http: BookOrbitHttp,
    private val cfis: BookOrbitCfiRepository,
) {
    suspend fun read(context: BookOrbitCfiContext): BookOrbitStatus? {
        cfis.check(context)
        val url = detailUrl(context)
        val detail = http.getObject(context.request, url)
        if (detail.longOrNull("id") != context.bookId) malformed()
        val status = when {
            !detail.has("readStatus") || detail.isNull("readStatus") -> null
            detail.optJSONObject("readStatus") != null ->
                BookOrbitBooks.parseStatus(checkNotNull(detail.optJSONObject("readStatus"))) ?: malformed()
            else -> malformed()
        }
        cfis.check(context)
        return status
    }

    suspend fun set(
        context: BookOrbitCfiContext,
        bytes: ByteArray,
    ): BookOrbitHttp.MutationResult {
        cfis.check(context)
        val result = http.patchStatus(
            context.request,
            BookOrbitUrl.api(context.request.baseUrl, "/books/${context.bookId}/status"),
            bytes,
        )
        cfis.check(context)
        return result
    }

    fun requestBytes(status: String): ByteArray =
        JSONObject().put("status", status).toString().toByteArray(Charsets.UTF_8)

    private fun detailUrl(context: BookOrbitCfiContext): String =
        BookOrbitUrl.api(context.request.baseUrl, "/books/${context.bookId}")

    private fun malformed(): Nothing = throw RemoteHttpFailure(SyncFailure.Malformed)
}
