package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import java.net.URI

/**
 * A free catalog a reader may start from, with no account at all.
 *
 * A preset is an address and how to recognise it again, nothing more.
 * What it is called belongs to the screen that draws it: the data layer
 * does not reach for resources.
 *
 * It is saved as an anonymous Custom connection, so recognising it here
 * adds no provider, no column and no table.
 */
enum class FreeCatalog(val address: String) {
    /** Project Gutenberg's root, whose language and topic folders `GutenbergBrowse` adds. */
    GUTENBERG(StarterCatalog.BROWSE_URL),
    ;

    /** Whether [server] is this catalog, already saved. */
    fun matches(server: RemoteServer): Boolean {
        if (server.kind != ServerKind.CUSTOM) return false
        val saved = server.catalogUrl ?: return false
        val a = identity(saved) ?: return false
        return a == identity(address)
    }

    companion object {
        /** The preset [server] is a saved copy of, for naming it by brand rather than by URL. */
        fun of(server: RemoteServer): FreeCatalog? = entries.firstOrNull { it.matches(server) }

        /**
         * What makes two addresses the same catalog.
         *
         * A setup stores the address that answered rather than the one
         * that was typed, so the stored form may carry a trailing slash
         * the preset does not, or a redirect's spelling of the host.
         * Scheme, `www.` and a trailing slash therefore do not count;
         * path and query do, because one server can serve several
         * catalogs at different paths.
         */
        internal fun identity(address: String): String? {
            val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
            return host + port + path + query
        }
    }
}
