package com.chmouel.liseur.data.library


/**
 * What a document *is*, rather than what it happens to be called.
 *
 * One file inside a folder the reader granted has two spellings, and
 * which one is written down depends only on how the book got here. A
 * folder scan builds its URI against the tree it walked, so it reads
 * `content://authority/tree/<tree>/document/<doc>`; the single-file
 * picker behind `+ -> Add Book` hands back the bare
 * `content://authority/document/<doc>`. Both name the same bytes on
 * disk, and comparing them as strings says they are different books —
 * which is exactly how the same EPUB ended up on the shelf twice
 * (issue #147).
 *
 * The document id is what the provider actually keys on, so authority
 * plus document id is the identity. It is read off the URL by hand
 * rather than through `DocumentsContract`, because a plain string
 * function can be tested on the JVM without an emulator, and because
 * the answer must not depend on whether the provider is installed.
 *
 * Null for anything that is not a content URI naming a document — a
 * `file:` URL, an `http:` URL, a copy in the app's own storage. Those
 * have only ever had one spelling, so the exact URL is already their
 * identity and inventing a second one for them could only make two
 * different books look alike.
 */
internal fun documentIdentity(url: String): String? {
    if (!url.startsWith("content://", ignoreCase = true)) return null

    val afterScheme = url.substringAfter("://")
    val authority = afterScheme.substringBefore('/', missingDelimiterValue = "")
    if (authority.isEmpty()) return null

    // The path, without a query or fragment: neither is part of what the
    // document is, and the picker is free to add either.
    val path = afterScheme.substring(authority.length)
        .substringBefore('?')
        .substringBefore('#')

    // The last one wins: a tree URI carries the tree's own id in an
    // earlier `/document/` segment on some providers, and it is the
    // trailing one that names the file.
    val marker = path.lastIndexOf(DOCUMENT_SEGMENT)
    if (marker < 0) return null

    val documentId = path.substring(marker + DOCUMENT_SEGMENT.length)
        .trimEnd('/')
        .ifEmpty { return null }

    // Percent-encoding is the provider's business and not always
    // spelled the same way twice, so identity is compared decoded.
    return authority + '\u0000' + percentDecode(documentId)
}

/**
 * Whether two URLs name the same document.
 *
 * Falls back to comparing the URLs when neither has an identity, so a
 * caller can use this alone rather than remembering which URLs the
 * identity applies to.
 */
internal fun sameDocument(url: String, other: String): Boolean {
    if (url == other) return true
    val identity = documentIdentity(url) ?: return false
    return identity == documentIdentity(other)
}

/**
 * Decodes `%xx` escapes, leaving anything malformed exactly as it was.
 *
 * `URLDecoder` is not used because it also turns `+` into a space,
 * which in a document id is a literal plus, and because it throws on
 * a trailing `%`.
 */
private fun percentDecode(value: String): String {
    if (!value.contains('%')) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        val hex = if (c == '%' && i + 2 < value.length) {
            value.substring(i + 1, i + 3).toIntOrNull(16)
        } else {
            null
        }
        if (hex == null) {
            out.append(c)
            i++
        } else {
            out.append(hex.toChar())
            i += 3
        }
    }
    return out.toString()
}

private const val DOCUMENT_SEGMENT = "/document/"
