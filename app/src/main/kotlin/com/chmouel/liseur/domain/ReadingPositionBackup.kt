package com.chmouel.liseur.domain

import java.util.Locale
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** Portable reading state; account baselines and acknowledgements stay on the device. */
data class BackedUpReadingPosition(
    val bookId: String,
    val title: String?,
    val author: String?,
    val locatorJson: String,
    val progression: Double?,
    val readAt: Long,
)

fun decodeReadingPositionBackup(text: String): List<BackedUpReadingPosition> {
    val root = JSONObject(text)
    require(root.opt("format") == 1 && root.opt("application") == "liseur")
    val entries = root.getJSONArray("positions")
    val ids = mutableSetOf<String>()
    return List(entries.length()) { index ->
        val entry = entries.getJSONObject(index)
        val bookId = entry.opt("book_id") as? String
        require(!bookId.isNullOrBlank() && ids.add(bookId))
        val locator = if (entry.isNull("locator")) "{}" else {
            entry.opt("locator") as? String ?: error("Invalid locator")
        }
        val progression = if (entry.isNull("progression")) null else {
            (entry.opt("progression") as? Number)?.toDouble()?.also {
                require(it.isFinite() && it in 0.0..1.0)
            } ?: error("Invalid progression")
        }
        require(if (locator == "{}") progression != null else Locator.fromJSON(JSONObject(locator)) != null)
        val readAt = entry.opt("read_at")
        require((readAt is Long || readAt is Int) && (readAt as Number).toLong() >= 0)
        fun metadata(key: String): String? {
            if (entry.isNull(key)) return null
            return entry.opt(key) as? String ?: error("Invalid book metadata")
        }
        BackedUpReadingPosition(bookId, metadata("title"), metadata("author"), locator,
            progression, (readAt as Number).toLong())
    }
}

/** Position replacement requires an unambiguous metadata match when the URL changed. */
fun matchBackedUpReadingPosition(position: BackedUpReadingPosition, known: List<KnownBook>): String {
    if (known.any { it.bookId == position.bookId }) return position.bookId
    val title = position.title?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return position.bookId
    val author = position.author?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    return known.singleOrNull {
        it.title.trim().lowercase(Locale.ROOT) == title &&
            it.author?.trim()?.lowercase(Locale.ROOT)?.takeIf { value -> value.isNotEmpty() } == author
    }?.bookId ?: position.bookId
}
