package com.chmouel.liseur.data.remote

/**
 * Joining a link from a server onto the address we reach that server at.
 *
 * Shared, because the awkward case is the same for both: behind a
 * reverse proxy a server can advertise a scheme, a host or a path
 * prefix that the phone cannot use, so what comes back in a feed is
 * rebuilt onto the URL that actually answered.
 */
object RemoteUrl {

    /**
     * Turns what a user typed into a base URL: adds a scheme when none
     * was given, drops a trailing slash, and keeps any path prefix,
     * because a self-hosted server is often reverse-proxied under one.
     *
     * A query is dropped by default, because for the servers Liseur
     * knows the shape of the base is an address to build paths onto and
     * a query on it is a paste accident. Set [keepQuery] where the query
     * is part of which catalog is meant: an OPDS root commonly selects
     * a shelf, a library or a user that way, and two of those at one
     * path are two different catalogs.
     *
     * A trailing slash goes by default for the same reason: on a base
     * that paths are built onto it is noise, and `/opds` and `/opds/`
     * name the same prefix. Set [keepTrailingSlash] for an address that
     * is only ever fetched rather than built onto, where the slash is
     * part of the resource name: Project Gutenberg answers 200 to
     * `/ebooks/search.opds/` and 403 to `/ebooks/search.opds`, and
     * trimming it turned an open catalog into a refused sign-in (#219).
     *
     * Returns null when the input cannot be a URL at all.
     */
    fun normaliseBase(
        input: String,
        defaultScheme: String = "https",
        keepQuery: Boolean = false,
        keepTrailingSlash: Boolean = false,
    ): String? {
        val whole = input.trim().substringBefore('#')
        val trimmed = if (keepQuery) whole else whole.substringBefore('?')
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> return null
            else -> "$defaultScheme://$trimmed"
        }

        val schemeEnd = withScheme.indexOf("://") + 3
        // A query ends the host as surely as a slash does. Without that,
        // an address that is nothing but a query — which `keepQuery` no
        // longer strips — reads as its own hostname.
        val host = withScheme.substring(schemeEnd)
            .substringBefore('/')
            .substringBefore('?')
        if (host.isEmpty() || host.startsWith(":")) return null

        // A trailing slash is noise on a path a request is built onto,
        // but it is not the last character when a query follows it, and
        // it is not noise at all where the whole address names the
        // document to fetch.
        val query = withScheme.substringAfter('?', "")
        val path = if (query.isEmpty()) withScheme else withScheme.substringBefore('?')
        val tidied = if (keepTrailingSlash) path else path.trimEnd('/')
        return if (query.isEmpty()) tidied else "$tidied?$query"
    }

    /**
     * Whether two addresses name the same place, allowing for a
     * trailing slash on the path.
     *
     * Not an opinion about URLs: the slash is how a catalog spells the
     * document it will answer to, and the same catalog can be reached
     * under both. What makes it worth a function is that the stored
     * spelling has changed once already — Liseur used to strip the
     * slash and now keeps it — so a reader who reconnects after
     * upgrading offers the same catalog under a new name. Read as a
     * different account, that throws away every book the server had not
     * been asked for yet, along with the reading recorded against them.
     */
    fun sameAddress(first: String, second: String): Boolean =
        withoutTrailingSlash(first) == withoutTrailingSlash(second)

    /**
     * The one spelling of an address, for naming an account by.
     *
     * [sameAddress] answers the same question, and anything that keys
     * state on an address has to agree with it: an identity that says
     * "the same catalog" while the key underneath changes strands
     * whatever was filed under the old one.
     */
    fun withoutTrailingSlash(address: String): String {
        val query = address.substringAfter('?', "")
        val path = if (query.isEmpty()) address else address.substringBefore('?')
        // A bare host has no path to tidy, and trimming there would eat
        // the slashes of the scheme.
        if (!path.substringAfter("://", "").contains('/')) return address
        val tidied = path.trimEnd('/')
        return if (query.isEmpty()) tidied else "$tidied?$query"
    }

    /** Swaps `https` for `http`, used only after the user asks for it. */    fun withHttp(baseUrl: String): String =
        if (baseUrl.startsWith("https://")) {
            "http://" + baseUrl.removePrefix("https://")
        } else {
            baseUrl
        }

    /**
     * The same address spelled with the other trailing slash, or null
     * when there is no other spelling of it.
     *
     * For addresses that are fetched rather than built onto, where the
     * slash is part of the resource name and a catalog may publish one
     * form while answering only to the other. A bare host has nothing
     * to flip: `https://books.example` and `https://books.example/` are
     * one request, and offering them as two would be a second identical
     * attempt rather than a second guess.
     */
    fun flipTrailingSlash(base: String): String? {
        val query = base.substringAfter('?', "")
        val path = if (query.isEmpty()) base else base.substringBefore('?')
        if (!path.substringAfter("://", "").contains('/')) return null
        val flipped = if (path.endsWith("/")) {
            path.dropLast(1).takeIf { it.substringAfter("://", "").contains('/') } ?: return null
        } else {
            "$path/"
        }
        return if (query.isEmpty()) flipped else "$flipped?$query"
    }

    /** An API path against the server, e.g. `/api/v1/books/42/file`. */
    fun api(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    fun resolve(baseUrl: String, href: String): String {
        val base = baseUrl.trimEnd('/')
        val prefix = base.substring(base.indexOf("://") + 3).substringAfter('/', "")

        val path = when {
            href.startsWith("http://", true) || href.startsWith("https://", true) ->
                href.substring(href.indexOf("://") + 3).substringAfter('/', "")
            href.startsWith("/") -> href.removePrefix("/")
            else -> return "$base/$href"
        }

        // Behind a proxy the server already includes the path prefix that
        // the base URL carries; joining both would duplicate it.
        val relative = when {
            prefix.isEmpty() -> path
            path == prefix -> ""
            path.startsWith("$prefix/") -> path.removePrefix("$prefix/")
            else -> path
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
