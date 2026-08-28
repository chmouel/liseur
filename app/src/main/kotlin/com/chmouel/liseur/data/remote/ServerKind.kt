package com.chmouel.liseur.data.remote

/**
 * How much of a reading position a kind of server can carry.
 *
 * A claim about the *kind*, answerable before anything has been typed,
 * which is what makes it the thing the server picker can show: choosing
 * where your library lives is partly choosing whether your place in a
 * book follows you, and finding that out after connecting is finding it
 * out too late.
 *
 * Deliberately not [com.chmouel.liseur.data.db.RemoteServer.canSync],
 * which asks a different question — whether *this* account is ready to
 * sync right now, and for calibre-web that waits on a Kobo token. The
 * two must not be folded together: a picker that read `canSync` would
 * have to invent an account to ask, and a repository that read this
 * would offer calibre-web a sync it has no token for.
 */
enum class SyncAbility {
    /** The exact spot in the book, restored on every device. */
    EXACT,

    /** How far through the book, but not the exact spot. */
    PROGRESSION,

    /** Nothing: the server has nowhere to put a reading position. */
    NONE,
}

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

    /**
     * Grimmory, reached through the Komga-compatible REST shim it
     * serves under `/komga/api`.
     *
     * Its own kind rather than a flavour of [KOMGA] because almost
     * nothing about connecting to it is the same: it signs in with a
     * username and password rather than an API key, lives under a path
     * prefix, lists its catalog through a different route, and cannot
     * carry a reading position at all. The DTO shapes it answers with
     * are Komga's, and that is where the sharing stops.
     */
    GRIMMORY("grimmory"),
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

    /**
     * Whether this kind signs every request with the password the reader
     * typed, so that password has to be kept.
     *
     * Asked as a question about the *kind* rather than about the
     * credential, because holding a `Basic` does not settle it:
     * liseur-sync signs in with one and then deliberately throws it
     * away, having traded it for a device token.
     *
     * Keeping the distinction here means the next kind that needs a
     * stored password has one place to declare it, rather than a
     * `takeIf` buried in the repository to remember to widen — which is
     * exactly the bug this replaces, where a Grimmory account would
     * connect happily and then report lost credentials on its first
     * refresh.
     */
    val signsWithStoredPassword: Boolean
        get() = when (this) {
            CALIBRE, GRIMMORY -> true
            KOMGA, LISEUR_SYNC -> false
        }

    /**
     * Whether a KOReader sync (kosync) partner may be paired alongside
     * this kind of server.
     *
     * The pairing exists for servers that catalog books but carry no
     * reading position, which today is Grimmory alone. Offering it
     * where the server already syncs natively would leave one book with
     * two sources of truth and a conflict the reader can neither see nor
     * resolve, so the answer is no for calibre-web, Komga and
     * liseur-sync.
     *
     * Asked of the kind rather than read off the interface, because the
     * peer runs from `CompositePositionSync` and the foreground policy
     * whether or not the section is on screen. Both consult this, so a
     * peer left behind by an account switch, a crash or a restored
     * database stays quiet rather than syncing against a server nobody
     * paired it with.
     */
    val hostsKosyncPeer: Boolean
        get() = when (this) {
            GRIMMORY -> true
            CALIBRE, KOMGA, LISEUR_SYNC -> false
        }

    /**
     * How much of a reading position this kind can carry, as something
     * to say before an account exists.
     *
     * calibre-web is [SyncAbility.PROGRESSION] because the Kobo
     * protocol exchanges a percentage and nothing else, so the page
     * comes back approximately. Komga and liseur-sync exchange a whole
     * locator. Grimmory's shim answers 404 to every progress route.
     */
    val syncAbility: SyncAbility
        get() = when (this) {
            CALIBRE -> SyncAbility.PROGRESSION
            KOMGA, LISEUR_SYNC -> SyncAbility.EXACT
            GRIMMORY -> SyncAbility.NONE
        }

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
