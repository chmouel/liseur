package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book

/**
 * Cleans up raw OPDS / EPUB metadata for titles and authors.
 */
fun sanitizeAuthor(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    if ('|' in trimmed) {
        val parts = trimmed.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2) {
            return "${parts[1]} ${parts[0]}"
        } else if (parts.size > 2) {
            return parts.joinToString(", ")
        }
    }
    return trimmed
}

fun sanitizeTitle(raw: String): String {
    val trimmed = raw.trim()
    if (":" in trimmed) {
        val parts = trimmed.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        val distinctParts = mutableListOf<String>()
        for (part in parts) {
            if (distinctParts.lastOrNull()?.equals(part, ignoreCase = true) != true) {
                distinctParts.add(part)
            }
        }
        return distinctParts.joinToString(": ")
    }
    return trimmed
}

val Book.displayTitle: String get() = sanitizeTitle(title)
val Book.displayAuthor: String? get() = sanitizeAuthor(author)
