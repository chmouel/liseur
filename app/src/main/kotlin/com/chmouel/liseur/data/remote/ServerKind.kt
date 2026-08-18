package com.chmouel.liseur.data.remote

/**
 * Which kind of server the library is connected to.
 *
 * One server is connected at a time, and its kind is what decides which
 * implementation of [CatalogSource], [FileSource], [ServerSetup] and
 * [PositionSync] the rest of the app talks to.
 *
 * Stored in the database by [name], so these spellings are part of the
 * schema and cannot be changed without a migration.
 */
enum class ServerKind(
    /**
     * What a book from this kind of server is called in the library.
     *
     * These spellings are written into `books.url`, which is the key
     * reading positions and annotations hang off, so they are as good as
     * schema: changing one would orphan every row that mentions it.
     */
    val urlPrefix: String,
) {
    /** calibre-web: OPDS to browse, the Kobo protocol to sync. */
    CALIBRE("calibre"),

    /** Komga: its own REST API throughout. */
    KOMGA("komga"),

    /**
     * liseur-sync: its own REST API to browse and download, the
     * append-only op log to sync.
     */
    LISEUR_SYNC("liseur-sync"),
    ;

    /**
     * A remote book's permanent identity. It stays the same whether or
     * not the file is on the device, so reading positions survive a
     * download being removed.
     */
    fun remoteUrl(remoteId: String) = "$urlPrefix:$remoteId"

    /** The id back out of [remoteUrl], or null if the book is not ours. */
    fun remoteId(bookUrl: String): String? =
        bookUrl.removePrefix("$urlPrefix:").takeIf { it != bookUrl }

    companion object {
        /**
         * Whether a book URL names a book that came from a server, as
         * opposed to a file the reader added on the device.
         */
        fun isRemoteUrl(bookUrl: String): Boolean =
            entries.any { bookUrl.startsWith("${it.urlPrefix}:") }

        /**
         * The kind stored under [name], defaulting to [CALIBRE].
         *
         * Every row that existed before Komga was added is a calibre-web
         * account, and the migration writes that spelling out, so the
         * fallback is only ever reached by a database from the future.
         */
        fun fromStored(name: String?): ServerKind =
            entries.firstOrNull { it.name == name } ?: CALIBRE
    }
}
