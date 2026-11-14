package com.chmouel.liseur.domain

import org.json.JSONObject

data class BackedUpBookFile(
    val bookId: String,
    val title: String?,
    val author: String?,
    val sha256: String,
    val size: Long,
)

data class BookRestorePlan(
    val matched: Map<String, String>,
    val missing: List<BackedUpBookFile>,
)

private val SHA256_REGEX = Regex("[0-9a-f]{64}")

fun decodeBookBackup(text: String): List<BackedUpBookFile> {
    val root = JSONObject(text)
    require(root.opt("format") == 1 && root.opt("application") == "liseur")
    val entries = root.getJSONArray("books")
    val ids = mutableSetOf<String>()
    return List(entries.length()) { index ->
        val entry = entries.getJSONObject(index)
        val bookId = entry.opt("book_id") as? String
        require(!bookId.isNullOrBlank() && ids.add(bookId))
        val title = if (entry.isNull("title")) null else {
            entry.opt("title") as? String ?: error("Invalid book title")
        }
        val author = if (entry.isNull("author")) null else {
            entry.opt("author") as? String ?: error("Invalid book author")
        }
        val sha256 = entry.opt("sha256") as? String
        require(sha256 != null && SHA256_REGEX.matches(sha256))
        val rawSize = entry.opt("size")
        require((rawSize is Long || rawSize is Int) && (rawSize as Number).toLong() >= 0)
        BackedUpBookFile(bookId, title, author, sha256, (rawSize as Number).toLong())
    }
}

fun planBookRestore(
    files: List<BackedUpBookFile>,
    known: List<KnownBook>,
    copies: Map<String, String>,
): BookRestorePlan {
    val knownUrls = known.mapTo(mutableSetOf()) { it.bookId }
    val directMatches = mutableMapOf<String, String>()
    val candidatesForTitle = mutableListOf<BackedUpBookFile>()

    for (file in files) {
        when {
            file.bookId in knownUrls -> directMatches[file.bookId] = file.bookId
            file.sha256 in copies -> directMatches[file.bookId] = copies.getValue(file.sha256)
            else -> candidatesForTitle += file
        }
    }

    val claimedLibraryUrls = directMatches.values.toSet()
    val titleMatchesByRecord = candidatesForTitle.associateWith { file ->
        matchBackedUpBookUrl(file.bookId, file.title, file.author, known)
    }
    val targetClaimCounts = titleMatchesByRecord.values
        .filterNotNull()
        .groupingBy { it }
        .eachCount()

    val matched = LinkedHashMap<String, String>()
    val missing = mutableListOf<BackedUpBookFile>()
    for (file in files) {
        val direct = directMatches[file.bookId]
        if (direct != null) {
            matched[file.bookId] = direct
            continue
        }
        val titleTarget = titleMatchesByRecord[file]
        if (
            titleTarget != null &&
            titleTarget !in claimedLibraryUrls &&
            targetClaimCounts[titleTarget] == 1
        ) {
            matched[file.bookId] = titleTarget
        } else {
            missing += file
        }
    }
    return BookRestorePlan(matched, missing)
}
