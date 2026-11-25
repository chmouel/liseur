package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.sync.LibraryUploadTarget

/**
 * Where a book on this device could be sent.
 *
 * Only one of these can be active at a time (ADR-0078), and the server wins
 * when both are present, so a book is never routed to two places by one tap.
 */
internal sealed interface UploadDestination {
    data class Server(val accountKey: String) : UploadDestination

    data class LibrarySync(val target: LibraryUploadTarget) : UploadDestination
}

/** The active upload destination, or null when neither can take a book. */
internal fun uploadDestination(
    server: RemoteServer?,
    router: RemoteRouter,
    library: LibraryUploadTarget?,
): UploadDestination? =
    when {
        server != null -> if (canUploadTo(server, router)) UploadDestination.Server(server.accountKey) else null
        library != null -> UploadDestination.LibrarySync(library)
        else -> null
    }

/**
 * Books the offer and the ALWAYS policy leave alone: already queued, held or
 * withheld by the backend, owned by it, or added before the reader's last
 * answer. Before the reader has answered for this account nothing qualifies.
 *
 * The cutoff is what keeps a book the reader deselected in the picker from
 * being offered again on the next launch.
 */
internal fun LibraryUploadTarget.notOffered(books: List<Book>, owns: (Book) -> Boolean): Set<String> {
    if (!selectionSettled) return books.mapTo(mutableSetOf()) { it.url }
    return books
        .filter { book ->
            book.url in queued || book.url in held || book.url in withheld || owns(book) || book.addedAt < offerSince
        }
        .mapTo(mutableSetOf()) { it.url }
}

/**
 * Books the per-book action hides because the backend already has or is
 * getting them. The cutoff and withholding do not apply: tapping the action is
 * consent.
 */
internal fun LibraryUploadTarget.alreadyTaken(books: List<Book>, owns: (Book) -> Boolean): Set<String> =
    books
        .filter { it.url in queued || it.url in held || owns(it) }
        .mapTo(mutableSetOf()) { it.url }
