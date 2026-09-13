package com.chmouel.liseur.data.library

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction


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
/** The provider's document id from a content URI, decoded. */
internal fun documentId(url: String): String? =
    rawDocumentId(url)?.takeIf(::addressable)

/** How specific a watched tree is, for picking the innermost one. */
internal fun documentIdLength(url: String): Int = rawDocumentId(url)?.length ?: 0

/** Which provider serves a document or tree URI. */
internal fun contentAuthority(url: String): String? = treeOrDocument(url)?.first

private fun rawDocumentId(url: String): String? = treeOrDocument(url)?.second

/**
 * Whether an id can be handed back to `DocumentsContract`.
 *
 * An id carrying bytes that are not text (see [percentDecode]) cannot
 * be: building a URI from it re-encodes those placeholders as UTF-8 and
 * names some other document, or nothing at all. Comparing such an id is
 * still exact and still useful, so only the addressing is refused.
 */
private fun addressable(id: String) = id.none { it in ESCAPE_FIRST..ESCAPE_LAST }

internal fun documentIdentity(url: String): String? {
    val (authority, id) = contentDocument(url) ?: return null
    return authority + '\u0000' + id
}

/**
 * Which watched folder still holds [bookUrl], if any, going by the ids.
 *
 * A last resort, and a guess: it reads a document id as a path, which a
 * provider is under no obligation to make true. An opaque-id provider can
 * hold the book in a tree this says nothing about. Ask the provider, or
 * walk the tree, in preference to this; use it only where neither was
 * possible, because refusing to answer at all would delete a book on no
 * evidence whatsoever.
 *
 * Parent and child folders can overlap; the most specific tree wins so
 * removing the parent rehomes onto the child rather than the other way
 * around.
 */
internal fun folderContainingDocument(
    bookUrl: String,
    folderUrls: Collection<String>,
): String? {
    val (bookAuthority, bookId) = contentDocument(bookUrl) ?: return null
    return folderUrls
        .filter { folderUrl ->
            val (folderAuthority, treeId) = treeOrDocument(folderUrl) ?: return@filter false
            folderAuthority == bookAuthority &&
                (bookId == treeId || bookId.startsWith("$treeId/"))
        }
        .maxByOrNull { documentIdLength(it) }
}

/**
 * What the book's own provider said about each candidate tree.
 *
 * [held] is the most specific tree that answered "yes", or null if none
 * did. [unanswered] are the trees that would not say, which have been
 * ruled neither in nor out and so must be settled some other way.
 */
internal class Containment(val held: String?, val unanswered: List<String>)

/**
 * Asks each candidate tree whether it holds [bookUrl].
 *
 * [isChild] is what the provider said about one tree: `true`, or `null`
 * where it did not say so. Anything short of a yes is reported in
 * [Containment.unanswered], for the caller to settle some other way,
 * rather than counted as a denial — a refusal and a plain "no" reach the
 * caller in the same words, and one of them must not delete a book. The
 * two are kept apart per tree because survivors can span two
 * authorities.
 *
 * Only trees from the book's own provider are asked at all. A document id
 * is unique within one authority and meaningless outside it, so rebuilding
 * the book's id under a different provider's tree asks that provider about
 * whatever *it* happens to keep under that id — and a "yes" to that
 * question rehomes the row onto a different file entirely.
 */
internal fun askHoldingFolder(
    bookUrl: String,
    folderUrls: List<String>,
    isChild: (String) -> Boolean?,
): Containment {
    val bookAuthority = contentDocument(bookUrl)?.first
        ?: return Containment(null, emptyList())
    val sameProvider = folderUrls.filter { treeOrDocument(it)?.first == bookAuthority }
    val unanswered = mutableListOf<String>()
    val held = sameProvider.filter { folderUrl ->
        isChild(folderUrl) ?: run { unanswered += folderUrl; false }
    }
    return Containment(held.maxByOrNull { documentIdLength(it) }, unanswered)
}

/** A shelved file, or the root of a watched tree when the picker omits `/document/`. */
private fun treeOrDocument(url: String): Pair<String, String>? {
    contentDocument(url)?.let { return it }
    return treeRoot(url)
}

private fun contentDocument(url: String): Pair<String, String>? {
    val (authority, path) = contentPath(url) ?: return null

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
    return authority to percentDecode(documentId)
}

private fun treeRoot(url: String): Pair<String, String>? {
    val (authority, path) = contentPath(url) ?: return null
    if (path.contains(DOCUMENT_SEGMENT)) return null
    val marker = path.indexOf(TREE_SEGMENT)
    if (marker < 0) return null
    val treeId = path.substring(marker + TREE_SEGMENT.length)
        .trimEnd('/')
        .ifEmpty { return null }
    return authority to percentDecode(treeId)
}

private fun contentPath(url: String): Pair<String, String>? {
    if (!url.startsWith("content://", ignoreCase = true)) return null

    val afterScheme = url.substringAfter("://")
    val authority = afterScheme.substringBefore('/', missingDelimiterValue = "")
    if (authority.isEmpty()) return null

    // The path, without a query or fragment: neither is part of what the
    // document is, and the picker is free to add either.
    val path = afterScheme.substring(authority.length)
        .substringBefore('?')
        .substringBefore('#')
    return authority to path
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
 * Decodes `%xx` escapes as UTF-8 bytes, keeping anything malformed distinct.
 *
 * `URLDecoder` is not used because it also turns `+` into a space,
 * which in a document id is a literal plus, and because it throws on
 * a trailing `%`.
 *
 * A run of bytes that is not UTF-8 at all is still part of what the
 * document *is*, so it is kept rather than dropped — but it cannot be
 * kept as text. Re-emitting it as `%FF` would spell it exactly as a
 * document genuinely named `%FF` (whose URL escapes the percent, as
 * `%25FF`), and two different files sharing one identity is how the
 * wrong row gets deleted. Each such byte becomes a lone low surrogate
 * instead, which a strict UTF-8 decode can never produce, so the two
 * can never be confused. Nothing may address an id spelled that way;
 * see [addressable].
 */
private fun percentDecode(value: String): String {
    if (!value.contains('%')) return value
    val bytes = ArrayList<Byte>()
    val out = StringBuilder(value.length)

    fun flushBytes() {
        if (bytes.isEmpty()) return
        val raw = bytes.toByteArray()
        // Strict, because the lenient decode turns every malformed run
        // into U+FFFD: `%FF.epub` and `%FE.epub` would then share one
        // identity, and this decides which book is which on the way to
        // deleting one of them.
        val decoded = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        }.getOrNull()
        if (decoded != null) {
            out.append(decoded)
        } else {
            raw.forEach { out.append(ESCAPE_FIRST + (it.toInt() and 0xFF)) }
        }
        bytes.clear()
    }

    var i = 0
    while (i < value.length) {
        if (value[i] == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.add(hex.toByte())
                i += 3
                continue
            }
        }
        flushBytes()
        out.append(value[i])
        i++
    }
    flushBytes()
    return out.toString()
}

private const val DOCUMENT_SEGMENT = "/document/"
private const val TREE_SEGMENT = "/tree/"

/**
 * Where a byte that is not text is kept: `U+DC00 + byte`.
 *
 * Lone low surrogates, which a strict UTF-8 decode rejects outright and
 * so can never appear in a decoded id.
 */
private const val ESCAPE_FIRST = '\uDC00'
private const val ESCAPE_LAST = '\uDCFF'
