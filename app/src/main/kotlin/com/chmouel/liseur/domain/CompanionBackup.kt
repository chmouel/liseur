package com.chmouel.liseur.domain

import com.chmouel.liseur.companion.ChapterRecap
import com.chmouel.liseur.companion.ChapterRecapRow
import org.json.JSONObject

data class BackedUpCompanionRecap(val recap: ChapterRecapRow, val title: String?, val author: String?)

fun decodeCompanionBackup(text: String): List<BackedUpCompanionRecap> {
    val root = JSONObject(text)
    require(root.opt("format") == 1 && root.opt("application") == "liseur")
    val entries = root.getJSONArray("recaps")
    val ids = mutableSetOf<Pair<String, String>>()
    return List(entries.length()) { index ->
        val entry = entries.getJSONObject(index)
        fun text(key: String): String = (entry.opt(key) as? String)?.takeIf { it.isNotBlank() }
            ?: error("Invalid recap field: $key")
        fun number(key: String): Long {
            val value = entry.opt(key)
            require((value is Int || value is Long) && (value as Number).toLong() >= 0)
            return (value as Number).toLong()
        }
        fun metadata(key: String): String? = if (entry.isNull(key)) null else {
            entry.opt(key) as? String ?: error("Invalid book metadata")
        }
        val bookId = text("book_id")
        val unitKey = text("unit_key")
        require(ids.add(bookId to unitKey))
        val sha = text("content_sha")
        require(sha.matches(Regex("[0-9a-f]{64}")))
        val unit = number("unit")
        val format = number("recap_format")
        require(unit <= Int.MAX_VALUE && format in 1..Int.MAX_VALUE.toLong())
        val json = text("json")
        require(ChapterRecap.decode(json) != null)
        BackedUpCompanionRecap(
            ChapterRecapRow(bookId, unitKey, unit.toInt(), sha, text("server_id"), text("model"),
                format.toInt(), json, number("saved_at"), number("used_at")),
            metadata("title"), metadata("author"),
        )
    }
}
