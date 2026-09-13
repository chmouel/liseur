package com.chmouel.liseur.data.library

import android.net.Uri
import android.provider.DocumentsContract
import com.chmouel.liseur.data.db.Book

/**
 * The URI to actually open the file with, or null when it is not here.
 *
 * A book's stable [Book.url] is whichever tree first shelved it, and it
 * never changes: it is the key every reading position, annotation and
 * session hangs off. After a watched parent folder is removed, though,
 * [Book.source] moves to the surviving child while the stored URL is
 * still rooted at the released tree, which can no longer be read through.
 * Rebuilding the URI against the current source keeps the file openable
 * without rewriting any URL-keyed reading state.
 *
 * This touches the Android framework, so it belongs where a file is
 * opened rather than on [Book] itself: [Book.openableUrl] stays a pure
 * derivation that `domain/` code and Compose lists can ask for cheaply,
 * and only the callers that go on to read bytes pay for the rebuild.
 */
fun Book.openableUri(): String? {
    val openable = openableUrl ?: return null
    if (localUri != null) return openable
    val tree = source ?: return openable
    val docId = documentId(url) ?: return openable
    return runCatching {
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), docId).toString()
    }.getOrDefault(openable)
}
