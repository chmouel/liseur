package com.chmouel.liseur.domain

import java.text.Normalizer

/** One way of naming a book, in the vocabulary liseur-sync uses. */
data class WorkIdentifier(val kind: String, val value: String) {
    companion object {
        const val SHA256 = "sha256"
        const val PARTIAL_MD5 = "partial-md5"

        /**
         * The catalog server's own id for the book (`komga:<id>`,
         * `calibre:<uuid>`). Two devices browsing the same catalog hold
         * the same one before either has downloaded the file.
         */
        const val SOURCE = "source"

        /** The EPUB's own `dc:identifier`: an ISBN, a UUID, a calibre id. */
        const val DC = "dc"

        /** Normalised title and author: the fuzzy last resort. */
        const val TITLE_AUTHOR = "ta"
    }
}

/**
 * Everything we can say about which book a file holds.
 *
 * The server resolves in a fixed order — exact bytes, then KOReader's
 * fingerprint, then the file's own identifier, then title and author —
 * and registers every identifier it was given against whichever one
 * matched. That is how a re-encoded copy and the original end up known
 * to be the same book, so it is always worth sending all of them rather
 * than only the strongest.
 *
 * The title-and-author identifier is matched on the server as a plain
 * string, which means the normalisation below *is* the interoperability
 * contract: two clients that spell it differently will never agree, and
 * neither of them will be told they disagree. It is therefore kept
 * deliberately dull and never changed casually.
 */
object WorkIdentifiers {

    fun of(
        fingerprint: BookFingerprint?,
        sourceId: String? = null,
        dcIdentifier: String?,
        title: String?,
        author: String?,
    ): List<WorkIdentifier> = buildList {
        fingerprint?.let {
            add(WorkIdentifier(WorkIdentifier.SHA256, it.sha256.lowercase()))
            add(WorkIdentifier(WorkIdentifier.PARTIAL_MD5, it.partialMd5.lowercase()))
        }
        sourceId?.takeIf { it.isNotBlank() }
            ?.let { add(WorkIdentifier(WorkIdentifier.SOURCE, it)) }
        dcIdentifier?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() && it !in USELESS_IDENTIFIERS }
            ?.let { add(WorkIdentifier(WorkIdentifier.DC, it)) }
        titleAuthor(title, author)?.let { add(WorkIdentifier(WorkIdentifier.TITLE_AUTHOR, it)) }
    }

    /**
     * A title and author reduced to the part two catalogues are likely
     * to agree on: no case, no accents, no punctuation, no repeated
     * spaces.
     *
     * Null when there is no title, because an author on their own names
     * a shelf rather than a book and would collapse every one of their
     * books into the same identity.
     */
    fun titleAuthor(title: String?, author: String?): String? {
        val left = fold(title) ?: return null
        val right = fold(author).orEmpty()
        return "$left|$right"
    }

    /**
     * The stored work id, but only when it really is the file's own
     * identifier.
     *
     * `workIdOf` folds two different things into one column: the EPUB's
     * `dc:identifier` when it has a usable one, and otherwise its title
     * and author. Only the first is a `dc` identifier; sending the
     * fallback as one would tell the server that a made-up string was
     * printed in the book.
     */
    fun dcFrom(storedWorkId: String?, title: String?, author: String?): String? =
        storedWorkId?.takeIf { it != workIdOf(null, title, author) }

    private fun fold(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val flattened = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase()
        val kept = buildString(flattened.length) {
            for (character in flattened) {
                when {
                    character.isLetterOrDigit() -> append(character)
                    isEmpty() || last() != ' ' -> append(' ')
                }
            }
        }
        return kept.trim().ifEmpty { null }
    }

    private val COMBINING = Regex("\\p{Mn}+")
}
