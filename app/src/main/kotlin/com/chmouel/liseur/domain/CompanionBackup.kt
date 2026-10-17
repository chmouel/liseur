package com.chmouel.liseur.domain

import com.chmouel.liseur.companion.ChapterRecap
import com.chmouel.liseur.companion.ChapterRecapRow
import com.chmouel.liseur.companion.StorySummaries
import com.chmouel.liseur.companion.StorySummaryRow
import org.json.JSONObject

data class BackedUpCompanionRecap(val recap: ChapterRecapRow, val title: String?, val author: String?)

data class BackedUpStorySummary(val summary: StorySummaryRow, val title: String?, val author: String?)

/** A backup's companion data: chapter recaps, and the running summaries written from them. */
data class CompanionBackupContents(
    val recaps: List<BackedUpCompanionRecap> = emptyList(),
    val summaries: List<BackedUpStorySummary> = emptyList(),
) {
    fun isEmpty() = recaps.isEmpty() && summaries.isEmpty()
}

private class Entry(private val entry: JSONObject, private val what: String) {
    fun text(key: String): String = (entry.opt(key) as? String)?.takeIf { it.isNotBlank() }
        ?: error("Invalid $what field: $key")

    fun number(key: String): Long {
        val value = entry.opt(key)
        require((value is Int || value is Long) && (value as Number).toLong() >= 0)
        return (value as Number).toLong()
    }

    fun int(key: String): Int = number(key).also { require(it <= Int.MAX_VALUE) }.toInt()

    fun metadata(key: String): String? = if (entry.isNull(key)) null else {
        entry.opt(key) as? String ?: error("Invalid book metadata")
    }

    fun sha(key: String): String = text(key).also { require(it.matches(SHA)) }

    companion object {
        val SHA = Regex("[0-9a-f]{64}")
    }
}

fun decodeCompanionBackup(text: String): CompanionBackupContents {
    val root = JSONObject(text)
    require(root.opt("format") == 1 && root.opt("application") == "liseur")
    val entries = root.getJSONArray("recaps")
    val ids = mutableSetOf<Pair<String, String>>()
    val recaps = List(entries.length()) { index ->
        val entry = Entry(entries.getJSONObject(index), "recap")
        val bookId = entry.text("book_id")
        val unitKey = entry.text("unit_key")
        require(ids.add(bookId to unitKey))
        val sha = entry.sha("content_sha")
        val unit = entry.int("unit")
        val format = entry.int("recap_format")
        require(format >= 1)
        val json = entry.text("json")
        require(ChapterRecap.decode(json) != null)
        BackedUpCompanionRecap(
            ChapterRecapRow(bookId, unitKey, unit, sha, entry.text("server_id"), entry.text("model"),
                format, json, entry.number("saved_at"), entry.number("used_at")),
            entry.metadata("title"), entry.metadata("author"),
        )
    }
    // Archives from before running summaries have none.
    val summaryEntries = if (root.isNull("summaries")) null else root.getJSONArray("summaries")
    val keys = mutableSetOf<Pair<String, String>>()
    val summaries = List(summaryEntries?.length() ?: 0) { index ->
        val entry = Entry(summaryEntries!!.getJSONObject(index), "summary")
        val bookId = entry.text("book_id")
        val key = entry.sha("key")
        require(keys.add(bookId to key))
        val level = entry.int("level")
        val lo = entry.int("lo")
        val hi = entry.int("hi")
        require(level <= MAX_LEVEL && lo <= hi)
        val recapFormat = entry.int("recap_format")
        val summaryFormat = entry.int("summary_format")
        require(recapFormat >= 1 && summaryFormat >= 1)
        val deps = entry.text("deps")
        val decoded = requireNotNull(StorySummaries.decodeDeps(deps))
        require(decoded.isNotEmpty() && decoded.all { it.unit in lo..hi && it.unitKey.isNotBlank() })
        require(decoded.zipWithNext().all { (a, b) -> a.unit < b.unit })
        val summary = entry.text("text")
        require(summary.length <= StorySummaries.TOP_CHARACTERS)
        BackedUpStorySummary(
            StorySummaryRow(bookId, key, level, lo, hi, entry.sha("content_sha"), recapFormat, summaryFormat, deps,
                summary, entry.number("saved_at"), entry.number("used_at")),
            entry.metadata("title"), entry.metadata("author"),
        )
    }
    return CompanionBackupContents(recaps, summaries)
}

private const val MAX_LEVEL = 10
