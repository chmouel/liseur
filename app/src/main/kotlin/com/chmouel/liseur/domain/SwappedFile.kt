package com.chmouel.liseur.domain

/**
 * Telling apart the two reasons a file changes underneath the library.
 *
 * Most of the time a file is rewritten because it is the same book again:
 * a download that was interrupted and retried, a copy with its metadata
 * tidied up, a converted edition. Your place in it and everything you
 * marked in it are still yours.
 *
 * Sometimes the path is simply reused for something else. Then the
 * highlights quote sentences the book does not contain and the saved
 * position lands wherever that offset happens to fall — so keeping them
 * is worse than starting fresh.
 */

/**
 * What a file says it is, as one string: the EPUB's own identifier when
 * it has a usable one, otherwise its title and author.
 *
 * Publishers are not required to make identifiers unique and some tools
 * emit the same placeholder for every file they produce, so a handful of
 * well-known useless values are ignored in favour of the title.
 */
fun workIdOf(identifier: String?, title: String?, author: String?): String? {
    val id = identifier?.trim()?.lowercase()
    if (!id.isNullOrEmpty() && id !in USELESS_IDENTIFIERS) return id
    val fallback = listOfNotNull(title?.trim(), author?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" — ")
        .lowercase()
    return fallback.ifEmpty { null }
}

/**
 * Whether a rewritten file still holds the book we indexed before.
 *
 * Unknown counts as unchanged. Books indexed before this was recorded
 * have no stored answer, and a file that will not say what it is tells us
 * nothing either — in both cases the reader keeps what they had, because
 * guessing wrongly here throws away work they cannot get back.
 */
fun isSameWork(stored: String?, found: String?): Boolean =
    stored == null || found == null || stored == found

private val USELESS_IDENTIFIERS = setOf(
    "urn:uuid:00000000-0000-0000-0000-000000000000",
    "00000000-0000-0000-0000-000000000000",
    "urn:uuid:none",
    "none",
    "null",
    "unknown",
    "id",
    "bookid",
    "uuid_id",
    "calibre_id",
)
