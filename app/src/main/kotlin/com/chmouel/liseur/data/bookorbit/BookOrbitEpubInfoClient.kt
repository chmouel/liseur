package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** v3.0.0 `/epub/{bookId}/info`: archive-root paths, not a package CFI. */
data class BookOrbitEpubInfo(
    val packagePath: String,
    val spine: List<SpineItem>,
) {
    data class SpineItem(val idref: String, val href: String, val mediaType: String, val linear: Boolean)

    fun agreesWith(local: BookOrbitEpubPackage): Boolean =
        packagePath == local.packagePath &&
            spine.size == local.spine.size &&
            spine.zip(local.spine).all { (remote, item) ->
                remote.idref == item.idref && remote.href == item.href &&
                    remote.linear == item.linear &&
                    remote.mediaType == local.manifest[item.idref]?.mediaType
            }

    companion object {
        fun parse(json: JSONObject): BookOrbitEpubInfo {
            val path = json.strictString("containerPath")
            val manifest = json.optJSONArray("manifest") ?: malformed()
            val items = (0 until manifest.length()).map { index ->
                val item = manifest.optJSONObject(index) ?: malformed()
                Triple(item.strictString("id"), item.strictString("href"), item.strictString("mediaType"))
            }
            if (items.map { it.first }.distinct().size != items.size) malformed()
            val byId = items.associateBy { it.first }
            val spine = json.optJSONArray("spine") ?: malformed()
            return BookOrbitEpubInfo(
                path,
                (0 until spine.length()).map { index ->
                    val item = spine.optJSONObject(index) ?: malformed()
                    val idref = item.strictString("idref")
                    val href = item.strictString("href")
                    val mediaType = item.strictString("mediaType")
                    val linear = item.opt("linear") as? Boolean ?: malformed()
                    if (byId[idref] != Triple(idref, href, mediaType)) malformed()
                    SpineItem(idref, href, mediaType, linear)
                },
            )
        }

        private fun JSONObject.strictString(key: String): String =
            (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: malformed()

        private fun malformed(): Nothing = throw RemoteHttpFailure(SyncFailure.Malformed)
    }
}

class BookOrbitEpubInfoClient(
    private val http: BookOrbitHttp,
    private val cfis: BookOrbitCfiRepository,
) {
    suspend fun read(context: BookOrbitCfiContext): BookOrbitEpubInfo = withContext(Dispatchers.IO) {
        cfis.check(context)
        val url = BookOrbitUrl.api(
            context.request.baseUrl, "/epub/${context.bookId}/info?fileId=${context.fileId}",
        )
        val info = BookOrbitEpubInfo.parse(http.getObject(context.request, url))
        cfis.check(context)
        info
    }
}
