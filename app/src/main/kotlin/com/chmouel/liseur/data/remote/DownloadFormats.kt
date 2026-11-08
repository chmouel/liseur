package com.chmouel.liseur.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * One file a catalog offers for a book: the EPUB, the "advanced" EPUB,
 * the Kobo one. [href] follows the same rules as
 * [RemoteBook.downloadHref], so it can stand in for it unchanged.
 */
data class DownloadFormat(
    val href: String,
    val type: String? = null,
    val title: String? = null,
    val sizeBytes: Long? = null,
) {
    /**
     * What makes two books' formats "the same choice" in one catalog.
     *
     * The media type alone cannot tell Standard Ebooks' compatible and
     * advanced EPUBs apart, so the link's title is part of it.
     */
    val key: String
        get() = type.orEmpty().substringBefore(';').trim().lowercase() + "|" + title.orEmpty().trim().lowercase()
}

/**
 * Which format to download, and how a book row keeps the list.
 *
 * The reader's choice is stored per catalog as a [DownloadFormat.key]
 * and applied when a download starts, so it covers every book from
 * that catalog, including ones listed before it was made.
 */
object DownloadFormats {

    /** The preferred format when the book offers it, otherwise the catalog's default (the first). */
    fun pick(formats: List<DownloadFormat>, preferred: String?): DownloadFormat? =
        formats.firstOrNull { preferred != null && it.key == preferred } ?: formats.firstOrNull()

    /**
     * What a download of [chosen] will cost, or [bookSize] when the book
     * offers no format to choose from.
     *
     * A chosen format that omits its length stays unknown: the default
     * file's size would describe a different download.
     */
    fun sizeOf(chosen: DownloadFormat?, bookSize: Long?): Long? =
        if (chosen == null) bookSize else chosen.sizeBytes

    /** Null unless there is a choice to make: one format is already [RemoteBook.downloadHref]. */
    fun encode(formats: List<DownloadFormat>): String? {
        if (formats.size < 2) return null
        val array = JSONArray()
        formats.forEach { format ->
            array.put(
                JSONObject().apply {
                    put("href", format.href)
                    format.type?.let { put("type", it) }
                    format.title?.let { put("title", it) }
                    format.sizeBytes?.let { put("size", it) }
                },
            )
        }
        return array.toString()
    }

    fun decode(text: String?): List<DownloadFormat> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val href = item.optString("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DownloadFormat(
                href = href,
                type = item.optString("type").takeIf { it.isNotEmpty() },
                title = item.optString("title").takeIf { it.isNotEmpty() },
                sizeBytes = if (item.has("size")) item.optLong("size") else null,
            )
        }
    }
}
