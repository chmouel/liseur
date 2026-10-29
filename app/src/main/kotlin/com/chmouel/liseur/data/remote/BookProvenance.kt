package com.chmouel.liseur.data.remote

/**
 * Where a book on the shelf came from, as far as its own row can say.
 *
 * Only what the row records: the kind of server a catalog book's URL
 * was minted for, or the folder a local file was found in. Not the
 * server connected today, which may not be the one that sent it.
 */
sealed interface BookProvenance {
    /** A server kind with a name of its own. */
    data class Server(val kind: ServerKind) : BookProvenance

    /** A catalog connected as a Custom server, which is plain OPDS. */
    data object Opds : BookProvenance

    /** A Grimmory catalog, which this app no longer connects to. */
    data object Grimmory : BookProvenance

    /** A watched folder, by its tree URI. */
    data class Folder(val treeUri: String) : BookProvenance

    /** A file picked on its own, or one whose folder is no longer watched. */
    data object File : BookProvenance

    companion object {
        fun of(bookUrl: String, source: String?): BookProvenance {
            val kind = if (bookUrl.startsWith("browse:")) {
                // browse:<server id>:<kind prefix>:<remote id>
                val prefix = bookUrl.split(':', limit = 4).getOrNull(2)
                ServerKind.entries.firstOrNull { it.urlPrefix == prefix }
            } else {
                ServerKind.entries.firstOrNull { it.remoteId(bookUrl) != null }
            }
            return when {
                kind == ServerKind.CUSTOM -> Opds
                kind != null -> Server(kind)
                bookUrl.startsWith("grimmory:") -> Grimmory
                else -> source?.takeIf { it.isNotBlank() }?.let(::Folder) ?: File
            }
        }
    }
}
